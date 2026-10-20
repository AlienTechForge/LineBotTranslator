"""Mode A (hybrid): Vision words -> Claude plans units -> clean erase -> readable fit -> Pillow render.

Round 5 changes, in the order the pixels are touched:

* every translated unit's background is measured on its own box and the unit is erased by flat
  fill (flat background) or inpainting (anything else), the dilation guarded by colour so that it
  never reaches into a neighbouring cell or rule (erase.py);
* the text is fitted readably before anything is erased: the full translation, then grown sideways
  into the free lane, then the planner's `short` alternative; a minor unit that is still too small
  is left untouched (render.py, geometry.free_lane);
* the output goes through the vision judge like a Mode B attempt, and when the verdict is not
  `pass` the planner is called once more with what the judge found (`MODE_A_RETRY`); both attempts
  are kept and verify.choose_attempt picks the one that is shown.

Round 6 changes:

* the background of a unit is judged on its box and on a ring around it; a flat fill paints the
  ring's colour, a lane extension has to pass the same test, anything the ring does not vouch for
  is inpainted (erase.py, `_flat_run`);
* the units of a planner `group` are drawn at one weight and one font size (`_group_weights`,
  `_settle_group_size`); the planner call itself may take a second look at the words it left out
  (claude_units.request_units, recorded as `coverage_pass` / `planner_coverage`).
"""

from __future__ import annotations

import json
import logging
import math
import statistics
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import numpy as np
from PIL import Image

from . import claude_units, verify, vision
from .colors import ColorChoice, hex_color, pick_colors
from .erase import (
    BackgroundInfo,
    EraseUnit,
    UnitMask,
    build_polygon_mask,
    build_unit_mask,
    erase_units,
    measure_background,
    occupied_map,
    region_agrees,
)
from .geometry import UnitGeometry, compute_geometry, extension_polygons, free_lane, glyph_height
from .imaging import LoadedImage
from .layout import MAX_GROW, MIN_FIT_RATIO
from .models import ClaudeUnit, OcrResult, Word
from .render import SKIP_TOO_SMALL, UnitRender, growth_limits, paste_unit, render_unit

log = logging.getLogger("lab.pipeline_a")

FLAT_COLUMN_SHARE = 0.97  # a column of the lane is background when this share of its samples is
LANE_SAMPLE_STEP_PX = 2.0


@dataclass
class RenderResult:
    output: np.ndarray
    erased: np.ndarray
    units: list[dict]
    timings: dict[str, int] = field(default_factory=dict)
    warnings: list[str] = field(default_factory=list)


def _norm(text: str) -> str:
    return " ".join(text.split()).casefold()


def classify_unit(unit: ClaudeUnit) -> tuple[str, str | None]:
    """translated | kept | skipped (with the reason)."""
    if unit.keep:
        return "kept", "keep=true"
    if not unit.translation.strip():
        return "skipped", "empty translation"
    if _norm(unit.translation) == _norm(unit.source_text):
        return "skipped", "translation equals the source text"
    return "translated", None


@dataclass
class _Prepared:
    uid: str
    unit: ClaudeUnit
    words: list[Word]
    geom: UnitGeometry
    action: str
    reason: str | None
    report: dict
    mask: UnitMask | None = None
    info: BackgroundInfo | None = None
    colors: ColorChoice | None = None
    render: UnitRender | None = None
    extension: UnitMask | None = None
    bold: bool = False  # the weight the unit is drawn in (one value for a whole group)
    lane: tuple[float, float] = (0.0, 0.0)


def _base_report(uid: str, unit: ClaudeUnit, words: list[Word], geom: UnitGeometry, action, reason):
    report = {
        "id": uid,
        "word_ids": list(unit.word_ids),
        "source_text": unit.source_text,
        "translation": unit.translation,
        "short": unit.short,
        "direction": unit.direction,
        "rotated": unit.rotated,
        "role": unit.role,
        "align": unit.align,
        "weight": unit.weight,
        "importance": unit.importance,
        "group": unit.group,
        "group_size": None,
        "group_weight": None,
        "group_size_broken": False,
        "keep": unit.keep,
        "notes": unit.notes,
        "paragraphs": sorted({w.para for w in words if w.para is not None}),
        "action": action,
        "skip_reason": reason,
    }
    report.update(geom.to_dict())
    if unit.rotated and abs(geom.angle) < 5:
        report["consistency"] = "model says rotated=true but the word baselines are level"
    elif not unit.rotated and 10 < abs(geom.angle) < 170 and abs(abs(geom.angle) - 90) > 10:
        report["consistency"] = f"model says rotated=false but the word baselines are at {geom.angle:.0f} deg"
    return report


LANE_TEST_STEPS = 8  # a lane that fails the ring's test is shortened in this many steps before it is given up


def _flat_run(rgb: np.ndarray, geom: UnitGeometry, axis: int, along: bool, limit: float, info: BackgroundInfo) -> float:
    """How far beyond the frame edge (up to `limit` px) the image keeps the unit's background colour.

    Columns of samples across the frame are checked outward in 2 px steps; the run ends at the first
    column in which fewer than 97% of the samples are within the unit's colour tolerance. Round 6: the
    run must also pass the rule of the ring (erase.region_agrees): its pixels flat, their median within
    RING_MATCH_MAX of the colour the unit is filled with. A run that fails is shortened until it
    passes, down to nothing (the unit then falls back to `short` or a smaller size instead of growing
    into a gradient or a slightly different paper).
    """
    if limit <= 1.0:
        return 0.0
    u, v = geom.axes
    direction, cross = (u, v) if axis == 0 else (v, u)
    half_along, half_cross = geom.size[axis] / 2.0, geom.size[1 - axis] / 2.0
    sign = 1.0 if along else -1.0
    offsets = np.arange(LANE_SAMPLE_STEP_PX, limit + LANE_SAMPLE_STEP_PX, LANE_SAMPLE_STEP_PX)
    across = np.linspace(-half_cross, half_cross, max(3, int(2 * half_cross / LANE_SAMPLE_STEP_PX) + 1))
    centre = np.asarray(geom.center, dtype=np.float64)
    pts = (
        centre
        + (sign * (half_along + offsets))[:, None, None] * direction[None, None, :]
        + across[None, :, None] * cross[None, None, :]
    )
    h, w = rgb.shape[:2]
    xs = np.clip(np.round(pts[..., 0]).astype(int), 0, w - 1)
    ys = np.clip(np.round(pts[..., 1]).astype(int), 0, h - 1)
    colors = rgb[ys, xs].astype(np.float32)
    fill = np.asarray(info.fill_color, np.float32)
    dist = np.linalg.norm(colors - fill, axis=2)
    ok = (dist <= info.tolerance).mean(axis=1) >= FLAT_COLUMN_SHARE
    bad = np.flatnonzero(~ok)
    n = int(bad[0]) if bad.size else len(offsets)
    step = max(1, -(-n // LANE_TEST_STEPS))
    while n > 0:
        if region_agrees(colors[:n].reshape(-1, 3), fill)[0]:
            break
        n -= step
    return float(offsets[n - 1]) if n > 0 else 0.0


def _group_weights(translated: list[_Prepared]) -> dict[str, bool]:
    """group id -> bold, one value for every unit of the group (round 6).

    The planner's `weight` wins when all the units of the group agree; otherwise the majority of
    (planner says bold OR the stroke estimate says bold) decides, and a tie is regular.
    """
    members: dict[str, list[_Prepared]] = {}
    for p in translated:
        if p.unit.group:
            members.setdefault(p.unit.group, []).append(p)
    out: dict[str, bool] = {}
    for gid, group in members.items():
        planner = {p.unit.weight for p in group}
        if len(planner) == 1:
            out[gid] = planner == {"bold"}
        else:
            votes = sum(1 for p in group if p.unit.weight == "bold" or p.colors.bold)
            out[gid] = votes * 2 > len(group)
    return out


def render_units(rgb: np.ndarray, ocr: OcrResult, units: list[ClaudeUnit], lang: str) -> RenderResult:
    """Pure image work (no network): geometry, background and colours, fit, one erase pass, text."""
    img_h, img_w = rgb.shape[:2]
    words_by_id = {w.id: w for w in ocr.words}
    prepared: list[_Prepared] = []
    warnings: list[str] = []
    for index, unit in enumerate(units, start=1):
        uid = f"u{index:02d}"
        words = [words_by_id[i] for i in unit.word_ids]
        geom = compute_geometry(words, unit.direction, unit.rotated)
        action, reason = classify_unit(unit)
        prepared.append(
            _Prepared(uid, unit, words, geom, action, reason, _base_report(uid, unit, words, geom, action, reason))
        )

    # ---- measure: background of every translated unit on its own box and on the ring around it, colours from the original
    t0 = time.perf_counter()
    translated = [p for p in prepared if p.action == "translated"]
    occupied = occupied_map((img_h, img_w), [w.polygon for w in ocr.words]) if translated else None
    for p in translated:
        p.mask = build_unit_mask((img_h, img_w), p.words, p.geom.glyph_h)
        if p.mask is None:
            p.action, p.reason = "skipped", "outside the image"
            p.report.update(action="skipped", skip_reason="outside the image")
            continue
        p.info = measure_background(rgb, p.mask, occupied)
    translated = [p for p in translated if p.action == "translated"]
    proxy = rgb.copy()  # the original with each unit's box painted in its background colour
    for p in translated:
        p.mask.roi(proxy)[p.mask.raw > 0] = p.info.fill_color
    for p in translated:
        p.colors = pick_colors(rgb, proxy, p.mask, p.geom.corners, p.geom.glyph_h)
    group_bold = _group_weights(translated)
    for p in translated:
        p.bold = group_bold[p.unit.group] if p.unit.group else (p.unit.weight == "bold" or p.colors.bold)
        if p.unit.group:
            p.report["group_weight"] = "bold" if p.bold else "regular"
    measure_ms = int((time.perf_counter() - t0) * 1000)

    # ---- fit: readable text first, then growth into the free lane, then `short`; decides what is erased
    t1 = time.perf_counter()
    listed = {w.id for p in prepared for w in p.words}
    loose = [np.asarray(w.polygon, dtype=np.float64) for w in ocr.words if w.id not in listed]
    frames = {p.uid: p.geom.corners for p in prepared}  # grows as units grow, so that two never meet

    def fit_unit(p: _Prepared, target_size: int | None = None) -> UnitRender | None:
        """Lane, then render_unit; with `target_size` None when that exact size cannot be reached."""
        axis = 0 if p.unit.direction == "horizontal" else 1
        lane = (0.0, 0.0)
        if p.info.flat:
            obstacles = [c for uid, c in frames.items() if uid != p.uid] + loose
            free = free_lane(p.geom, axis, obstacles, (img_h, img_w))
            want = growth_limits(p.geom.size[axis], free, p.unit.align)
            lane = (
                _flat_run(rgb, p.geom, axis, False, want[0], p.info) if want[0] > 0 else 0.0,
                _flat_run(rgb, p.geom, axis, True, want[1], p.info) if want[1] > 0 else 0.0,
            )
        render = render_unit(
            p.unit.translation, p.unit.direction, lang, p.unit.role, p.geom, p.bold,
            short=p.unit.short, align=p.unit.align, importance=p.unit.importance, lane=lane, target_size=target_size,
        )  # fmt: skip
        if render is not None:
            p.lane = lane
            p.report["lane"] = [round(lane[0], 1), round(lane[1], 1)]
        return render

    def settle(p: _Prepared, render: UnitRender) -> None:
        """Adopt a render: its frame is now an obstacle for the others, and a grown frame has an extension to erase."""
        p.render = render
        frames[p.uid] = render.geom.corners
        p.extension = None
        if sum(render.grow) > 0.5:
            axis = 0 if p.unit.direction == "horizontal" else 1
            p.extension = build_polygon_mask(
                (img_h, img_w), extension_polygons(p.geom, axis, render.grow[0], render.grow[1])
            )

    for p in translated:
        try:
            render = fit_unit(p)
            if render.skipped:
                p.render = render
                p.action, p.reason = "skipped", render.skipped
                p.report.update(action="skipped", skip_reason=render.skipped, min_fit_px=render.min_fit_px)
                continue
            settle(p, render)
        except Exception as exc:  # noqa: BLE001 - one bad unit must not sink the others
            log.warning("stage=fit unit %s failed: %s", p.uid, type(exc).__name__)
            p.action, p.reason = "skipped", f"fit failed: {type(exc).__name__}"
            p.report.update(action="skipped", skip_reason=p.reason, error=f"{type(exc).__name__}: {exc}")
            warnings.append(f"{p.uid}: fitting failed ({type(exc).__name__}: {exc}); the area stays untouched")
    translated = [p for p in translated if p.action == "translated"]

    # ---- group typography: one size for the rows of a table column or list (the weight was settled above)
    members: dict[str, list[_Prepared]] = {}
    for p in translated:
        if p.unit.group:
            members.setdefault(p.unit.group, []).append(p)
    for gid, group in members.items():
        try:
            _settle_group_size(gid, group, fit_unit, settle, warnings)
        except Exception as exc:  # noqa: BLE001 - the units keep the size they were fitted with
            log.warning("stage=fit group failed: %s", type(exc).__name__)
            warnings.append(f"group {gid}: could not unify the font size ({type(exc).__name__}: {exc})")
    fit_ms = int((time.perf_counter() - t1) * 1000)

    # ---- erase: flat fill or inpaint per unit, colour-guarded; skipped and unlisted words keep their pixels
    t2 = time.perf_counter()
    translated_ids = {w.id for p in translated for w in p.words}
    protect = []
    for w in ocr.words:
        if w.id in translated_ids:
            continue
        pm = build_unit_mask((img_h, img_w), [w], glyph_height([w]), dilate=0)
        if pm is not None:
            protect.append(pm)
    erase = erase_units(
        rgb, [EraseUnit(p.uid, p.mask, p.info, p.extension) for p in translated], protect
    )
    erased = erase.erased
    erase_ms = measure_ms + int((time.perf_counter() - t2) * 1000)
    log.info(
        "stage=erase ms=%d units=%d flat=%d inpaint=%d image=%dx%d",
        erase_ms, len(translated), sum(1 for m in erase.methods.values() if m == "flat"),
        sum(1 for m in erase.methods.values() if m == "inpaint"), img_w, img_h,
    )  # fmt: skip

    # ---- draw
    t3 = time.perf_counter()
    canvas = Image.fromarray(erased.copy())
    overflowed = 0
    for p in prepared:
        if p.action != "translated" or p.render is None:
            continue
        ur = p.render
        try:
            ink = paste_unit(canvas, ur, p.geom, p.colors.text)
            p.report.update(
                {
                    "font_size": ur.font_size,
                    "font": ur.font_name,
                    "bold": ur.bold,
                    "layout": ur.mode,
                    "lines": ur.lines,
                    "fitted": ur.fitted,
                    "readable": ur.readable,
                    "overflowed": ur.overflowed,
                    "truncated": ur.truncated,
                    "text_used": ur.text_used,
                    "fit_ratio": round(ur.font_size / p.geom.glyph_h, 2) if p.geom.glyph_h else None,
                    "min_fit_px": ur.min_fit_px,
                    "grow": [round(ur.grow[0], 1), round(ur.grow[1], 1)],
                    "erase_method": erase.methods[p.uid],
                    "erase_reason": p.info.reason,
                    "erase_bg": hex_color(p.info.color),
                    "erase_fill": hex_color(p.info.fill_color),
                    "erase_ring_color": None if p.info.ring_color is None else hex_color(p.info.ring_color),
                    "erase_ring_spread": None if p.info.ring_spread is None else round(p.info.ring_spread, 1),
                    "erase_ring_gap": None if p.info.ring_gap is None else round(p.info.ring_gap, 1),
                    "erase_extension": erase.extension.get(p.uid),
                    "erase_spread": round(p.info.spread, 1),
                    "erase_ink_share": round(p.info.ink_share, 2),
                    "erase_px": erase.mask_px[p.uid],
                    "text_color": hex_color(p.colors.text),
                    "bg_color": hex_color(p.colors.background),
                    "color_fallback": p.colors.fallback,
                    "contrast": round(p.colors.contrast, 1),
                    "stroke_ratio": None if p.colors.stroke_ratio is None else round(p.colors.stroke_ratio, 3),
                    "ink_bbox": ink,
                }
            )
            overflowed += int(ur.overflowed)
        except Exception as exc:  # noqa: BLE001 - one bad unit must not sink the others
            log.warning("stage=render unit %s failed: %s", p.uid, type(exc).__name__)
            p.report.update({"error": f"{type(exc).__name__}: {exc}", "fitted": False, "overflowed": False})
            warnings.append(f"{p.uid}: render failed ({type(exc).__name__}: {exc}); the area stays erased")
    render_ms = fit_ms + int((time.perf_counter() - t3) * 1000)
    log.info("stage=render ms=%d units=%d overflowed=%d", render_ms, len(translated), overflowed)

    return RenderResult(
        output=np.array(canvas, dtype=np.uint8),
        erased=erased,
        units=[p.report for p in prepared],
        timings={"erase_ms": erase_ms, "render_ms": render_ms},
        warnings=warnings,
    )


def _settle_group_size(gid, group: list[_Prepared], fit_unit, settle, warnings: list[str]) -> None:
    """One font size for the units of a group: the smallest size that a unit managed with its full text.

    Every unit was fitted on its own first. The group's size is then the minimum size among the units
    that were drawn readably with the full text (when none was, the minimum among the readable ones),
    but not below MIN_FIT_RATIO of the median glyph height of the group. Every unit is fitted again at
    exactly that size: the full text first, `short` when the full text does not fit at it. A unit that
    cannot reach the size keeps the smaller size it had and is flagged `group_size_broken`.
    """
    drawn = [p for p in group if p.render is not None and p.render.mask is not None and p.render.fitted]
    pool = [p for p in drawn if p.render.text_used == "full" and p.render.readable] or [
        p for p in drawn if p.render.readable
    ]
    if len(group) < 2 or not pool:
        for p in group:
            p.report.update(group_size=p.render.font_size if p.render is not None and p.render.mask is not None else None)
        return
    median_h = float(statistics.median(p.geom.glyph_h for p in group))
    floor = max(1, math.ceil(MIN_FIT_RATIO * median_h - 1e-9))
    target = max(min(p.render.font_size for p in pool), floor)
    broken = 0
    for p in group:
        render = fit_unit(p, target)
        if render is not None:
            settle(p, render)
        elif p.render.font_size != target:
            p.report["group_size_broken"] = True
            broken += 1
    for p in group:
        p.report["group_size"] = target
    if broken:
        warnings.append(f"group {gid}: {broken} unit(s) could not reach the common size of {target} px and keep their own")


def _png_bytes(arr: np.ndarray) -> bytes:
    import io

    buf = io.BytesIO()
    Image.fromarray(arr).save(buf, "PNG")
    return buf.getvalue()


def problem_units(units: list[dict]) -> list[dict]:
    """The unit reports the renderer could not draw readably: overflowed, truncated, below the readable
    size or skipped as too small (named by their source text in the planner's retry paragraph)."""
    return [
        u for u in units
        if (u.get("action") == "translated" and (u.get("overflowed") or u.get("truncated") or u.get("readable") is False))
        or u.get("skip_reason") == SKIP_TOO_SMALL
    ]  # fmt: skip


CheckFn = Callable[[bytes, tuple[int, int]], dict]


def _sum_known(values: list) -> float | None:
    known = [v for v in values if isinstance(v, (int, float))]
    return round(sum(known), 6) if known else None


def _attempt(
    n: int, image: LoadedImage, ocr: OcrResult, lang: str, out_dir: Path, feedback: str | None, check: CheckFn | None,
    meta: dict,
) -> dict:
    """One planner call, one render, the files, and (with `check`) the verification of the output."""
    t0 = time.perf_counter()
    call = claude_units.request_units(image, ocr, lang, feedback)
    result = render_units(image.rgb(), ocr, call.units, lang)
    out_dir.mkdir(parents=True, exist_ok=True)
    png = _png_bytes(result.output)
    (out_dir / f"A_a{n}.png").write_bytes(png)
    (out_dir / f"A_a{n}_erased.png").write_bytes(_png_bytes(result.erased))
    translated = [u for u in result.units if u["action"] == "translated"]
    extra: list[str] = []
    if not result.units:
        extra.append("Claude returned no usable units; the image is unchanged")
    elif not translated:
        extra.append("every unit was kept or skipped; nothing was translated")
    listed = {wid for u in result.units for wid in u["word_ids"]}
    if ocr.words and len(listed) < len(ocr.words):
        extra.append(f"{len(ocr.words) - len(listed)} of {len(ocr.words)} OCR words are in no unit and stay untouched")
    too_small = sum(1 for u in result.units if u.get("skip_reason") == SKIP_TOO_SMALL)
    if too_small:
        extra.append(f"{too_small} minor unit(s) could not be drawn at a readable size and stay untouched")
    attempt = {
        "n": n,
        "output": f"A_a{n}.png",
        "erased": f"A_a{n}_erased.png",
        "units_file": None,
        "verification_file": None,
        "elapsed_ms": int((time.perf_counter() - t0) * 1000),
        "claude_ms": call.elapsed_ms,
        "erase_ms": result.timings["erase_ms"],
        "render_ms": result.timings["render_ms"],
        "word_count": len(ocr.words),
        "unit_count": len(result.units),
        "translated_count": len(translated),
        "tokens": {"input": call.input_tokens, "output": call.output_tokens},
        "cost_usd": call.cost_usd,
        "model": call.model,
        "served_model": call.served_model,
        "stop_reason": call.stop_reason,
        "fallback_form": call.fallback_form,
        "fallback_attempts": call.attempts,
        "claude_image_size": list(call.sent_image_size) if call.sent_image_size else None,
        "word_list_tokens_est": call.word_list_tokens_est,
        "warnings": call.notes + result.warnings + extra,
        "units": result.units,
        "retry_feedback": feedback,
        "coverage_pass": bool((call.coverage or {}).get("pass")),
        "planner_coverage": call.coverage,
        "verdict": None,
        "verification": None,
        "verify_error": None,
        "verify_ms": None,
        "judge_ms": None,
        "judge_cost_usd": None,
        "judge_status": None,
    }
    try:  # the units as a file of their own, for the published bundle
        doc = {
            **meta, "attempt": n, "page": [ocr.width, ocr.height],
            "coverage_pass": attempt["coverage_pass"], "planner_coverage": call.coverage, "units": result.units,
        }  # fmt: skip
        (out_dir / f"A_a{n}_units.json").write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding="utf-8")
        attempt["units_file"] = f"A_a{n}_units.json"
    except OSError:
        log.warning("could not write A_a%d_units.json", n)
    if check is not None:
        attempt.update(check(png, (result.output.shape[1], result.output.shape[0])))
        if attempt.get("verification") is not None:
            name = f"A_a{n}_verify.json"
            try:
                doc = {**meta, "attempt": n, "image": attempt["output"], **attempt["verification"]}
                (out_dir / name).write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding="utf-8")
                attempt["verification_file"] = name
            except OSError:
                log.warning("could not write %s", name)
    log.info(
        "stage=mode-a-attempt n=%d words=%d units=%d translated=%d overflowed=%d verdict=%s",
        n, len(ocr.words), len(result.units), len(translated),
        sum(1 for u in translated if u.get("overflowed")), attempt["verdict"],
    )  # fmt: skip
    return attempt


def run_mode_a(
    image: LoadedImage,
    lang: str,
    out_dir: Path,
    source_ocr=None,
    *,
    check: CheckFn | None = None,
    retry: bool = False,
    meta: dict | None = None,
) -> dict:
    """Full Mode A. Raises on failure (the caller shows the message in Mode A's error box).

    `source_ocr` (jobs.SourceOcr) is the per-job cache of the Vision call on the source image,
    shared with the Mode B verification; without it Vision is called here. `check(png, size)` runs
    the vision judge on an output and returns the verdict fields of the attempt (jobs._check_output);
    without it nothing is verified and nothing is retried. With `retry` and a verdict that is not
    `pass`, the planner is called once more with what the judge found and the units that did not
    fit; a failing second attempt keeps the first.
    """
    t_all = time.perf_counter()
    meta = meta or {}

    if source_ocr is not None:
        ocr = source_ocr.get()
        vision_ms = source_ocr.ms or 0
    else:
        t0 = time.perf_counter()
        ocr = vision.ocr_words(image.data, image.width, image.height)
        vision_ms = int((time.perf_counter() - t0) * 1000)
    if not ocr.words:
        raise RuntimeError("Vision found no text in the image")

    attempts = [_attempt(1, image, ocr, lang, out_dir, None, check, meta)]
    retry_error = None
    first = attempts[0]
    if retry and check is not None and verify.needs_retry(first["verdict"]):
        feedback = claude_units.build_feedback(first["verification"], problem_units(first["units"]))
        if feedback:
            try:
                attempts.append(_attempt(2, image, ocr, lang, out_dir, feedback, check, meta))
            except Exception as exc:  # noqa: BLE001 - attempt 1 stays the result
                from .redaction import describe_error

                log.warning("stage=mode-a-retry failed: %s", type(exc).__name__)
                retry_error = describe_error(exc)
    chosen = verify.choose_attempt([a["verification"] for a in attempts])
    for i, a in enumerate(attempts):
        a["selected"] = i == chosen
    main = attempts[chosen]

    planner_cost = _sum_known([a["cost_usd"] for a in attempts])
    judge_cost = _sum_known([a["judge_cost_usd"] for a in attempts])
    elapsed_ms = int((time.perf_counter() - t_all) * 1000)
    log.info(
        "stage=mode-a-done ms=%d attempts=%d selected=%d verdict=%s",
        elapsed_ms, len(attempts), main["n"], main["verdict"],
    )  # fmt: skip
    verification = main["verification"] or {}
    return {
        "elapsed_ms": elapsed_ms,
        "vision_ms": vision_ms,
        "claude_ms": sum(a["claude_ms"] for a in attempts),
        "erase_ms": sum(a["erase_ms"] for a in attempts),
        "render_ms": sum(a["render_ms"] for a in attempts),
        "verify_ms": sum(a.get("verify_ms") or 0 for a in attempts),
        "judge_ms": sum(a.get("judge_ms") or 0 for a in attempts),
        "word_count": len(ocr.words),
        "unit_count": main["unit_count"],
        "translated_count": main["translated_count"],
        "tokens": {
            "input": sum(a["tokens"]["input"] for a in attempts),
            "output": sum(a["tokens"]["output"] for a in attempts),
        },
        "cost_usd": planner_cost,
        "judge_cost_usd": judge_cost,
        "total_cost_usd": _sum_known([planner_cost, judge_cost]),
        "cost_anthropic_usd": _sum_known([planner_cost, judge_cost]),  # Mode A costs Claude only
        "cost_openrouter_usd": 0.0,
        "model": main["model"],
        "served_model": main["served_model"],
        "stop_reason": main["stop_reason"],
        "fallback_form": main["fallback_form"],
        "fallback_attempts": main["fallback_attempts"],
        "claude_image_size": main["claude_image_size"],
        "word_list_tokens_est": main["word_list_tokens_est"],
        "warnings": list(main["warnings"]),
        "page": [ocr.width, ocr.height],
        "units": main["units"],
        "coverage_pass": main["coverage_pass"],
        "planner_coverage": main["planner_coverage"],
        "output": main["output"],
        "erased": main["erased"],
        "units_file": main["units_file"],
        "verdict": main["verdict"],
        "verification": main["verification"],
        "verification_file": main["verification_file"],
        "verify_error": main["verify_error"],
        "usability": verification.get("usability"),
        "coverage": verification.get("coverage"),
        "selected_attempt": main["n"],
        "attempts": attempts,
        "retry": bool(retry),
        "retry_error": retry_error,
        "max_grow": MAX_GROW,
    }
