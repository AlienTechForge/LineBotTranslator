"""Semantic judge for Mode B: is the text of the redrawn image real, complete translation?

Round 3 showed what the OCR checks of verify.py cannot see: an image model that renders a whole
column of invented words ("Banero Salaino Az-infe Rilchkan") or a paragraph of random Han
characters produces text that IS in the target script, so no digit or leftover check objects.
The judge is one Claude call per attempt that reads the OCR text of the SOURCE and of the OUTPUT
(text only, never the image) and answers with JSON:

* its own faithful translation of the source (``reference_translation``), the yardstick;
* ``coverage``: the share of the source content that appears correctly translated in the output;
* the offending segments: garbled (not real words), untranslated, mistranslated, altered numbers.

``verify.verify_output`` turns that into the `garbled` verdict (garbled segments present, or
coverage < 0.90). A judge failure (no key, API error, refusal, unparsable answer) is never an
error of the attempt: the record just says ``status: "unavailable"`` and the verdict is then
decided by the OCR checks alone.

Same client, environment and server-side fallback ladder as Mode A (claude_units.py); the model
is ``CLAUDE_JUDGE_MODEL`` (default claude-sonnet-5-5). Nothing here logs OCR text.
"""

from __future__ import annotations

import json
import logging
import os
import time

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator

from . import claude_units
from .config import LANGUAGES, REQUEST_TIMEOUT_S, get_settings
from .models import ModeError, OcrResult
from .redaction import describe_error

log = logging.getLogger("lab.judge")

MAX_TOKENS = 8000
EFFORT = "medium"
TIMEOUT_S = min(REQUEST_TIMEOUT_S, 180.0)  # a judge that hangs must not stall the attempt for 10 minutes
MAX_LINES = 600  # per side; a page with more lines than this is cut (and the record says so)
MAX_LINE_CHARS = 400

STATUS_OK = "ok"
STATUS_UNAVAILABLE = "unavailable"
STATUS_SKIPPED = "skipped"  # nothing to judge (the source image has no text)

SEGMENT = {
    "type": "object",
    "additionalProperties": False,
    "required": ["output_text", "source_text"],
    "properties": {"output_text": {"type": "string"}, "source_text": {"type": "string"}},
}

JUDGE_SCHEMA: dict = {
    "type": "object",
    "additionalProperties": False,
    "required": [
        "reference_translation",
        "coverage",
        "garbled_segments",
        "untranslated_segments",
        "mistranslated_segments",
        "altered_numbers",
        "summary",
    ],
    "properties": {
        "reference_translation": {"type": "array", "items": {"type": "string"}},
        "coverage": {"type": "number"},
        "garbled_segments": {"type": "array", "items": SEGMENT},
        "untranslated_segments": {"type": "array", "items": {"type": "string"}},
        "mistranslated_segments": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["output_text", "source_text", "problem"],
                "properties": {
                    "output_text": {"type": "string"},
                    "source_text": {"type": "string"},
                    "problem": {"type": "string"},
                },
            },
        },
        "altered_numbers": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["source", "output"],
                "properties": {"source": {"type": "string"}, "output": {"type": "string"}},
            },
        },
        "summary": {"type": "string"},
    },
}

# The one place the judge prompt lives (README.md describes it).
SYSTEM_PROMPT = """\
You are the quality judge of an image-translation experiment. An image model was told to redraw \
an image so that every piece of text in it is translated into the target language and \
everything else stays exactly the same. You do not see any image. You get the OCR text of the \
ORIGINAL image (the SOURCE) and the OCR text read back from the model's OUTPUT image, both one \
line per row in reading order. Decide whether the OUTPUT text is a correct, real and complete \
translation of the SOURCE text.

Work in this order and put the results into the JSON fields:

1. reference_translation: translate every SOURCE line faithfully and naturally into the target \
language, one string per source line, in the same order (an empty string for a line with \
nothing to translate). Keep brand names, URLs, e-mail addresses and numbers as written. This is \
your own yardstick: write it from the source alone, before you look at the output.

2. Compare the OUTPUT with your reference translation. The output lines can be split, merged or \
ordered differently from the source lines because the image was redrawn, so match by meaning, \
not by line number. The OCR of either side can contain small reading errors (handwriting, tiny \
print, decoration read as characters): never count a single misread character, stray \
punctuation or an isolated fragment of one or two characters against the output.

3. coverage: a number from 0 to 1, the share of the SOURCE content that appears in the OUTPUT \
correctly translated into the target language. Weigh content by how much text it is and how \
much it matters: a missing title or a column of body text counts for more than a footnote. 1 \
means everything is there and correct; 0.5 means about half is missing, untranslated, garbled \
or wrong.

4. garbled_segments: parts of the OUTPUT that are not real, meaningful text in the target \
language: invented or scrambled words, random characters of the target script, repeated \
pseudo-words, text that looks like the target language but means nothing. For each give \
output_text (copied from the output, without its line label) and source_text (the source line it \
stands for, copied from the source, or an empty string when you cannot tell). Do not list brand \
names, names of people or places, URLs, e-mail addresses, numbers, or real text that is merely \
poorly translated (that goes to mistranslated_segments).

5. untranslated_segments: text in the OUTPUT that is still in the source language (or in any \
language other than the target language) although it should have been translated, copied from \
the output. Exceptions: brand names, product names kept in their original form, URLs, e-mail \
addresses, and text that was already in the target language in the source.

6. mistranslated_segments: OUTPUT lines that are real target-language text but whose meaning \
differs from the source: output_text, source_text and problem (a few words).

7. altered_numbers: numbers (prices, dates, times, phone numbers, quantities, years) that appear \
in both texts with different digits: source and output, as written. Do not list a number only \
because it is formatted differently (12:30 and 12.30, 1,200 and 1200).

8. summary: one sentence in English on the overall result.

Be strict about garbled text, it is the failure that matters most, but never report a segment \
you cannot point to in the OUTPUT. Use empty arrays when there is nothing to report. Return only \
the JSON object that matches the schema."""


def language_name(code: str) -> str:
    return LANGUAGES.get(code, code)


# ---------------------------------------------------------------- the answer


class GarbledSegment(BaseModel):
    model_config = ConfigDict(extra="ignore")

    output_text: str
    source_text: str = ""


class MistranslatedSegment(BaseModel):
    model_config = ConfigDict(extra="ignore")

    output_text: str
    source_text: str = ""
    problem: str = ""


class AlteredNumber(BaseModel):
    model_config = ConfigDict(extra="ignore")

    source: str
    output: str


class JudgeAnswer(BaseModel):
    """The validated JSON answer. ``coverage`` is clamped into 0..1 rather than rejected."""

    model_config = ConfigDict(extra="ignore")

    reference_translation: list[str] = Field(default_factory=list)
    coverage: float
    garbled_segments: list[GarbledSegment] = Field(default_factory=list)
    untranslated_segments: list[str] = Field(default_factory=list)
    mistranslated_segments: list[MistranslatedSegment] = Field(default_factory=list)
    altered_numbers: list[AlteredNumber] = Field(default_factory=list)
    summary: str = ""

    @field_validator("coverage", mode="before")
    @classmethod
    def _clamp(cls, value):
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError("coverage must be a number")
        return min(1.0, max(0.0, float(value)))


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


def ocr_lines(ocr: OcrResult) -> list[str]:
    """The OCR text as non-empty, whitespace-normalised lines in reading order.

    Vision's full text keeps its line breaks, which is the order and the segmentation the judge
    needs. Without a full text (a stand-in OCR) the words are grouped by paragraph instead.
    """
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


def _numbered(prefix: str, lines: list[str]) -> str:
    return "\n".join(f"{prefix}{i}: {line}" for i, line in enumerate(lines, start=1))


def build_user_text(target_lang: str, source_lines: list[str], output_lines: list[str]) -> str:
    output_block = _numbered("O", output_lines) if output_lines else "(no text was recognised in the output image)"
    return (
        f"Target language: {language_name(target_lang)} ({target_lang})\n"
        "\n"
        f"SOURCE lines ({len(source_lines)}), the original image:\n"
        f"{_numbered('S', source_lines)}\n"
        "\n"
        f"OUTPUT lines ({len(output_lines)}), the model's redrawn image:\n"
        f"{output_block}"
    )


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


def _call_claude(model: str, user_text: str):
    """(answer text, input tokens, output tokens, served model, stop reason, form, rejected forms)."""
    import anthropic

    if not (os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN")):
        raise ModeError("ANTHROPIC_API_KEY is not set")
    kwargs = {
        "model": model,
        "max_tokens": MAX_TOKENS,
        "system": SYSTEM_PROMPT,
        "messages": [{"role": "user", "content": [{"type": "text", "text": user_text}]}],
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


def judge_output(source: OcrResult, output: OcrResult, target_lang: str) -> dict:
    """Judge one attempt. Returns the record that goes into ``verification["judge"]``; never raises.

    ``status`` is ``ok`` (the answer fields are present), ``unavailable`` (the call failed, ``error``
    says why) or ``skipped`` (the source has no text). Tokens and cost use claude_units' price table.
    """
    settings = get_settings()
    model = settings.claude_judge_model
    t0 = time.perf_counter()
    record: dict = {"status": STATUS_UNAVAILABLE, "model": model, "error": None}
    try:
        source_lines, source_cut = _cap(ocr_lines(source))
        output_lines, output_cut = _cap(ocr_lines(output))
        if not source_lines:
            return {**record, "status": STATUS_SKIPPED, "note": "the source OCR found no text, nothing to judge"}
        if settings.mock:
            from . import mock

            text, in_tok, out_tok = mock.judge_response(source_lines)
            served, stop_reason, form, rejected = model, "end_turn", "mock", []
        else:
            user_text = build_user_text(target_lang, source_lines, output_lines)
            text, in_tok, out_tok, served, stop_reason, form, rejected = _call_claude(model, user_text)
        answer = parse_answer(text)
        cost_model = served if claude_units.price_for(served) else model
        elapsed = int((time.perf_counter() - t0) * 1000)
        log.info(  # counts only: the OCR text and the judge's quotes never reach the log
            "stage=judge ms=%d model=%s in_tokens=%d out_tokens=%d coverage=%.2f garbled=%d",
            elapsed, served, in_tok, out_tok, answer.coverage, len(answer.garbled_segments),
        )  # fmt: skip
        return {
            **record,
            "status": STATUS_OK,
            "served_model": served,
            "coverage": round(answer.coverage, 4),
            "reference_translation": answer.reference_translation,
            "garbled_segments": [s.model_dump() for s in answer.garbled_segments],
            "untranslated_segments": answer.untranslated_segments,
            "mistranslated_segments": [s.model_dump() for s in answer.mistranslated_segments],
            "altered_numbers": [n.model_dump() for n in answer.altered_numbers],
            "summary": answer.summary,
            "input_tokens": in_tok,
            "output_tokens": out_tok,
            "cost_usd": claude_units.estimate_cost(cost_model, in_tok, out_tok),
            "elapsed_ms": elapsed,
            "stop_reason": stop_reason,
            "fallback_form": form,
            "fallback_attempts": rejected,
            "source_lines": len(source_lines),
            "output_lines": len(output_lines),
            "truncated": source_cut or output_cut,
        }
    except Exception as exc:  # noqa: BLE001 - a judge failure must never fail the attempt
        log.warning("stage=judge failed: %s", type(exc).__name__)
        return {**record, "error": describe_error(exc), "elapsed_ms": int((time.perf_counter() - t0) * 1000)}
