"""Mode C, step 3: translation only. Claude gets the text of the layout units, not the geometry.

Mode A asks the model to group words, translate, give `short`, align, weigh and group, and writes 18k tokens
of JSON for a menu. In Mode C the layout comes from `segment.py`; this module sends the units that are not
`keep` as a numbered list (`[id] text`, one per line, ids are the unit numbers of the list: 1 is `u01`) with
the downscaled picture as context, and gets back `{items: [{id, translation, keep, short}]}`.

* the call goes through `claude_units.create_message` (same server-side fallback ladder, same process-wide
  `CLAUDE_SLOTS`, same price table and cost estimate), with the model `CLAUDE_TRANSLATE_MODEL` (default: the
  value of `CLAUDE_MODEL`), `max_tokens` 8000 and effort `medium`;
* a list longer than `MODE_C_CHUNK_UNITS` (50) is cut into chunks of at most that size that run in parallel
  threads (under the semaphore); each chunk carries the picture and a line saying it is part of a longer list.
  Results are merged by id; a unit nobody answered for stays `keep` with a warning;
* `short` is kept only when the translation is really longer than SHORT_RATIO x the source (a CJK character
  counts as two Latin characters) and the short wording is shorter than the translation;
* with `MODE_C_IMAGE_CONTEXT` off, no picture is sent.

Nothing about the picture or the text is logged.
"""

from __future__ import annotations

import json
import logging
import os
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field

from . import claude_units
from .config import MAX_CONCURRENT_CLAUDE, REQUEST_TIMEOUT_S, get_settings
from .imaging import LoadedImage, to_jpeg_base64
from .models import ClaudeUnit, ModeError
from .redaction import describe_error
from .segment import has_cjk

log = logging.getLogger("lab.claude_text")

SHORT_RATIO = 1.4  # `short` only when the translation is longer than this x the source (CJK character = 2 Latin ones)
TEXT_IMAGE_SIDE = 1024  # long side of the picture sent as context (JPEG)
TEXT_IMAGE_QUALITY = 85
MAX_TOKENS = 8000
EFFORT = "medium"

ITEMS_SCHEMA: dict = {
    "type": "object",
    "additionalProperties": False,
    "required": ["items"],
    "properties": {
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["id", "translation", "keep", "short"],
                "properties": {
                    "id": {"type": "integer"},
                    "translation": {"type": "string"},
                    "keep": {"type": "boolean"},
                    "short": {"type": "string"},
                },
            },
        }
    },
}

# The one place the Mode C prompt lives (README.md shows it; a test keeps the two in sync).
SYSTEM_PROMPT = f"""\
You translate text that was cut out of one photo or scan: a menu, poster, sign, receipt or document. \
You get the picture and a numbered list. Each line is "[id] text": the text of one piece of the picture, \
in reading order. Our code has already found where every piece sits; it erases the original text and \
draws your translation in the same place.

Translate every item into the target language and return one entry per item with the same id. Never \
skip, merge or reorder items.
- The list is one menu or document. Keep terms consistent across the whole list: the same dish, \
ingredient, size or label always gets the same translation. Use the picture to understand what a word \
means; the text can contain OCR mistakes.
- Proper names, brand names, shop names and product codes stay as they are: set keep to true and copy \
the text unchanged into translation.
- Keep numbers, units and currency symbols exactly as written.
- Keep each translation about as short as its original. No explanations, notes, quotes or alternatives \
inside translation.
- short: leave it empty unless the translation is longer than {SHORT_RATIO:g} times the original (count \
a Chinese, Japanese or Korean character as 2 Latin letters). Then give a shorter wording of the same \
item.

Return only the JSON object that matches the schema."""


def weighted_length(text: str) -> int:
    """Characters of `text` with a CJK character counted as two Latin ones (the width it takes on the page)."""
    return sum(2 if has_cjk(ch) else 1 for ch in text.strip())


def clean_short(source: str, translation: str, short: str) -> str:
    """`short` when it is warranted: the translation is longer than SHORT_RATIO x the source and `short` is shorter."""
    short = " ".join(short.split())
    if not short:
        return ""
    long_enough = weighted_length(translation) > SHORT_RATIO * weighted_length(source)
    return short if long_enough and weighted_length(short) < weighted_length(translation) else ""


def build_user_text(lang: str, source_lang: str | None, lines: list[str], part: tuple[int, int] | None = None) -> str:
    text = f"Target language: {claude_units.language_name(lang)} ({lang})\n"
    if source_lang:
        text += f"Source language: {claude_units.language_name(source_lang)} ({source_lang})\n"
    if part is not None and part[1] > 1:
        text += (
            f"This is part {part[0]} of {part[1]} of one longer list from the same picture; "
            "the ids run across the whole list. Translate only the items below.\n"
        )
    return text + "Items:\n" + "\n".join(lines)


@dataclass
class TextCall:
    units: list[ClaudeUnit]  # the units with their translations (a copy; the ones that were kept are unchanged)
    input_tokens: int
    output_tokens: int
    cost_usd: float | None
    model: str  # requested
    served_model: str
    stop_reason: str | None
    fallback_form: str  # default | array | none | mock
    elapsed_ms: int
    calls: int = 0  # how many Claude calls (chunks) were made
    chunk_sizes: list[int] = field(default_factory=list)
    sent_image_size: tuple[int, int] | None = None
    translated_ids: list[int] = field(default_factory=list)
    missing_ids: list[int] = field(default_factory=list)  # asked for, never answered: those units stay as they are
    notes: list[str] = field(default_factory=list)
    attempts: list[str] = field(default_factory=list)


def item_line(index: int, unit: ClaudeUnit) -> str:
    """`[id] text`, on one line (the line breaks of a multi-line unit were already joined by the segmentation)."""
    return f"[{index}] " + " ".join(unit.source_text.split())


def _parse_items(text: str) -> list[dict]:
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ModeError(f"Claude returned text that is not valid JSON: {exc}") from exc
    items = data.get("items") if isinstance(data, dict) else None
    if not isinstance(items, list):
        raise ModeError("Claude's JSON has no `items` list")
    good = []
    for item in items:
        if (
            isinstance(item, dict)
            and isinstance(item.get("id"), int)
            and not isinstance(item.get("id"), bool)
            and isinstance(item.get("translation"), str)
        ):
            good.append(
                {
                    "id": item["id"],
                    "translation": item["translation"],
                    "keep": bool(item.get("keep")),
                    "short": item.get("short") if isinstance(item.get("short"), str) else "",
                }
            )
    return good


def _mock_items(lang: str, ids: list[int], units: list[ClaudeUnit]) -> list[dict]:
    """The canned answer of LAB_MOCK=1: the text wrapped in the target language code (the keep rules already ran)."""
    items = []
    for i in ids:
        source = " ".join(units[i - 1].source_text.split())
        translation = f"[{lang}] {source}"
        items.append({"id": i, "translation": translation, "keep": False, "short": source})
    return items


def _call_chunk(client, settings, image_block, lang, source_lang, lines, part) -> tuple[list[dict], dict]:
    """One Claude call for one chunk of the list: (parsed items, usage / meta)."""
    t0 = time.perf_counter()
    content = []
    if image_block is not None:
        content.append(image_block)
    content.append({"type": "text", "text": build_user_text(lang, source_lang, lines, part)})
    kwargs = {
        "model": settings.translate_model,
        "max_tokens": MAX_TOKENS,
        "system": SYSTEM_PROMPT,
        "messages": [{"role": "user", "content": content}],
        "output_config": {"format": {"type": "json_schema", "schema": ITEMS_SCHEMA}, "effort": EFFORT},
    }
    response, used_form, attempts = claude_units.create_message(client, kwargs)
    stop_reason = getattr(response, "stop_reason", None)
    if stop_reason == "refusal":
        details = getattr(response, "stop_details", None)
        raise ModeError(f"Claude refused the request (category={getattr(details, 'category', None)})")
    if stop_reason == "max_tokens":
        raise ModeError(f"Claude hit max_tokens={MAX_TOKENS} before finishing the JSON; the chunk is too long")
    text = next((b.text for b in response.content if getattr(b, "type", None) == "text"), None)
    if text is None:
        raise ModeError(f"Claude returned no text block (stop_reason={stop_reason})")
    items = _parse_items(text)
    served = getattr(response, "model", None) or settings.translate_model
    return items, {
        "input": int(response.usage.input_tokens), "output": int(response.usage.output_tokens), "served": served,
        "stop": stop_reason, "form": used_form, "attempts": attempts, "ms": int((time.perf_counter() - t0) * 1000),
    }  # fmt: skip


def chunks_of(ids: list[int], size: int) -> list[list[int]]:
    size = max(1, int(size))
    return [ids[i : i + size] for i in range(0, len(ids), size)]


def translate_units(
    units: list[ClaudeUnit], image: LoadedImage, lang: str, source_lang: str | None = None
) -> TextCall:
    """Translate the units that are not `keep`; returns them (copies) with translation / keep / short filled in.

    The numbered list holds every unit that is not `keep` and has text, with the unit's position in `units`
    (1-based) as its id. Raises ModeError when no key is set or when every chunk failed; a chunk that failed
    while others succeeded, and any id the model skipped, leave their units `keep` with a note.
    """
    settings = get_settings()
    t0 = time.perf_counter()
    ids = [i for i, u in enumerate(units, start=1) if not u.keep and u.source_text.strip()]
    model = settings.translate_model
    out = [u.model_copy() for u in units]
    call = TextCall(out, 0, 0, None, model, model, None, "mock" if settings.mock else "none", 0)
    if not ids:
        call.cost_usd = 0.0
        call.notes.append("there was nothing to translate: every unit is a price, number, code or other text that is kept")
        return call

    chunks = chunks_of(ids, settings.mode_c_chunk_units)
    call.calls, call.chunk_sizes = len(chunks), [len(c) for c in chunks]
    answers: dict[int, dict] = {}
    failures: list[str] = []
    if settings.mock:
        prompt_chars = len(SYSTEM_PROMPT) + sum(len(item_line(i, units[i - 1])) for i in ids)
        for chunk in chunks:
            for item in _mock_items(lang, chunk, units):
                answers[item["id"]] = item
        call.input_tokens = prompt_chars // 3 + 1500  # a picture and a prompt
        call.output_tokens = sum(len(a["translation"]) + len(a["short"]) + 24 for a in answers.values()) // 3
        call.cost_usd = claude_units.estimate_cost(model, call.input_tokens, call.output_tokens)
    else:
        if not (os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN")):
            raise ModeError("ANTHROPIC_API_KEY is not set")
        import anthropic

        client = anthropic.Anthropic(timeout=REQUEST_TIMEOUT_S, max_retries=2)
        image_block = None
        if settings.mode_c_image_context:
            b64, sent = to_jpeg_base64(image.pil, TEXT_IMAGE_SIDE, quality=TEXT_IMAGE_QUALITY)
            call.sent_image_size = sent
            image_block = {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": b64}}

        def run(n: int, chunk: list[int]):
            lines = [item_line(i, units[i - 1]) for i in chunk]
            return _call_chunk(client, settings, image_block, lang, source_lang, lines, (n, len(chunks)))

        with ThreadPoolExecutor(max_workers=max(1, min(len(chunks), MAX_CONCURRENT_CLAUDE)), thread_name_prefix="text") as pool:
            futures = [pool.submit(run, n, chunk) for n, chunk in enumerate(chunks, start=1)]
            metas = []
            for n, (chunk, future) in enumerate(zip(chunks, futures), start=1):
                try:
                    items, meta = future.result()
                except Exception as exc:  # noqa: BLE001 - this chunk only; the others still count
                    failures.append(f"part {n} of {len(chunks)} failed ({describe_error(exc)})")
                    log.warning("stage=claude-text chunk %d of %d failed: %s", n, len(chunks), type(exc).__name__)
                    continue
                metas.append(meta)
                allowed = set(chunk)
                for item in items:
                    if item["id"] in allowed and item["id"] not in answers:
                        answers[item["id"]] = item
        if not metas:
            raise ModeError(failures[0] if failures else "Claude returned nothing")
        call.input_tokens = sum(m["input"] for m in metas)
        call.output_tokens = sum(m["output"] for m in metas)
        call.served_model = metas[0]["served"]
        call.stop_reason = metas[0]["stop"]
        call.fallback_form = metas[0]["form"]
        call.attempts = [a for m in metas for a in m["attempts"]]
        cost_model = call.served_model if claude_units.price_for(call.served_model) else model
        call.cost_usd = claude_units.estimate_cost(cost_model, call.input_tokens, call.output_tokens)
    call.elapsed_ms = int((time.perf_counter() - t0) * 1000)

    for i in ids:
        unit = units[i - 1]
        item = answers.get(i)
        if item is None:
            call.missing_ids.append(i)
            out[i - 1] = unit.model_copy(update={"keep": True, "translation": unit.source_text, "short": "", "notes": "no translation returned"})
            continue
        call.translated_ids.append(i)
        if item["keep"]:
            out[i - 1] = unit.model_copy(update={"keep": True, "translation": unit.source_text, "short": "", "notes": "kept by the translator"})
            continue
        translation = item["translation"].strip()
        out[i - 1] = unit.model_copy(
            update={"translation": translation, "short": clean_short(unit.source_text, translation, item["short"]), "keep": False}
        )
    if failures:
        call.notes.extend(failures)
    if call.missing_ids:
        names = ", ".join(f"u{i:02d}" for i in call.missing_ids[:20]) + (" ..." if len(call.missing_ids) > 20 else "")
        call.notes.append(f"{len(call.missing_ids)} unit(s) got no translation and stay as they are ({names})")
    log.info(
        "stage=claude-text ms=%d chunks=%d units=%d missing=%d in_tokens=%d out_tokens=%d model=%s",
        call.elapsed_ms, call.calls, len(ids), len(call.missing_ids), call.input_tokens, call.output_tokens, call.served_model,
    )  # fmt: skip
    return call
