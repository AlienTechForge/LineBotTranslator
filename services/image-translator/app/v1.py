"""The production API: `POST /v1/translations` and its two read routes, plus `GET /healthz`.

Every `/v1/*` request carries `X-Service-Token` equal to `SERVICE_TOKEN` (`v1_gate`, a middleware: the check
runs before the upload is read). With no `SERVICE_TOKEN` set the routes answer 503, never open. Errors of
`/v1` are `{"status": "error", "reason": <code>, "detail": <text>, "schema_version": 1}` with the HTTP status of
the problem: 400 bad input, 401 no token, 403 wrong token, 404, 409 a request id that is in use, 413 too big,
415 not a jpeg / png / webp, 503 not configured. A request that was accepted always answers 200: when both
modes fail the document says `status: "error"` and a reason, so that the caller can still tell its user
something.

A finished request leaves two files in `<LAB_DATA_DIR>/v1/<request_id>/`: `record.json` (the response, so
`GET /v1/translations/<id>` can say it again) and, for `status: "image"`, `image.png`. They are deleted
`V1_RETENTION_MINUTES` after the record was written (`Sweeper`, and a request for an expired record is a 404 even
before the sweeper has run). The source picture is never written; the scratch files of Mode A live in a
`work/` folder that is removed when the request ends.

Nothing here logs a text, a translation, an image, a token or a header: the log lines carry the request id, the
status and counts.
"""

from __future__ import annotations

import json
import logging
import os
import re
import secrets
import shutil
import threading
import time
import uuid
from pathlib import Path

from fastapi import APIRouter, File, Form, Request, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, JSONResponse, Response

from . import __version__, budget, policy
from .config import LANG_CODE_RE, MAX_LANG_CODE_CHARS, MAX_UPLOAD_BYTES, get_settings, normalize_lang
from .imaging import ALLOWED_CONTENT_TYPES, ImageError, LoadedImage, load_image
from .openrouter import MODEL_ID_RE

log = logging.getLogger("lab.v1")

SCHEMA_VERSION = 1
REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
V1_DIR = "v1"
RECORD_FILE = "record.json"
IMAGE_FILE = "image.png"
WORK_DIR = "work"
MAX_UPLOAD_OVERHEAD = 1024 * 1024  # multipart framing and the small fields, on top of the picture itself
TOKEN_HEADER = "x-service-token"

router = APIRouter()


# ---------------------------------------------------------------- errors


class V1Error(Exception):
    """An error of /v1: the HTTP status, a machine readable reason and a sentence for a human."""

    def __init__(self, status: int, reason: str, detail: str) -> None:
        super().__init__(detail)
        self.status, self.reason, self.detail = status, reason, detail


STATUS_REASONS = {400: "bad_request", 401: "unauthorized", 403: "forbidden", 404: "not_found", 405: "method_not_allowed"}


def error_response(status: int, reason: str, detail: str) -> JSONResponse:
    body = {"status": "error", "reason": reason, "detail": detail, "schema_version": SCHEMA_VERSION}
    return JSONResponse(body, status_code=status)


def is_v1_path(path: str) -> bool:
    return path == "/v1" or path.startswith("/v1/")


async def v1_error_handler(request: Request, exc: V1Error) -> JSONResponse:
    return error_response(exc.status, exc.reason, exc.detail)


async def v1_gate(request: Request, call_next):
    """The service token of every /v1 request (a middleware: it runs before the body is read)."""
    if not is_v1_path(request.url.path):
        return await call_next(request)
    expected = get_settings().service_token
    if not expected:
        return error_response(503, "service_token_missing", "SERVICE_TOKEN is not set: the service is not configured")
    supplied = request.headers.get(TOKEN_HEADER, "")
    if not supplied:
        return error_response(401, "unauthorized", "the X-Service-Token header is missing")
    if not secrets.compare_digest(supplied.encode("utf-8"), expected.encode("utf-8")):
        return error_response(403, "forbidden", "the service token is not valid")
    length = request.headers.get("content-length", "")
    if request.method == "POST" and length.isdigit() and int(length) > MAX_UPLOAD_BYTES + MAX_UPLOAD_OVERHEAD:
        return error_response(413, "payload_too_large", f"the upload is larger than {MAX_UPLOAD_BYTES // (1024 * 1024)} MB")
    return await call_next(request)


# ---------------------------------------------------------------- records on disk


def v1_root() -> Path:
    return get_settings().data_dir / V1_DIR


def record_dir(request_id: str) -> Path:
    return v1_root() / request_id


def retention_s() -> float:
    return get_settings().v1_retention_minutes * 60.0


def is_expired(record_file: Path, now: float | None = None) -> bool:
    """A record older than V1_RETENTION_MINUTES (by the time it was written) is gone, sweeper or not."""
    try:
        age = (time.time() if now is None else now) - record_file.stat().st_mtime
    except OSError:
        return True
    return age > retention_s()


def write_atomic(path: Path, data: bytes) -> None:
    tmp = path.with_name(f".{path.name}.{os.getpid()}.{threading.get_ident()}.tmp")
    try:
        tmp.write_bytes(data)
        os.replace(tmp, path)
    finally:
        try:
            tmp.unlink()
        except FileNotFoundError:
            pass


_inflight: set[str] = set()
_inflight_lock = threading.Lock()


def sweep(now: float | None = None) -> int:
    """Delete the records (folders of v1/) older than the retention; returns how many were removed.

    A folder counts from its record.json, or from the folder itself when there is none (a request that never
    finished, e.g. the process was killed). A request that is running right now is never touched.
    """
    root = v1_root()
    current = time.time() if now is None else now
    removed = 0
    try:
        entries = list(root.iterdir())
    except OSError:
        return 0
    with _inflight_lock:
        busy = set(_inflight)
    for entry in entries:
        if not entry.is_dir() or entry.name in busy:
            continue
        record = entry / RECORD_FILE
        try:
            stamp = (record if record.exists() else entry).stat().st_mtime
        except OSError:
            continue
        if current - stamp > retention_s():
            shutil.rmtree(entry, ignore_errors=True)
            removed += 1
    return removed


class Sweeper(threading.Thread):
    """Calls `sweep()` once at start and then every minute (or a quarter of the retention when that is shorter)."""

    def __init__(self) -> None:
        super().__init__(name="v1-sweeper", daemon=True)
        self._stop_event = threading.Event()

    def interval(self) -> float:
        return max(1.0, min(60.0, retention_s() / 4.0))

    def run(self) -> None:
        while True:
            try:
                count = sweep()
                if count:
                    log.info("stage=v1-sweep removed=%d", count)
            except Exception as exc:  # noqa: BLE001 - the sweeper must outlive any one bad folder
                log.warning("stage=v1-sweep failed: %s", type(exc).__name__)
            if self._stop_event.wait(self.interval()):
                return

    def stop(self) -> None:
        self._stop_event.set()


# ---------------------------------------------------------------- input


async def read_image(upload: UploadFile | None) -> LoadedImage:
    if upload is None:
        raise V1Error(400, "bad_request", "the `image` file is missing")
    if (upload.content_type or "").lower() not in ALLOWED_CONTENT_TYPES:
        raise V1Error(415, "unsupported_media_type", "only image/jpeg, image/png and image/webp uploads are accepted")
    chunks: list[bytes] = []
    size = 0
    while True:
        chunk = await upload.read(1024 * 1024)
        if not chunk:
            break
        size += len(chunk)
        if size > MAX_UPLOAD_BYTES:
            raise V1Error(413, "payload_too_large", f"the upload is larger than {MAX_UPLOAD_BYTES // (1024 * 1024)} MB")
        chunks.append(chunk)
    if size == 0:
        raise V1Error(400, "bad_request", "the `image` file is empty")
    try:
        return await run_in_threadpool(load_image, b"".join(chunks))
    except ImageError as exc:
        raise V1Error(415, "unsupported_media_type", str(exc)) from exc


def parse_target_lang(raw: str) -> tuple[str, list[str]]:
    """(language to use, warnings): known codes in their own spelling, unknown ones as given."""
    code = (raw or "").strip()
    if not code:
        raise V1Error(400, "bad_request", "`target_lang` is required")
    if len(code) > MAX_LANG_CODE_CHARS or not LANG_CODE_RE.match(code):
        raise V1Error(400, "bad_request", "`target_lang` must be a BCP-47 language code such as zh-TW, en or ja")
    lang, known = normalize_lang(code)
    warnings = []
    if not known:
        warnings.append(
            f"target_lang {lang!r} is not in the language table: the prompts use the code itself and the fonts "
            "fall back to Noto Sans / DejaVu"
        )
    return lang, warnings


# ---------------------------------------------------------------- the routes


def build_response(request_id: str, lang: str, outcome: policy.Outcome, image_info: dict | None) -> dict:
    return {
        "request_id": request_id,
        "schema_version": SCHEMA_VERSION,
        "target_lang": lang,
        "status": outcome.status,
        "tier": outcome.tier,
        "reason": outcome.reason,
        "mode": outcome.mode,
        "verdict": outcome.verdict,
        "usability": outcome.usability,
        "coverage": outcome.coverage,
        "image": image_info,
        "source": outcome.source,
        "text": outcome.text,
        "judge": outcome.judge,
        "costs": outcome.costs,
        "budget": budget.snapshot(),
        "timings_ms": outcome.timings_ms,
        "attempts": outcome.attempts,
        "warnings": outcome.warnings,
    }


def attempts_summary(attempts: list[dict]) -> str:
    """`A:garbled+score/0.10,B:numbers+score/0.60` for the log: mode, verdict and usability (two decimals, `-` when
    unknown) of every attempt; `A:skipped(small_text)` for a mode that did not run, `B:error` for one that failed.
    Only fixed words and numbers: no text, no error message."""
    parts = []
    for item in attempts:
        mode = str(item.get("mode") or "?")
        if item.get("skipped"):
            parts.append(f"{mode}:skipped({item['skipped']})")
        elif item.get("error"):
            parts.append(f"{mode}:error")
        else:
            usability = item.get("usability")
            shown = "-" if usability is None else f"{usability:.2f}"
            parts.append(f"{mode}:{item.get('verdict') or '-'}/{shown}")
    return ",".join(parts) or "-"


def internal_failure(started: float, warnings: list[str]) -> policy.Outcome:
    """The outcome of a request whose policy crashed on something unforeseen: still an answer, never a 500."""
    return policy.Outcome(
        "error",
        "internal_error",
        warnings=list(warnings),
        timings_ms={"ocr": None, "mode_a": None, "mode_b": None, "judge": None, "total": int((time.monotonic() - started) * 1000)},
    )


@router.post("/v1/translations")
async def create_translation(
    image: UploadFile | None = File(None),
    target_lang: str = Form(""),
    request_id: str = Form(""),
    policy_name: str = Form("a_then_b", alias="policy"),
    mode_b_model: str = Form(""),
) -> JSONResponse:
    started = time.monotonic()
    settings = get_settings()
    rid = request_id.strip() or uuid.uuid4().hex
    if not REQUEST_ID_RE.match(rid):
        raise V1Error(400, "bad_request", "`request_id` must match ^[A-Za-z0-9_-]{1,64}$")
    lang, warnings = parse_target_lang(target_lang)
    chosen_policy = (policy_name or "a_then_b").strip() or "a_then_b"
    if chosen_policy not in policy.POLICIES:
        raise V1Error(400, "bad_request", f"`policy` must be one of {', '.join(policy.POLICIES)}")
    model = mode_b_model.strip()
    if model and not MODEL_ID_RE.match(model):
        raise V1Error(400, "bad_request", "`mode_b_model` is not a valid model id")
    loaded = await read_image(image)

    folder = record_dir(rid)
    try:
        folder.parent.mkdir(parents=True, exist_ok=True)
        folder.mkdir()
    except FileExistsError as exc:
        raise V1Error(409, "request_id_in_use", "a request with this `request_id` is running or still retained") from exc
    with _inflight_lock:
        _inflight.add(rid)
    work = folder / WORK_DIR
    try:
        deadline = started + settings.v1_deadline_s
        try:
            outcome = await run_in_threadpool(
                policy.run, loaded, lang, chosen_policy, rid, deadline,
                work_dir=work, mode_b_model=model or None, warnings=warnings,
            )  # fmt: skip
        except Exception as exc:  # noqa: BLE001 - a bug must not turn into a 500 the caller cannot explain
            log.error("stage=v1 request=%s the policy crashed: %s", rid, type(exc).__name__)
            outcome = internal_failure(started, warnings)
        image_info = None
        if outcome.png is not None:
            write_atomic(folder / IMAGE_FILE, outcome.png)
            width, height = outcome.size or (0, 0)
            image_info = {
                "path": f"/v1/translations/{rid}/{IMAGE_FILE}", "width": width, "height": height, "bytes": len(outcome.png),
            }  # fmt: skip
        response = build_response(rid, lang, outcome, image_info)
        write_atomic(folder / RECORD_FILE, json.dumps(response, ensure_ascii=False).encode("utf-8"))
        log.info(
            "stage=v1 request=%s status=%s tier=%s reason=%s mode=%s verdict=%s usability=%s cost=%.4f total_ms=%s "
            "attempts=%s glyph_px=%s",
            rid, outcome.status, outcome.tier, outcome.reason, outcome.mode, outcome.verdict, outcome.usability,
            outcome.costs.get("total_usd", 0.0), outcome.timings_ms.get("total"),
            attempts_summary(outcome.attempts),
            "-" if not outcome.source else f"{outcome.source['median_glyph_px']:.1f}",
        )  # fmt: skip
        return JSONResponse(response)
    finally:
        shutil.rmtree(work, ignore_errors=True)
        with _inflight_lock:
            _inflight.discard(rid)


def _record_file(request_id: str) -> Path:
    if not REQUEST_ID_RE.match(request_id):
        raise V1Error(404, "not_found", "no such translation")
    path = record_dir(request_id) / RECORD_FILE
    if not path.is_file() or is_expired(path):
        raise V1Error(404, "not_found", "no such translation (it may have expired)")
    return path


@router.get("/v1/translations/{request_id}")
def get_translation(request_id: str) -> Response:
    path = _record_file(request_id)
    try:
        return Response(path.read_bytes(), media_type="application/json")
    except OSError as exc:
        raise V1Error(404, "not_found", "no such translation (it may have expired)") from exc


@router.get("/v1/translations/{request_id}/image.png")
def get_translation_image(request_id: str) -> FileResponse:
    record = _record_file(request_id)
    path = record.with_name(IMAGE_FILE)
    if not path.is_file():
        raise V1Error(404, "not_found", "this translation has no image")
    return FileResponse(path, media_type="image/png")


@router.get("/healthz")
def healthz() -> dict:
    """Open to everyone, free of secrets: the budget numbers and whether the canned mock is on."""
    try:
        numbers = budget.snapshot()
    except Exception:  # noqa: BLE001 - a health check must answer
        numbers = None
    return {"status": "ok", "mock": get_settings().mock, "version": __version__, "budget": numbers}
