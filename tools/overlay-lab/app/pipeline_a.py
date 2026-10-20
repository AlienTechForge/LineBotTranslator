"""Mode A (hybrid): Vision words -> Claude units -> OpenCV inpaint -> Pillow render."""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from PIL import Image

from . import claude_units, vision
from .colors import hex_color, pick_colors
from .erase import UnitMask, build_global_mask, build_unit_mask, inpaint_all
from .geometry import UnitGeometry, compute_geometry, glyph_height
from .imaging import LoadedImage
from .models import ClaudeUnit, OcrResult, Word
from .render import paste_unit, render_unit

log = logging.getLogger("lab.pipeline_a")

BOLD_ROLES = {"title", "heading"}


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


def _base_report(uid: str, unit: ClaudeUnit, words: list[Word], geom: UnitGeometry, action, reason):
    report = {
        "id": uid,
        "word_ids": list(unit.word_ids),
        "source_text": unit.source_text,
        "translation": unit.translation,
        "direction": unit.direction,
        "rotated": unit.rotated,
        "role": unit.role,
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


def render_units(rgb: np.ndarray, ocr: OcrResult, units: list[ClaudeUnit], lang: str) -> RenderResult:
    """Pure image work (no network): geometry, one inpaint for all units, colours, text."""
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

    # ---- erase: one mask, one inpaint call for every translated unit
    t0 = time.perf_counter()
    translated = [p for p in prepared if p.action == "translated"]
    translated_ids = {w.id for p in translated for w in p.words}
    for p in translated:
        p.mask = build_unit_mask((img_h, img_w), p.words, p.geom.glyph_h)
    protect = []
    for w in ocr.words:
        if w.id in translated_ids:
            continue
        pm = build_unit_mask((img_h, img_w), [w], glyph_height([w]), dilate=0)
        if pm is not None:
            protect.append(pm)
    mask = build_global_mask((img_h, img_w), [p.mask for p in translated if p.mask], protect)
    erased = inpaint_all(rgb, mask)
    erase_ms = int((time.perf_counter() - t0) * 1000)
    log.info(
        "stage=erase ms=%d units=%d mask_px=%d image=%dx%d",
        erase_ms,
        len(translated),
        int(np.count_nonzero(mask)),
        img_w,
        img_h,
    )

    # ---- colour + render
    t1 = time.perf_counter()
    canvas = Image.fromarray(erased.copy())
    overflowed = 0
    for p in prepared:
        if p.action != "translated" or p.mask is None:
            continue
        try:
            colors = pick_colors(rgb, erased, p.mask, p.geom.corners, p.geom.glyph_h)
            bold = p.unit.role in BOLD_ROLES or colors.bold
            ur = render_unit(p.unit.translation, p.unit.direction, lang, p.unit.role, p.geom, bold)
            ink = paste_unit(canvas, ur, p.geom, colors.text)
            p.report.update(
                {
                    "font_size": ur.font_size,
                    "font": ur.font_name,
                    "bold": ur.bold,
                    "layout": ur.mode,
                    "lines": ur.lines,
                    "fitted": ur.fitted,
                    "overflowed": ur.overflowed,
                    "truncated": ur.truncated,
                    "text_color": hex_color(colors.text),
                    "bg_color": hex_color(colors.background),
                    "color_fallback": colors.fallback,
                    "contrast": round(colors.contrast, 1),
                    "stroke_ratio": None if colors.stroke_ratio is None else round(colors.stroke_ratio, 3),
                    "ink_bbox": ink,
                }
            )
            overflowed += int(ur.overflowed)
        except Exception as exc:  # noqa: BLE001 - one bad unit must not sink the others
            log.exception("stage=render unit %s failed", p.uid)
            p.report.update({"error": f"{type(exc).__name__}: {exc}", "fitted": False, "overflowed": False})
            warnings.append(f"{p.uid}: render failed ({type(exc).__name__}: {exc}); the area stays erased")
    render_ms = int((time.perf_counter() - t1) * 1000)
    log.info("stage=render ms=%d units=%d overflowed=%d", render_ms, len(translated), overflowed)

    return RenderResult(
        output=np.array(canvas, dtype=np.uint8),
        erased=erased,
        units=[p.report for p in prepared],
        timings={"erase_ms": erase_ms, "render_ms": render_ms},
        warnings=warnings,
    )


def _save_png(arr: np.ndarray, path: Path) -> None:
    Image.fromarray(arr).save(path, "PNG")


def run_mode_a(image: LoadedImage, lang: str, out_dir: Path, source_ocr=None) -> dict:
    """Full Mode A. Raises on failure (the caller shows the message in Mode A's error box).

    `source_ocr` (jobs.SourceOcr) is the per-job cache of the Vision call on the source image,
    shared with the Mode B verification; without it Vision is called here.
    """
    t_all = time.perf_counter()

    if source_ocr is not None:
        ocr = source_ocr.get()
        vision_ms = source_ocr.ms or 0
    else:
        t0 = time.perf_counter()
        ocr = vision.ocr_words(image.data, image.width, image.height)
        vision_ms = int((time.perf_counter() - t0) * 1000)
    if not ocr.words:
        raise RuntimeError("Vision found no text in the image")

    call = claude_units.request_units(image, ocr, lang)
    result = render_units(image.rgb(), ocr, call.units, lang)

    out_dir.mkdir(parents=True, exist_ok=True)
    _save_png(result.output, out_dir / "A.png")
    _save_png(result.erased, out_dir / "A_erased.png")

    elapsed_ms = int((time.perf_counter() - t_all) * 1000)
    translated = [u for u in result.units if u["action"] == "translated"]
    extra: list[str] = []
    if not result.units:
        extra.append("Claude returned no usable units; the image is unchanged")
    elif not translated:
        extra.append("every unit was kept or skipped; nothing was translated")
    listed = {wid for u in result.units for wid in u["word_ids"]}
    if ocr.words and len(listed) < len(ocr.words):
        extra.append(f"{len(ocr.words) - len(listed)} of {len(ocr.words)} OCR words are in no unit and stay untouched")
    log.info(
        "stage=mode-a-done ms=%d words=%d units=%d translated=%d overflowed=%d",
        elapsed_ms,
        len(ocr.words),
        len(result.units),
        len(translated),
        sum(1 for u in translated if u.get("overflowed")),
    )
    return {
        "elapsed_ms": elapsed_ms,
        "vision_ms": vision_ms,
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
        "page": [ocr.width, ocr.height],
        "units": result.units,
        "output": "A.png",
        "erased": "A_erased.png",
    }
