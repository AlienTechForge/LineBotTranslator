"""The vision judge: Claude looks at the original and at the output and says whether it is usable.

Rounds 3 and 4 judged from OCR text. That failed in both directions: Vision misreads tiny rendered
text ("Member Price" came back as "Manbar Price") so legible pictures were judged garbled, and
errors that only a look at the two pictures catches went unreported (a table row drawn twice, so
the handwritten marks sat next to the wrong dish; 漂髮 translated as "Perm"; a phone number changed
to 99446916). The judge is now one Claude call per attempt that SEES both images (long edge at most
1568 px, JPEG) next to the OCR lines of the source (an aid, the output OCR is no longer sent) and
answers with JSON:

* ``usability``: how usable is the output for a customer who only reads the target language;
* ``coverage``: the share of the source text that is present, translated and legible;
* ``legibility_issues``, ``structure_issues``, ``altered_numbers``, ``untranslated``,
  ``mistranslated`` and a one-sentence ``summary``.

``verify.verify_output`` turns that into the verdict (numbers, rows, garbled, partial, score). The
same judge, prompt and record serve Mode B outputs and the Mode A output, so the two modes get
comparable scores. A judge failure (no key, API error, refusal, unparsable answer) is never an
error of the attempt: the record says ``status: "unavailable"`` and the verdict is ``unverified``;
``skipped`` means there was no output image.

Same client, environment and server-side fallback ladder as Mode A (claude_units.py, which also
owns the process-wide Claude semaphore); the model is ``CLAUDE_JUDGE_MODEL`` (default
claude-sonnet-5-5). An image costs about width x height / 750 input tokens, 1.6k at the cap, so an
attempt sends roughly 3k tokens of pictures. Nothing here logs OCR text or image data.
"""

from __future__ import annotations

import io
import json
import logging
import os
import time
from typing import Literal

from PIL import Image
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator

from . import claude_units
from .config import LANGUAGES, REQUEST_TIMEOUT_S, get_settings
from .imaging import to_jpeg_base64
from .models import ModeError, OcrResult
from .redaction import describe_error

log = logging.getLogger("lab.judge")

MAX_TOKENS = 8000
EFFORT = "medium"
TIMEOUT_S = min(REQUEST_TIMEOUT_S, 180.0)  # a judge that hangs must not stall the attempt for 10 minutes
MAX_IMAGE_SIDE = 1568  # long edge of both pictures sent to Claude
JPEG_QUALITY = 90
MAX_LINES = 600  # OCR lines of the source; a page with more lines than this is cut (and the record says so)
MAX_LINE_CHARS = 400

STATUS_OK = "ok"
STATUS_UNAVAILABLE = "unavailable"
STATUS_SKIPPED = "skipped"  # nothing to judge (there is no output image)

STRUCTURE_TYPES = ["duplicated_row", "missing_row", "shifted", "misaligned_marks", "other"]


def _object(properties: dict) -> dict:
    return {
        "type": "object",
        "additionalProperties": False,
        "required": list(properties),
        "properties": properties,
    }


_STRING = {"type": "string"}
_BOOL = {"type": "boolean"}

JUDGE_SCHEMA: dict = _object(
    {
        "usability": {"type": "number"},
        "coverage": {"type": "number"},
        "legibility_issues": {
            "type": "array",
            "items": _object({"where": _STRING, "output_text": _STRING, "problem": _STRING, "major": _BOOL}),
        },
        "structure_issues": {
            "type": "array",
            "items": _object({"type": {"type": "string", "enum": STRUCTURE_TYPES}, "detail": _STRING}),
        },
        "altered_numbers": {
            "type": "array",
            "items": _object({"source": _STRING, "output": _STRING, "where": _STRING}),
        },
        "untranslated": {
            "type": "array",
            "items": _object({"text": _STRING, "where": _STRING, "major": _BOOL}),
        },
        "mistranslated": {
            "type": "array",
            "items": _object({"output_text": _STRING, "source_text": _STRING, "problem": _STRING}),
        },
        "summary": _STRING,
    }
)

# The one place the judge prompt lives (README.md shows it; a test keeps the two in sync).
SYSTEM_PROMPT = """\
You are the quality judge of an image-translation experiment. An image model, or a program, was \
told to redraw an image so that every piece of text in it is translated into the target language \
and everything else stays exactly the same. You see both pictures: Image 1 is the ORIGINAL and \
Image 2 is the OUTPUT that you judge. You also get the text that OCR read from the original, an \
aid only: it can contain reading errors, especially for handwriting and small print.

Judge what is actually drawn in Image 2, not what you would expect to be there: read its text the \
way a customer would, from the pixels. Be strict about garbled text and changed numbers. Compare \
tables and lists row by row. Never report something you cannot point to in Image 2.

Work in this order and put the results into the JSON fields:

1. Compare the two images region by region. For a table or a list compare item by item: every \
row of Image 1 must be in Image 2 exactly once, in the same order, with the handwritten marks, \
prices and quantities next to the same item.

2. usability: a number from 0 to 1. If a customer who only reads the target language got Image 2 \
instead of Image 1, how usable is it? 1 means everything they need is readable, correct and in \
the right place; 0.5 means about half of it is; below 0.4 means it is not usable.

3. coverage: a number from 0 to 1, the share of the source text that is present in Image 2, \
translated into the target language and legible. Weigh text by its size and importance: a \
missing title or a column of body text counts for more than a footnote.

4. legibility_issues: text in Image 2 that is not real target-language text or cannot be read: \
invented or scrambled words, random characters, pseudo-text, blurred, cut-off or overlapping \
text. For each give where (the region in words, for example "price pill of the second row" or \
"top-right card"), output_text (what you can read there, copied as it appears, or an empty \
string when nothing is readable), problem (a few words) and major: true when a customer would \
miss something they need (a dish name, a price label, a heading), false for decoration, \
footnotes and logo lettering. Do not list brand names, names of people or places, URLs, e-mail \
addresses or numbers here.

5. structure_issues: type duplicated_row (a row or item appears twice), missing_row (one is \
missing), shifted (items moved so that the layout no longer matches), misaligned_marks \
(handwritten marks, prices or quantities now sit next to a different item than in Image 1) or \
other; detail names the item in a few words.

6. altered_numbers: prices, dates, times, phone numbers, quantities and years that differ between \
Image 1 and Image 2: source and output as written, and where. Read them from the images; the OCR \
text is only an aid. A difference of formatting alone (12:30 and 12.30, 1,200 and 1200) is not an \
alteration.

7. untranslated: text in Image 2 that is still in the source language (or in another language \
that is not the target language) although it should have been translated: text, where and major. \
Brand names, URLs, e-mail addresses and text that was already in the target language are not \
issues. A title or a column of body text is major, a footnote is not.

8. mistranslated: text in Image 2 that is real target-language text but whose meaning differs \
from the source: output_text, source_text and problem (a few words).

9. summary: one sentence in English on the overall result.

Use empty arrays when there is nothing to report. Return only the JSON object that matches the \
schema."""

ORIGINAL_LABEL = "Image 1 is the ORIGINAL image."
OUTPUT_LABEL = "Image 2 is the model's OUTPUT image."


def language_name(code: str) -> str:
    return LANGUAGES.get(code, code)


# ---------------------------------------------------------------- the answer


def _clamp01(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError("must be a number")
    return min(1.0, max(0.0, float(value)))


class LegibilityIssue(BaseModel):
    model_config = ConfigDict(extra="ignore")

    where: str = ""
    output_text: str = ""
    problem: str = ""
    major: bool


class StructureIssue(BaseModel):
    model_config = ConfigDict(extra="ignore")

    type: Literal["duplicated_row", "missing_row", "shifted", "misaligned_marks", "other"]
    detail: str = ""


class AlteredNumber(BaseModel):
    model_config = ConfigDict(extra="ignore")

    source: str = ""
    output: str = ""
    where: str = ""


class Untranslated(BaseModel):
    model_config = ConfigDict(extra="ignore")

    text: str = ""
    where: str = ""
    major: bool


class Mistranslated(BaseModel):
    model_config = ConfigDict(extra="ignore")

    output_text: str = ""
    source_text: str = ""
    problem: str = ""


class JudgeAnswer(BaseModel):
    """The validated JSON answer. ``usability`` and ``coverage`` are clamped into 0..1 rather than rejected."""

    model_config = ConfigDict(extra="ignore")

    usability: float
    coverage: float
    legibility_issues: list[LegibilityIssue] = Field(default_factory=list)
    structure_issues: list[StructureIssue] = Field(default_factory=list)
    altered_numbers: list[AlteredNumber] = Field(default_factory=list)
    untranslated: list[Untranslated] = Field(default_factory=list)
    mistranslated: list[Mistranslated] = Field(default_factory=list)
    summary: str = ""

    @field_validator("usability", "coverage", mode="before")
    @classmethod
    def _clamp(cls, value):
        return _clamp01(value)


def parse_answer(text: str) -> JudgeAnswer:
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ModeError(f"the judge returned text that is not valid JSON: {exc}") from exc
    try:
        return JudgeAnswer.model_validate(data)
    except ValidationError as exc:
        raise ModeError(f"the judge's JSON does not match the schema: {exc}") from exc


# ---------------------------------------------------------------- the input


def ocr_lines(ocr: OcrResult | None) -> list[str]:
    """The OCR text as non-empty, whitespace-normalised lines in reading order.

    Vision's full text keeps its line breaks, which is the order and the segmentation the judge
    needs. Without a full text (a stand-in OCR) the words are grouped by paragraph instead.
    """
    if ocr is None:
        return []
    lines = [" ".join(line.split()) for line in (ocr.text or "").splitlines()]
    lines = [line for line in lines if line]
    if lines:
        return lines
    grouped: list[list[str]] = []
    last: object = object()
    for word in ocr.words:
        if not word.text.strip():
            continue
        if not grouped or word.para != last:
            grouped.append([])
            last = word.para
        grouped[-1].append(" ".join(word.text.split()))
    return [" ".join(parts) for parts in grouped]


def _cap(lines: list[str]) -> tuple[list[str], bool]:
    cut = len(lines) > MAX_LINES or any(len(line) > MAX_LINE_CHARS for line in lines)
    return [line[:MAX_LINE_CHARS] for line in lines[:MAX_LINES]], cut


def build_user_text(target_lang: str, source_lines: list[str]) -> str:
    """The text part of the user message: the target language and the OCR lines of the original."""
    if source_lines:
        numbered = "\n".join(f"S{i}: {line}" for i, line in enumerate(source_lines, start=1))
    else:
        numbered = "(OCR found no text in Image 1)"
    return (
        f"Target language: {language_name(target_lang)} ({target_lang})\n"
        "\n"
        f"OCR lines of Image 1, the original ({len(source_lines)}), an aid that can contain reading errors:\n"
        f"{numbered}"
    )


def _image_block(b64: str) -> dict:
    return {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": b64}}


def build_content(source_image: Image.Image, output_image: Image.Image, user_text: str) -> tuple[list[dict], dict]:
    """The content blocks of the user message and the sizes of the two pictures as sent.

    Label, picture 1, label, picture 2, then the text: each picture is downscaled to a long edge of
    at most 1568 px and sent as a JPEG, as the planner of Mode A does.
    """
    b64_source, sent_source = to_jpeg_base64(source_image, MAX_IMAGE_SIDE, quality=JPEG_QUALITY)
    b64_output, sent_output = to_jpeg_base64(output_image, MAX_IMAGE_SIDE, quality=JPEG_QUALITY)
    content = [
        {"type": "text", "text": ORIGINAL_LABEL},
        _image_block(b64_source),
        {"type": "text", "text": OUTPUT_LABEL},
        _image_block(b64_output),
        {"type": "text", "text": user_text},
    ]
    return content, {"source": list(sent_source), "output": list(sent_output)}


def open_image(data: bytes) -> Image.Image:
    image = Image.open(io.BytesIO(data))
    image.load()
    return image


# ---------------------------------------------------------------- the call


def _text_of(response) -> str:
    stop_reason = getattr(response, "stop_reason", None)
    if stop_reason == "refusal":
        details = getattr(response, "stop_details", None)
        raise ModeError(f"the judge refused the request (category={getattr(details, 'category', None)})")
    if stop_reason == "max_tokens":
        raise ModeError(f"the judge hit max_tokens={MAX_TOKENS} before finishing the JSON")
    text = next((b.text for b in response.content if getattr(b, "type", None) == "text"), None)
    if text is None:
        raise ModeError(f"the judge returned no text block (stop_reason={stop_reason})")
    return text


def _call_claude(model: str, content: list[dict]):
    """(answer text, input tokens, output tokens, served model, stop reason, form, rejected forms)."""
    import anthropic

    if not (os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN")):
        raise ModeError("ANTHROPIC_API_KEY is not set")
    kwargs = {
        "model": model,
        "max_tokens": MAX_TOKENS,
        "system": SYSTEM_PROMPT,
        "messages": [{"role": "user", "content": content}],
        "output_config": {
            "format": {"type": "json_schema", "schema": JUDGE_SCHEMA},
            "effort": EFFORT,
        },
    }  # no `thinking`: effort alone sets how hard the judge works
    client = anthropic.Anthropic(timeout=TIMEOUT_S, max_retries=2)
    response, form, rejected = claude_units.create_message(client, kwargs)
    text = _text_of(response)
    usage = getattr(response, "usage", None)
    return (
        text,
        int(getattr(usage, "input_tokens", 0) or 0),
        int(getattr(usage, "output_tokens", 0) or 0),
        getattr(response, "model", None) or model,
        getattr(response, "stop_reason", None),
        form,
        rejected,
    )


def judge_output(
    source_ocr: OcrResult | None, source_image: Image.Image, output_png: bytes | None, target_lang: str
) -> dict:
    """Judge one attempt. Returns the record that goes into ``verification["judge"]``; never raises.

    `source_image` is the original, `output_png` the bytes of the picture to judge (None: nothing
    to judge), `source_ocr` the cached OCR of the original (None when Vision failed: the judge then
    works from the pictures alone). ``status`` is ``ok`` (the answer fields are present),
    ``unavailable`` (the call failed, ``error`` says why) or ``skipped`` (no output image). Tokens
    and cost use claude_units' price table.
    """
    settings = get_settings()
    model = settings.claude_judge_model
    t0 = time.perf_counter()
    record: dict = {"status": STATUS_UNAVAILABLE, "model": model, "error": None}
    try:
        if not output_png:
            return {**record, "status": STATUS_SKIPPED, "note": "there is no output image, nothing to judge"}
        source_lines, cut = _cap(ocr_lines(source_ocr))
        sent = None
        if settings.mock:
            from . import mock

            text, in_tok, out_tok = mock.judge_response(source_lines)
            served, stop_reason, form, rejected = model, "end_turn", "mock", []
        else:
            content, sent = build_content(source_image, open_image(output_png), build_user_text(target_lang, source_lines))
            text, in_tok, out_tok, served, stop_reason, form, rejected = _call_claude(model, content)
        answer = parse_answer(text)
        cost_model = served if claude_units.price_for(served) else model
        elapsed = int((time.perf_counter() - t0) * 1000)
        log.info(  # counts only: OCR text, the judge's quotes and image data never reach the log
            "stage=judge ms=%d model=%s in_tokens=%d out_tokens=%d usability=%.2f coverage=%.2f issues=%d",
            elapsed, served, in_tok, out_tok, answer.usability, answer.coverage,
            len(answer.legibility_issues) + len(answer.structure_issues) + len(answer.altered_numbers)
            + len(answer.untranslated),
        )  # fmt: skip
        return {
            **record,
            "status": STATUS_OK,
            "served_model": served,
            "usability": round(answer.usability, 4),
            "coverage": round(answer.coverage, 4),
            "legibility_issues": [i.model_dump() for i in answer.legibility_issues],
            "structure_issues": [i.model_dump() for i in answer.structure_issues],
            "altered_numbers": [i.model_dump() for i in answer.altered_numbers],
            "untranslated": [i.model_dump() for i in answer.untranslated],
            "mistranslated": [i.model_dump() for i in answer.mistranslated],
            "summary": answer.summary,
            "input_tokens": in_tok,
            "output_tokens": out_tok,
            "cost_usd": claude_units.estimate_cost(cost_model, in_tok, out_tok),
            "elapsed_ms": elapsed,
            "stop_reason": stop_reason,
            "fallback_form": form,
            "fallback_attempts": rejected,
            "source_lines": len(source_lines),
            "truncated": cut,
            "images_sent": sent,
        }
    except Exception as exc:  # noqa: BLE001 - a judge failure must never fail the attempt
        log.warning("stage=judge failed: %s", type(exc).__name__)
        return {**record, "error": describe_error(exc), "elapsed_ms": int((time.perf_counter() - t0) * 1000)}
