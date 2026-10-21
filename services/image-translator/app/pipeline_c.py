"""Mode C: Vision words -> layout units from the geometry -> text-only translation -> erase -> fit -> Pillow render.

Google's image translation has the same five stages as Mode A (locate and read, translate, erase and fill, lay
out, composite) but analyses the layout with geometry (the OCR engine's lines, paragraphs, blocks, columns),
translates with a text-only model per block and erases with a trained inpainting model. Mode C is Mode A with
those three stages swapped; the renderer, the judge and the lab stay:

    Vision words        (vision.ocr_words, shared with Mode B's check through jobs.SourceOcr)
    layout units        segment.segment_units      no model call
    translation         claude_text.translate_units    text only, chunked, one picture as context
    erase + fit + draw  pipeline_a.render_units    flat fill or inpaint (INPAINT_MODEL), residue check
    judge               the same check as Mode A (rows are not counted: `structure_checks=False`)

There is no retry: the units do not come from a model, so a second translation call with the judge's findings
would not change what the judge complained about. The result has the shape of `pipeline_a.run_mode_a`'s plus
`segment_ms` and `segment` (the SegmentReport), and the files `C_a1.png`, `C_a1_erased.png` and `C_a1_units.json`.
"""

from __future__ import annotations

import json
import logging
import time
from pathlib import Path
from typing import Callable

from . import claude_text, segment, vision
from .imaging import LoadedImage
from .layout import MAX_GROW
from .pipeline_a import CheckFn, _png_bytes, _sum_known, render_notes, render_units

log = logging.getLogger("lab.pipeline_c")

TranslateFn = Callable[["claude_text.TextCall"], None]
FILE_PREFIX = "C_a1"


def run_mode_c(
    image: LoadedImage,
    lang: str,
    out_dir: Path,
    source_ocr=None,
    *,
    check: CheckFn | None = None,
    meta: dict | None = None,
    on_translate: TranslateFn | None = None,
) -> dict:
    """Full Mode C. Raises on failure (the caller shows the message in Mode C's error box).

    `source_ocr` (jobs.SourceOcr) is the per-job cache of the Vision call on the source image, shared with Mode A
    and the Mode B verification; without it Vision is called here. `check(png, size)` runs the vision judge on the
    output and returns the verdict fields (jobs.check_output with structure_checks=False); without it nothing is
    verified. `on_translate(call)` is called with the translation (a claude_text.TextCall) as soon as it is back,
    before anything is drawn, so that the translated text and its cost survive a render that fails afterwards.
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

    rgb = image.rgb()
    t0 = time.perf_counter()
    units, report = segment.segment_units(ocr, rgb, lang)
    segment_ms = int((time.perf_counter() - t0) * 1000)
    log.info(
        "stage=segment ms=%d words=%d lines=%d units=%d kept=%d polarity_splits=%d column_splits=%d merges=%d",
        segment_ms, report.words, report.lines, report.units, report.kept, report.polarity_splits,
        report.column_splits, report.merges,
    )  # fmt: skip
    if not units:
        raise RuntimeError("the OCR words did not make any unit")

    call = claude_text.translate_units(units, image, lang, report.source_lang)
    if on_translate is not None:
        on_translate(call)
    result = render_units(rgb, ocr, call.units, lang)
    for entry, reason in zip(result.units, report.reasons):
        entry["polarity"] = reason["polarity"]
        entry["keep_kind"] = reason["keep"]

    out_dir.mkdir(parents=True, exist_ok=True)
    png = _png_bytes(result.output)
    (out_dir / f"{FILE_PREFIX}.png").write_bytes(png)
    (out_dir / f"{FILE_PREFIX}_erased.png").write_bytes(_png_bytes(result.erased))
    translated = [u for u in result.units if u["action"] == "translated"]
    extra = render_notes(result, ocr, "the OCR words did not make any unit; the image is unchanged")
    segment_doc = report.to_dict()
    attempt = {
        "n": 1,
        "output": f"{FILE_PREFIX}.png",
        "erased": f"{FILE_PREFIX}_erased.png",
        "units_file": None,
        "verification_file": None,
        "elapsed_ms": int((time.perf_counter() - t_all) * 1000),
        "segment_ms": segment_ms,
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
        "claude_calls": call.calls,
        "chunk_sizes": call.chunk_sizes,
        "word_list_tokens_est": 0,
        "warnings": call.notes + result.warnings + extra,
        "units": result.units,
        "retry_feedback": None,
        "coverage_pass": False,
        "planner_coverage": None,
        "verdict": None,
        "verification": None,
        "verify_error": None,
        "verify_ms": None,
        "judge_ms": None,
        "judge_cost_usd": None,
        "judge_status": None,
    }
    try:  # the units as a file of their own, for the published bundle
        doc = {**meta, "mode": "C", "attempt": 1, "page": [ocr.width, ocr.height], "segment": segment_doc, "units": result.units}
        (out_dir / f"{FILE_PREFIX}_units.json").write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding="utf-8")
        attempt["units_file"] = f"{FILE_PREFIX}_units.json"
    except OSError:
        log.warning("could not write %s_units.json", FILE_PREFIX)
    if check is not None:
        attempt.update(check(png, (result.output.shape[1], result.output.shape[0])))
        if attempt.get("verification") is not None:
            name = f"{FILE_PREFIX}_verify.json"
            try:
                doc = {**meta, "mode": "C", "attempt": 1, "image": attempt["output"], **attempt["verification"]}
                (out_dir / name).write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding="utf-8")
                attempt["verification_file"] = name
            except OSError:
                log.warning("could not write %s", name)
    attempt["selected"] = True
    log.info(
        "stage=mode-c-attempt words=%d units=%d translated=%d overflowed=%d verdict=%s",
        len(ocr.words), len(result.units), len(translated), sum(1 for u in translated if u.get("overflowed")), attempt["verdict"],
    )  # fmt: skip

    translate_cost = attempt["cost_usd"]
    judge_cost = _sum_known([attempt["judge_cost_usd"]])
    elapsed_ms = int((time.perf_counter() - t_all) * 1000)
    verification = attempt["verification"] or {}
    log.info("stage=mode-c-done ms=%d verdict=%s", elapsed_ms, attempt["verdict"])
    return {
        "elapsed_ms": elapsed_ms,
        "vision_ms": vision_ms,
        "segment_ms": segment_ms,
        "claude_ms": attempt["claude_ms"],
        "erase_ms": attempt["erase_ms"],
        "render_ms": attempt["render_ms"],
        "verify_ms": attempt.get("verify_ms") or 0,
        "judge_ms": attempt.get("judge_ms") or 0,
        "word_count": len(ocr.words),
        "unit_count": attempt["unit_count"],
        "translated_count": attempt["translated_count"],
        "tokens": dict(attempt["tokens"]),
        "cost_usd": translate_cost,
        "judge_cost_usd": judge_cost,
        "total_cost_usd": _sum_known([translate_cost, judge_cost]),
        "cost_anthropic_usd": _sum_known([translate_cost, judge_cost]),  # Mode C costs Claude only
        "cost_openrouter_usd": 0.0,
        "model": attempt["model"],
        "served_model": attempt["served_model"],
        "stop_reason": attempt["stop_reason"],
        "fallback_form": attempt["fallback_form"],
        "fallback_attempts": attempt["fallback_attempts"],
        "claude_image_size": attempt["claude_image_size"],
        "claude_calls": call.calls,
        "chunk_sizes": call.chunk_sizes,
        "word_list_tokens_est": 0,
        "warnings": list(attempt["warnings"]),
        "page": [ocr.width, ocr.height],
        "units": attempt["units"],
        "segment": segment_doc,
        "coverage_pass": False,
        "planner_coverage": None,
        "output": attempt["output"],
        "erased": attempt["erased"],
        "units_file": attempt["units_file"],
        "verdict": attempt["verdict"],
        "verification": attempt["verification"],
        "verification_file": attempt["verification_file"],
        "verify_error": attempt["verify_error"],
        "usability": verification.get("usability"),
        "coverage": verification.get("coverage"),
        "selected_attempt": 1,
        "attempts": [attempt],
        "retry": False,
        "retry_error": None,
        "max_grow": MAX_GROW,
    }
