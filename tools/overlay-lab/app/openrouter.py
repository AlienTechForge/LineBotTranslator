"""Mode B (image model): ask an OpenRouter image-output model to redo the whole image.

Request shapes follow the current OpenRouter docs (fetched when this was written):

* chat route  - POST /api/v1/chat/completions with ``modalities: ["image", "text"]`` and the
  picture as an ``image_url`` content part. The result image is in
  ``choices[0].message.images[i].image_url.url`` (a data URL).
  Reference: https://openrouter.ai/docs/api/api-reference/chat/create-a-chat-completion
* images route - POST /api/v1/images with ``prompt`` and ``input_references`` (the dedicated
  Image API described on https://openrouter.ai/docs/features/multimodal/image-generation).
  The result image is in ``data[0].b64_json`` (+ ``media_type``). The same page documents the
  output-size parameter: ``resolution`` (tiers 512 / 768 / 1K / 1.5K / 2K / 4K; ``size`` is a
  shorthand for it), so that is what the page's 1K / 2K / 4K option sends - on this route only.
  The chat route has no documented size parameter (``image_config`` keys are provider specific),
  so the option is not sent there and the result says so.
* model list - GET /api/v1/models?output_modalities=all (the default of that endpoint is
  text-only models), filtered client side on ``architecture.output_modalities``.
  Reference: https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties
"""

from __future__ import annotations

import base64
import io
import logging
import math
import re
import threading
import time

import httpx2
from PIL import Image

from .config import (
    DEFAULT_IMAGE_MODEL,
    LANGUAGES,
    REQUEST_TIMEOUT_S,
    get_settings,
    openrouter_api_key,
    openrouter_referer,
)
from . import verify
from .imaging import LoadedImage, to_jpeg_base64
from .models import ModeError

log = logging.getLogger("lab.openrouter")

BASE_URL = "https://openrouter.ai/api/v1"
MODELS_TTL_S = 600.0
MAX_SIDE_SENT = 2048
RATE_LIMIT_WAIT_S = 20.0  # a 429 is retried once after this long, then it counts as a failure

# The one place the prompt lives (README.md shows it; a test keeps the two in sync).
PROMPT_TEMPLATE = (
    "Translate all the text in this image into {language} and output the edited image only.\n"
    "\n"
    "Hard rules:\n"
    "1. Translate EVERY piece of text into {language}: every line of multi-line titles, headings, "
    "table cells, labels, captions, footers and small print. Do not leave any line in the source "
    "language, unless it is a brand name, a URL, an e-mail address or already in {language}.\n"
    "2. NEVER change, \"correct\", reformat or re-draw digits, numbers, prices, dates, times, phone "
    "numbers or handwritten marks: reproduce them pixel-for-pixel where they are.\n"
    "3. Keep the layout, positions, sizes, fonts, colours, background, illustrations and photos "
    "exactly as they are.\n"
    "4. Keep the image's dimensions and aspect ratio.\n"
    "5. Output the edited image only, no text reply."
)

# Appended (after a blank line) to the prompt of the one automatic retry; {problems} is one
# line per failed check (see build_retry_prompt).
RETRY_TEMPLATE = (
    "Your previous attempt at this same image failed an automatic check. Redo the whole image "
    "from the original and fix exactly the following, the hard rules above still apply:\n"
    "{problems}"
)
RETRY_DIGITS_LINE = (
    "- These digit sequences of the original image were changed or lost in your output. Copy them "
    "exactly as they are written in the original, character for character: {runs}."
)
RETRY_LEFTOVER_LINE = (
    "- These words are still in the source language in your output. Translate them into "
    "{language} as well: {words}."
)
# what the semantic judge (judge.py) found; each line appears only when there is something to name
RETRY_GARBLED_LINE = (
    "- These parts of your output are not real {language} text: {outputs}. Re-render them as the "
    "correct translation of: {sources}."
)
RETRY_GARBLED_BLIND_LINE = (
    "- These parts of your output are not real {language} text: {outputs}. Re-render them as "
    "correct {language} text that translates the original text at the same place."
)
RETRY_UNTRANSLATED_LINE = (
    "- These parts of your output are still in the source language. Translate them into "
    "{language} as well: {segments}."
)
RETRY_NUMBERS_LINE = (
    "- These numbers differ between the original image and your output. Copy each of them exactly "
    "as it is written in the original: {pairs}."
)
MAX_RETRY_RUNS = 20
MAX_RETRY_WORDS = 10
MAX_RETRY_SEGMENTS = 10  # garbled / untranslated segments and altered numbers named in the retry
MAX_QUOTED_CHARS = 120

# Image-output models that are served better by the dedicated Image API than by chat/completions
# (round 2: chat failed once with finish_reason=error for gemini-3-pro-image, /images worked).
IMAGES_ROUTE_FIRST_PREFIXES = ("google/gemini-3",)
IMAGES_ROUTE_FIRST_MARKERS = ("nano-banana",)

# aspect ratios accepted by the common image endpoints (OpenRouter ImageGenerationRequest enum)
ASPECT_RATIOS = [
    (1, 1), (1, 2), (2, 1), (2, 3), (3, 2), (3, 4), (4, 3), (4, 5), (5, 4), (9, 16), (16, 9), (9, 21), (21, 9),
]  # fmt: skip


class OpenRouterHTTPError(ModeError):
    def __init__(self, status: int, message: str):
        super().__init__(f"OpenRouter HTTP {status}: {message}")
        self.status = status
        self.message = message


def build_prompt(lang: str) -> str:
    return PROMPT_TEMPLATE.format(language=LANGUAGES.get(lang, lang))


def _quote(text: str) -> str:
    """A fragment of OCR text as a short, single-line, double-quoted string for the prompt."""
    flat = " ".join(str(text).replace('"', "'").split())
    return '"' + (flat[: MAX_QUOTED_CHARS - 1] + "\u2026" if len(flat) > MAX_QUOTED_CHARS else flat) + '"'


def _unique(items: list[str]) -> list[str]:
    return list(dict.fromkeys(i for i in items if i))


def build_retry_prompt(lang: str, verification: dict) -> str:
    """The prompt plus a second paragraph naming what the verification and the judge found wrong.

    Missing digit runs are given as they were written in the source (up to 20) and up to 10
    leftover source-language words must be translated too (the big ones, which make the verdict
    `partial` by themselves, first). From the judge: up to 10 garbled segments, each with the
    source text it stands for, up to 10 untranslated segments and up to 10 altered numbers. A
    number that only differs from a *soft* source run (low-confidence handwriting that Vision
    may have misread) is not named: telling the model to "copy" a misread number would make it
    wrong. A check that found nothing is not mentioned, and with nothing to name the plain
    prompt comes back.
    """
    language = LANGUAGES.get(lang, lang)
    problems: list[str] = []
    runs = [d.get("as_written") or d["run"] for d in verification.get("missing_digit_details") or []]
    runs = runs or list(verification.get("missing_digit_runs") or [])
    if runs:
        problems.append(RETRY_DIGITS_LINE.format(runs=", ".join(runs[:MAX_RETRY_RUNS])))
    big = [b["word"] for b in verification.get("big_leftover_words") or [] if b.get("word")]
    words = _unique(big + list(verification.get("leftover_words") or []))
    if words:
        problems.append(
            RETRY_LEFTOVER_LINE.format(language=language, words=", ".join(words[:MAX_RETRY_WORDS]))
        )

    judge = verification.get("judge") or {}
    if judge.get("status") == "ok":
        garbled = [s for s in judge.get("garbled_segments") or [] if (s.get("output_text") or "").strip()]
        garbled = garbled[:MAX_RETRY_SEGMENTS]
        if garbled:
            outputs = ", ".join(_quote(s["output_text"]) for s in garbled)
            sources = _unique([(s.get("source_text") or "").strip() for s in garbled])
            if sources:
                problems.append(
                    RETRY_GARBLED_LINE.format(
                        language=language, outputs=outputs, sources=", ".join(_quote(s) for s in sources)
                    )
                )
            else:
                problems.append(RETRY_GARBLED_BLIND_LINE.format(language=language, outputs=outputs))
        untranslated = _unique([(s or "").strip() for s in judge.get("untranslated_segments") or []])
        if untranslated:
            problems.append(
                RETRY_UNTRANSLATED_LINE.format(
                    language=language,
                    segments=", ".join(_quote(s) for s in untranslated[:MAX_RETRY_SEGMENTS]),
                )
            )
        soft = {d["run"] for d in verification.get("digit_runs") or [] if d.get("soft")}
        pairs = []
        for item in judge.get("altered_numbers") or []:
            src, out = str(item.get("source") or ""), str(item.get("output") or "")
            digits = verify.digit_string(src)
            if not digits or digits == verify.digit_string(out) or digits in soft:
                continue
            pairs.append(f"{_quote(src)} (your output has {_quote(out)})")
        if pairs:
            problems.append(RETRY_NUMBERS_LINE.format(pairs=", ".join(_unique(pairs)[:MAX_RETRY_SEGMENTS])))

    if not problems:
        return build_prompt(lang)
    return build_prompt(lang) + "\n\n" + RETRY_TEMPLATE.format(problems="\n".join(problems))


def preferred_route(model: str) -> str:
    """'images' for the Gemini 3 / nano-banana image models, 'chat' for everything else."""
    lowered = model.lower()
    if lowered.startswith(IMAGES_ROUTE_FIRST_PREFIXES) or any(m in lowered for m in IMAGES_ROUTE_FIRST_MARKERS):
        return "images"
    return "chat"


def route_order(model: str, setting: str = "auto") -> list[str]:
    """Routes to try, in order. OPENROUTER_IMAGE_API=chat|images forces one, auto picks per model."""
    if setting in ("chat", "images"):
        return [setting]
    first = preferred_route(model)
    return [first, "images" if first == "chat" else "chat"]


def nearest_aspect_ratio(width: int, height: int) -> str:
    """Closest supported aspect ratio (by log distance) as the 'w:h' string."""
    target = math.log(width / height)
    w, h = min(ASPECT_RATIOS, key=lambda r: abs(math.log(r[0] / r[1]) - target))
    return f"{w}:{h}"


def _headers() -> dict[str, str]:
    key = openrouter_api_key()
    if not key:
        raise ModeError("OPENROUTER_API_KEY is not set (OPEN_ROUTE_API_KEY, the production name, also works)")
    headers = {
        "Authorization": f"Bearer {key}",
        "Content-Type": "application/json",
        "X-Title": "LineBotTranslator overlay-lab",
    }
    referer = openrouter_referer()
    if referer:
        headers["HTTP-Referer"] = referer
    return headers


def _client() -> httpx2.Client:
    return httpx2.Client(timeout=httpx2.Timeout(REQUEST_TIMEOUT_S, connect=15.0))


def _error_message(response) -> str:
    try:
        body = response.json()
        err = body.get("error", body) if isinstance(body, dict) else body
        if isinstance(err, dict):
            return str(err.get("message") or err)[:500]
        return str(err)[:500]
    except Exception:  # noqa: BLE001
        return response.text[:500]


# ---------------------------------------------------------------- model list

_models_lock = threading.Lock()
_models_cache: dict = {"at": 0.0, "data": None}


def _fetch_models() -> dict:
    headers = {}
    key = openrouter_api_key()
    if key:
        headers["Authorization"] = f"Bearer {key}"
    with _client() as client:
        response = client.get(f"{BASE_URL}/models", params={"output_modalities": "all"}, headers=headers)
    if response.status_code >= 400:
        raise OpenRouterHTTPError(response.status_code, _error_message(response))
    models = {}
    for item in response.json().get("data", []):
        arch = item.get("architecture") or {}
        if "image" in (arch.get("output_modalities") or []):
            models[item["id"]] = {
                "name": item.get("name", item["id"]),
                "input_image": "image" in (arch.get("input_modalities") or []),
                "preferred_route": preferred_route(item["id"]),
            }
    return models


def list_image_models(force: bool = False) -> dict:
    """Image-output model ids, sorted, env default first. Cached for 10 minutes."""
    settings = get_settings()
    default = settings.openrouter_image_model or DEFAULT_IMAGE_MODEL
    if settings.mock:
        models = [default, "mock/image-model"]
        details = {m: {"name": m, "input_image": True, "preferred_route": preferred_route(m)} for m in models}
        return {"default": default, "models": models, "details": details, "cached": False, "error": None}

    error = None
    cached = False
    with _models_lock:
        fresh = _models_cache["data"] is not None and time.monotonic() - _models_cache["at"] < MODELS_TTL_S
        if fresh and not force:
            data, cached = _models_cache["data"], True
        else:
            try:
                data = _fetch_models()
                _models_cache.update(at=time.monotonic(), data=data)
            except Exception as exc:  # noqa: BLE001 - the page still works with just the default
                error = f"{type(exc).__name__}: {exc}"
                log.warning("stage=openrouter-models failed: %s", error)
                data = _models_cache["data"] or {}
    ids = sorted(data)
    ordered = [default] + [i for i in ids if i != default]
    details = {
        i: data.get(i) or {"name": i, "input_image": None, "preferred_route": preferred_route(i)} for i in ordered
    }
    return {"default": default, "models": ordered, "details": details, "cached": cached, "error": error}


# ---------------------------------------------------------------- helpers to read results


def decode_image_url(url: str, client: httpx2.Client | None = None) -> bytes:
    """Bytes of a ``data:`` URL, or the download of an http(s) URL."""
    match = re.match(r"^data:[^;,]*(;base64)?,(.*)$", url, re.S)
    if match:
        payload = match.group(2)
        return base64.b64decode(payload) if match.group(1) else payload.encode("latin-1")
    if url.startswith("http://") or url.startswith("https://"):
        if client is None:
            with _client() as c:
                r = c.get(url)
        else:
            r = client.get(url)
        if r.status_code >= 400:
            raise ModeError(f"could not download the generated image ({r.status_code})")
        return r.content
    raise ModeError("unrecognised image URL in the response")


def extract_chat_image(body: dict, client: httpx2.Client | None = None) -> tuple[bytes, str]:
    """Image bytes from a chat-completions body, plus any text the model said."""
    if isinstance(body.get("error"), dict):
        raise ModeError(f"OpenRouter returned an error: {body['error'].get('message', body['error'])}"[:600])
    choices = body.get("choices") or []
    if not choices:
        raise ModeError("OpenRouter response has no choices")
    message = choices[0].get("message") or {}
    text = message.get("content") if isinstance(message.get("content"), str) else ""
    urls: list[str] = []
    for img in message.get("images") or []:
        image_url = img.get("image_url") if isinstance(img, dict) else None
        url = image_url.get("url") if isinstance(image_url, dict) else image_url
        if isinstance(url, str):
            urls.append(url)
    if not urls and isinstance(message.get("content"), list):  # some providers inline the image
        for part in message["content"]:
            if isinstance(part, dict) and part.get("type") in ("image_url", "output_image"):
                image_url = part.get("image_url")
                url = image_url.get("url") if isinstance(image_url, dict) else image_url
                if isinstance(url, str):
                    urls.append(url)
            elif isinstance(part, dict) and part.get("type") == "text":
                text = (text or "") + str(part.get("text", ""))
    if not urls:
        said = (text or "").strip().replace("\n", " ")[:300]
        raise ModeError(
            "the model returned no image" + (f" (it said: {said})" if said else "")
            + f" [finish_reason={choices[0].get('finish_reason')}]"
        )
    return decode_image_url(urls[0], client), (text or "").strip()


def extract_images_api_image(body: dict, client: httpx2.Client | None = None) -> bytes:
    if isinstance(body.get("error"), dict):
        raise ModeError(f"OpenRouter returned an error: {body['error'].get('message', body['error'])}"[:600])
    items = body.get("data") or []
    if not items:
        raise ModeError("OpenRouter images response has no data")
    item = items[0]
    if item.get("b64_json"):
        return base64.b64decode(item["b64_json"])
    if item.get("url"):
        return decode_image_url(item["url"], client)
    raise ModeError("OpenRouter images response carries neither b64_json nor url")


# ---------------------------------------------------------------- the two routes


def _call_chat(model: str, prompt: str, data_url: str, params: dict) -> tuple[bytes, dict, str]:
    payload: dict = {
        "model": model,
        "modalities": ["image", "text"],
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {"type": "image_url", "image_url": {"url": data_url}},
                ],
            }
        ],
    }
    if params.get("aspect_ratio"):
        payload["image_config"] = {"aspect_ratio": params["aspect_ratio"]}
    # no output-size parameter here: the docs do not define one for the chat route
    with _client() as client:
        response = client.post(f"{BASE_URL}/chat/completions", headers=_headers(), json=payload)
        if response.status_code >= 400:
            raise OpenRouterHTTPError(response.status_code, _error_message(response))
        body = response.json()
        image, said = extract_chat_image(body, client)
    return image, body.get("usage") or {}, said


def _call_images(model: str, prompt: str, data_url: str, params: dict) -> tuple[bytes, dict, str]:
    payload: dict = {
        "model": model,
        "prompt": prompt,
        "input_references": [{"type": "image_url", "image_url": {"url": data_url}}],
    }
    if params.get("aspect_ratio"):
        payload["aspect_ratio"] = params["aspect_ratio"]
    if params.get("resolution"):
        payload["resolution"] = params["resolution"]  # documented tier: 1K / 2K / 4K ...
    with _client() as client:
        response = client.post(f"{BASE_URL}/images", headers=_headers(), json=payload)
        if response.status_code >= 400:
            raise OpenRouterHTTPError(response.status_code, _error_message(response))
        body = response.json()
        image = extract_images_api_image(body, client)
    return image, body.get("usage") or {}, ""


def _with_rate_limit_retry(call, *args):
    """``call(*args)``; an OpenRouter 429 is retried once after RATE_LIMIT_WAIT_S seconds.

    A second 429 propagates as the usual OpenRouterHTTPError and ends the run as a failure (the
    route/parameter fallbacks never swallow a 429).
    """
    try:
        return call(*args)
    except OpenRouterHTTPError as exc:
        if exc.status != 429:
            raise
        log.warning("stage=mode-b OpenRouter answered 429, waiting %.0f s before one retry", RATE_LIMIT_WAIT_S)
        time.sleep(RATE_LIMIT_WAIT_S)
        return call(*args)


def _png_bytes(data: bytes) -> tuple[bytes, tuple[int, int]]:
    try:
        im = Image.open(io.BytesIO(data))
        im.load()
    except Exception as exc:  # noqa: BLE001
        raise ModeError("the model's output is not a decodable image") from exc
    if im.format == "PNG":
        return data, im.size
    buf = io.BytesIO()
    im.convert("RGB").save(buf, "PNG")
    return buf.getvalue(), im.size


def _param_variants(aspect: str | None, resolution: str, api: str) -> list[tuple[dict, list[str]]]:
    """[(params to send, names of the optional params left out)], richest first.

    A provider that rejects the request (400 / 404 / 422) gets it again without the output
    size first, then without the aspect ratio as well.
    """
    params = {"aspect_ratio": aspect or "", "resolution": resolution if api == "images" else ""}
    variants = [(dict(params), [])]
    dropped: list[str] = []
    for name, key in (("resolution", "resolution"), ("aspect ratio", "aspect_ratio")):
        if params.get(key):
            params[key] = ""
            dropped = dropped + [name]
            variants.append((dict(params), list(dropped)))
    return variants


def _label(api: str, dropped: list[str]) -> str:
    return api + (f" (no {', no '.join(dropped)})" if dropped else "")


def run_image_model(
    image: LoadedImage, lang: str, model: str, *, prompt: str | None = None, resolution: str = ""
) -> dict:
    """Send the picture to the image model and return {png, usage, api_used, ...}.

    `prompt` replaces the default prompt (the retry sends the prompt plus what went wrong);
    `resolution` is the output-size tier (1K / 2K / 4K), sent on the images route only.
    """
    settings = get_settings()
    t0 = time.perf_counter()
    route_pref = preferred_route(model)
    if settings.mock:
        png, size = _png_bytes(image.data)
        return {
            "png": png,
            "size": list(size),
            "usage": {"cost": 0.0},
            "api_used": "mock",
            "attempts": [],
            "model_text": "",
            "elapsed_ms": int((time.perf_counter() - t0) * 1000),
            "route_preference": route_pref,
            "resolution_requested": resolution,
            "resolution_applied": False,
            "prompt": prompt or build_prompt(lang),
        }

    _headers()  # fail fast (and clearly) when the key is missing
    b64, _ = to_jpeg_base64(image.pil, MAX_SIDE_SENT, quality=90)
    data_url = f"data:image/jpeg;base64,{b64}"
    prompt = prompt or build_prompt(lang)
    aspect = nearest_aspect_ratio(image.width, image.height)
    order = route_order(model, settings.openrouter_image_api)
    attempts: list[str] = []
    result = None
    api_used = ""
    resolution_applied = False
    for api in order:
        call = _call_chat if api == "chat" else _call_images
        for params, dropped in _param_variants(aspect, resolution, api):
            try:
                result = _with_rate_limit_retry(call, model, prompt, data_url, params)
                api_used = _label(api, dropped)
                resolution_applied = bool(params.get("resolution"))
                break
            except OpenRouterHTTPError as exc:
                attempts.append(f"{_label(api, dropped)}: HTTP {exc.status}: {exc.message[:200]}")
                if exc.status in (400, 404, 422) and any(params.values()):
                    continue  # retry once more without the next optional parameter
                if exc.status in (400, 404, 405, 422) or (api == "images" and exc.status == 502):
                    break  # this route does not suit the model (or failed the generation): try the next
                raise
            except ModeError as exc:
                attempts.append(f"{api}: {exc}"[:300])
                break  # e.g. "the model returned no image": try the other route
        if result is not None:
            break
    if result is None:
        raise ModeError("no OpenRouter route produced an image: " + " | ".join(attempts))

    raw, usage, said = result
    png, size = _png_bytes(raw)
    elapsed = int((time.perf_counter() - t0) * 1000)
    log.info(
        "stage=mode-b ms=%d model=%s api=%s resolution=%s out=%dx%d bytes=%d cost=%s",
        elapsed, model, api_used, resolution if resolution_applied else "-", size[0], size[1], len(png), usage.get("cost"),
    )  # fmt: skip
    return {
        "png": png,
        "size": list(size),
        "usage": usage,
        "api_used": api_used,
        "attempts": attempts,
        "model_text": said[:500],
        "elapsed_ms": elapsed,
        "route_preference": route_pref,
        "resolution_requested": resolution,
        "resolution_applied": resolution_applied,
        "prompt": prompt,
    }
