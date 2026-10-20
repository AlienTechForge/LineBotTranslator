"""Mode A, step 2: ask Claude (vision) to group OCR words into translation units and translate them."""

from __future__ import annotations

import json
import logging
import os
import time
from dataclasses import dataclass, field

from pydantic import ValidationError

from .config import LANGUAGES, REQUEST_TIMEOUT_S, get_settings
from .imaging import LoadedImage, to_jpeg_base64
from .models import ClaudeUnit, ClaudeUnits, ModeError, OcrResult
from .wordlist import check_token_budget, format_word_list

log = logging.getLogger("lab.claude")

MAX_IMAGE_SIDE = 1568
MAX_TOKENS = 16000
EFFORT = "high"
FALLBACK_MODEL_FOR_ARRAY_FORM = "claude-opus-4-8"
BETA_FALLBACK_DEFAULT = "server-side-fallback-2026-07-01"
BETA_FALLBACK_ARRAY = "server-side-fallback-2026-06-01"

# USD per million tokens (input, output)
PRICES_PER_MTOK: dict[str, tuple[float, float]] = {
    "claude-opus-5-5": (4.0, 20.0),
    "claude-sonnet-5-5": (2.0, 10.0),
    "claude-haiku-5-5": (0.10, 0.50),
}

UNITS_SCHEMA: dict = {
    "type": "object",
    "additionalProperties": False,
    "required": ["units"],
    "properties": {
        "units": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": [
                    "word_ids",
                    "source_text",
                    "translation",
                    "direction",
                    "rotated",
                    "role",
                    "keep",
                    "notes",
                ],
                "properties": {
                    "word_ids": {"type": "array", "items": {"type": "string"}},
                    "source_text": {"type": "string"},
                    "translation": {"type": "string"},
                    "direction": {
                        "type": "string",
                        "enum": ["horizontal", "vertical_rl", "vertical_lr"],
                    },
                    "rotated": {"type": "boolean"},
                    "role": {
                        "type": "string",
                        "enum": ["title", "heading", "body", "label", "price", "number", "other"],
                    },
                    "keep": {"type": "boolean"},
                    "notes": {"type": "string"},
                },
            },
        }
    },
}

SYSTEM_PROMPT = """\
You prepare an image for in-place translation. You receive the image and a list of OCR words \
(id, axis-aligned box, text). Group the words into translation units and translate each unit \
into the target language. You output units, never coordinates: our code computes the geometry \
from the word boxes, erases the original glyphs and draws your translation in place.

What a unit is:
- A unit is what a human translator would translate as one piece: one line of a list, one \
bullet, one heading, one paragraph, one dish name in a vertical column, one price tag, one \
table cell, one button label.
- Never split a sentence across units. Never merge different rows of a table, different list \
items or different bullets into one unit.
- Vertical CJK columns are one unit per column, unless the columns are clearly one continuous \
paragraph.
- Use the image to decide grouping, reading order and direction. OCR text can contain errors; \
write source_text as it actually reads in the image.

Fields:
- word_ids: ids taken from the word list, in reading order. Every id may appear at most once \
across all units. Words you do not list are left untouched in the image (use that for pure \
decoration, logos and artwork lettering you would not translate).
- source_text: the unit's text in the source language.
- translation: natural, idiomatic target-language text for this role. Keep it short for labels \
and tags (the space available is the size of the source text). Keep numbers, units and currency \
symbols. Do not add explanations, quotes or notes inside the translation.
- direction: "horizontal", "vertical_rl" (columns read top to bottom, columns ordered right to \
left) or "vertical_lr" (top to bottom, columns left to right).
- rotated: true when the unit's text runs along a page that is turned (photographed sideways or \
upside down, or text set at an angle); direction then describes how it reads in the text's own \
frame.
- role: title | heading | body | label | price | number | other.
- keep: true for things that must not be translated: prices, phone numbers, URLs, e-mail \
addresses, brand names, and text that is already in the target language. For keep units set \
translation equal to source_text.
- notes: a very short remark if something is uncertain, otherwise an empty string.

Return only the JSON object that matches the schema."""


def language_name(code: str) -> str:
    return LANGUAGES.get(code, code)


def build_user_text(lang: str, width: int, height: int, word_list: str) -> str:
    return (
        f"Target language: {language_name(lang)} ({lang})\n"
        f"Original image size: {width} x {height} px (width x height)\n"
        "The attached image may be downscaled, but the word boxes below are in ORIGINAL pixel "
        "coordinates.\n"
        "Word list, one word per line: id x y w h text (x y = top-left corner of the "
        "axis-aligned box)\n"
        "---\n"
        f"{word_list}"
    )


# ---------------------------------------------------------------- cost


def price_for(model: str) -> tuple[float, float] | None:
    if model in PRICES_PER_MTOK:
        return PRICES_PER_MTOK[model]
    for key in sorted(PRICES_PER_MTOK, key=len, reverse=True):
        if model.startswith(key + "-"):
            return PRICES_PER_MTOK[key]
    return None


def estimate_cost(model: str, input_tokens: int, output_tokens: int) -> float | None:
    """USD estimate from the price table, or None for an unknown model (show tokens only)."""
    price = price_for(model)
    if price is None:
        return None
    return round((input_tokens * price[0] + output_tokens * price[1]) / 1_000_000, 6)


# ---------------------------------------------------------------- parse / validate


def parse_units(text: str) -> ClaudeUnits:
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ModeError(f"Claude returned text that is not valid JSON: {exc}") from exc
    try:
        return ClaudeUnits.model_validate(data)
    except ValidationError as exc:
        raise ModeError(f"Claude's JSON does not match the unit schema: {exc}") from exc


def validate_units(units: ClaudeUnits, valid_ids: set[str]) -> tuple[list[ClaudeUnit], list[str]]:
    """Enforce the id rules.

    * A unit that references an id that is not in the word list is rejected as a whole.
    * An id that was already used (earlier in the same unit or in an earlier unit) is dropped
      from the later occurrence; the rest of that unit survives.
    * A unit left without ids is dropped.
    Returns (accepted units, human-readable log lines).
    """
    used: set[str] = set()
    accepted: list[ClaudeUnit] = []
    notes: list[str] = []
    for index, unit in enumerate(units.units, start=1):
        unknown = [wid for wid in unit.word_ids if wid not in valid_ids]
        if unknown:
            notes.append(f"unit {index} rejected: unknown word ids {unknown[:5]}")
            continue
        kept_ids: list[str] = []
        for wid in unit.word_ids:
            if wid in used:
                notes.append(f"unit {index}: duplicate word id {wid} dropped")
                continue
            used.add(wid)
            kept_ids.append(wid)
        if not kept_ids:
            notes.append(f"unit {index} dropped: no word ids left")
            continue
        accepted.append(unit.model_copy(update={"word_ids": kept_ids}))
    for line in notes:
        log.info("stage=claude-validate %s", line)
    return accepted, notes


# ---------------------------------------------------------------- the call


@dataclass
class ClaudeCall:
    units: list[ClaudeUnit]
    input_tokens: int
    output_tokens: int
    cost_usd: float | None
    model: str  # requested
    served_model: str
    stop_reason: str | None
    fallback_form: str  # default | array | none | mock
    elapsed_ms: int
    sent_image_size: tuple[int, int] | None = None
    word_list_tokens_est: int = 0
    notes: list[str] = field(default_factory=list)
    attempts: list[str] = field(default_factory=list)


def _build_messages(image: LoadedImage, ocr: OcrResult, lang: str):
    word_list = format_word_list(ocr.words)
    tokens, warning = check_token_budget(word_list)
    b64, sent_size = to_jpeg_base64(image.pil, MAX_IMAGE_SIDE, quality=90)
    messages = [
        {
            "role": "user",
            "content": [
                {
                    "type": "image",
                    "source": {"type": "base64", "media_type": "image/jpeg", "data": b64},
                },
                {"type": "text", "text": build_user_text(lang, ocr.width, ocr.height, word_list)},
            ],
        }
    ]
    return messages, sent_size, tokens, warning


def _create(client, form: str, kwargs: dict):
    if form == "default":
        return client.beta.messages.create(
            **kwargs, betas=[BETA_FALLBACK_DEFAULT], fallbacks="default"
        )
    if form == "array":
        return client.beta.messages.create(
            **kwargs,
            betas=[BETA_FALLBACK_ARRAY],
            fallbacks=[{"model": FALLBACK_MODEL_FOR_ARRAY_FORM}],
        )
    return client.messages.create(**kwargs)


def create_message(client, kwargs: dict):
    """``messages.create`` through the server-side fallback ladder.

    Tries the ``fallbacks="default"`` form, then the array form, then a plain call; a form the SDK
    or the API rejects (TypeError / HTTP 400) steps down to the next one. Returns
    ``(response, form used, one line per rejected form)``. Shared by Mode A and the Mode B judge.
    """
    import anthropic

    attempts: list[str] = []
    for form in ("default", "array", "none"):
        try:
            return _create(client, form, kwargs), form, attempts
        except (TypeError, anthropic.BadRequestError) as exc:
            # The SDK rejected the keyword, or the API rejected the beta / parameter
            # combination: step down to the next, simpler form and remember why.
            attempts.append(f"{form}: {type(exc).__name__}: {str(exc)[:300]}")
            log.warning("stage=claude fallback form %s rejected (%s)", form, type(exc).__name__)
        except anthropic.APIStatusError as exc:
            raise ModeError(f"Claude API error {exc.status_code}: {exc.message}") from exc
        except anthropic.APIConnectionError as exc:
            raise ModeError(f"Claude API connection error: {exc}") from exc
    raise ModeError("Claude request was rejected in every form: " + " | ".join(attempts))


def request_units(image: LoadedImage, ocr: OcrResult, lang: str) -> ClaudeCall:
    """Call Claude (or the mock) and return validated units plus usage / cost."""
    settings = get_settings()
    valid_ids = {w.id for w in ocr.words}
    t0 = time.perf_counter()

    if settings.mock:
        from . import mock

        text, in_tok, out_tok = mock.claude_response(ocr)
        units, notes = validate_units(parse_units(text), valid_ids)
        return ClaudeCall(
            units=units,
            input_tokens=in_tok,
            output_tokens=out_tok,
            cost_usd=estimate_cost(settings.claude_model, in_tok, out_tok),
            model=settings.claude_model,
            served_model=settings.claude_model,
            stop_reason="end_turn",
            fallback_form="mock",
            elapsed_ms=int((time.perf_counter() - t0) * 1000),
            notes=notes,
        )

    import anthropic

    if not (os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN")):
        raise ModeError("ANTHROPIC_API_KEY is not set")

    messages, sent_size, word_tokens, warning = _build_messages(image, ocr, lang)
    kwargs = {
        "model": settings.claude_model,
        "max_tokens": MAX_TOKENS,
        "system": SYSTEM_PROMPT,
        "messages": messages,
        "output_config": {
            "format": {"type": "json_schema", "schema": UNITS_SCHEMA},
            "effort": EFFORT,
        },
    }
    client = anthropic.Anthropic(timeout=REQUEST_TIMEOUT_S, max_retries=2)
    response, used_form, attempts = create_message(client, kwargs)

    stop_reason = getattr(response, "stop_reason", None)
    if stop_reason == "refusal":
        details = getattr(response, "stop_details", None)
        category = getattr(details, "category", None)
        explanation = getattr(details, "explanation", None)
        raise ModeError(f"Claude refused the request (category={category}, {explanation})")
    if stop_reason == "max_tokens":
        raise ModeError(
            f"Claude hit max_tokens={MAX_TOKENS} before finishing the JSON; "
            "the image has too many units for one call"
        )
    text = next((b.text for b in response.content if getattr(b, "type", None) == "text"), None)
    if text is None:
        raise ModeError(f"Claude returned no text block (stop_reason={stop_reason})")

    units, notes = validate_units(parse_units(text), valid_ids)
    if warning:
        notes.insert(0, warning)
    in_tok = int(response.usage.input_tokens)
    out_tok = int(response.usage.output_tokens)
    served = getattr(response, "model", None) or settings.claude_model
    cost_model = served if price_for(served) else settings.claude_model
    elapsed = int((time.perf_counter() - t0) * 1000)
    log.info(
        "stage=claude ms=%d form=%s in_tokens=%d out_tokens=%d units=%d dropped_notes=%d model=%s",
        elapsed,
        used_form,
        in_tok,
        out_tok,
        len(units),
        len(notes),
        served,
    )
    return ClaudeCall(
        units=units,
        input_tokens=in_tok,
        output_tokens=out_tok,
        cost_usd=estimate_cost(cost_model, in_tok, out_tok),
        model=settings.claude_model,
        served_model=served,
        stop_reason=stop_reason,
        fallback_form=used_form,
        elapsed_ms=elapsed,
        sent_image_size=sent_size,
        word_list_tokens_est=word_tokens,
        notes=notes,
        attempts=attempts,
    )
