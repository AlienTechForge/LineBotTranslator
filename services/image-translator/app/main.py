"""FastAPI app: the production API (`/v1`, `/healthz`, see v1.py) and the lab (page, JSON API and job outputs under `/lab`).

The lab keeps its old root paths for one release: `/api/*` and `/files/*` still answer (with a `Deprecation: true`
header) and `GET /` redirects to `/lab/`. `LAB_TOKEN` gates every lab route; `SERVICE_TOKEN` gates `/v1`.
"""

from __future__ import annotations

import io
import logging
import mimetypes
import re
import secrets
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path

from fastapi import APIRouter, Depends, FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.exception_handlers import http_exception_handler, request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse, Response
from jinja2 import Environment, FileSystemLoader, select_autoescape
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import inpaint, jobs, mock, openrouter, v1
from .config import (
    DEFAULT_OUTPUT_SIZE,
    LANGUAGES,
    MAX_BATCH_FILES,
    MAX_CONCURRENT_CLAUDE,
    MAX_CONCURRENT_GENERATIONS,
    MAX_IMAGE_MODELS,
    MAX_RUNS,
    MAX_UPLOAD_BYTES,
    OUTPUT_SIZES,
    get_settings,
)
from .imaging import ALLOWED_CONTENT_TYPES, ImageError, load_image

log = logging.getLogger("lab.main")

TEMPLATE_DIR = Path(__file__).parent / "templates"
PROVIDER_DEFAULT_SIZE = "default"  # output_size value for "send no resolution, use the provider's size"
FILE_NAME_RE = re.compile(r"^[A-Za-z0-9_.-]{1,64}$")
MODEL_RE = openrouter.MODEL_ID_RE
SERVED_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".json"}
CSP = (
    "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; "
    "script-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'"
)


def parse_models(values: list[str], single: str = "") -> list[str]:
    """Model ids from repeated `image_models` fields and/or comma separated lists, de-duplicated in order."""
    out: list[str] = []
    for raw in [*values, single]:
        for part in raw.split(","):
            part = part.strip()
            if part and part not in out:
                out.append(part)
    return out


@dataclass
class Options:
    """The form fields shared by POST /api/jobs and POST /api/batches, validated."""

    target_lang: str
    mode_a: bool
    mode_b: bool
    models: list[str]
    output_size: str
    verify: bool
    verify_details: bool
    runs: int
    judge: bool
    mode_a_retry: bool | None
    mode_c: bool = False


def form_options(
    target_lang: str = Form("en"),
    mode_a: bool = Form(False),
    mode_b: bool = Form(False),
    image_models: list[str] = Form(default_factory=list),
    image_model: str = Form(""),
    output_size: str = Form(DEFAULT_OUTPUT_SIZE),
    verify: bool = Form(True),
    verify_details: bool = Form(False),
    runs: int = Form(1),
    judge: bool = Form(True),
    mode_a_retry: bool | None = Form(None),
    mode_c: bool = Form(False),
) -> Options:
    if target_lang not in LANGUAGES:
        raise HTTPException(400, f"target_lang must be one of {', '.join(LANGUAGES)}")
    if not (mode_a or mode_b or mode_c):
        raise HTTPException(400, "select at least one of Mode A, Mode B and Mode C")
    models = parse_models(image_models, image_model) or [get_settings().openrouter_image_model]
    if len(models) > MAX_IMAGE_MODELS:
        raise HTTPException(400, f"select at most {MAX_IMAGE_MODELS} image models")
    if not all(MODEL_RE.match(m) for m in models):
        raise HTTPException(400, "invalid image model id")
    # FastAPI turns an empty form value into the default (2K), so "the provider's own size" has a name
    output_size = "" if output_size == PROVIDER_DEFAULT_SIZE else output_size
    if output_size not in OUTPUT_SIZES:
        raise HTTPException(400, f"output_size must be one of: default, {', '.join(s for s in OUTPUT_SIZES if s)}")
    if not 1 <= runs <= MAX_RUNS:
        raise HTTPException(400, f"runs must be between 1 and {MAX_RUNS}")
    return Options(
        target_lang, mode_a, mode_b, models, output_size, verify, verify_details, runs, judge, mode_a_retry, mode_c
    )


def resolve_target_langs(values: list[str], default: str, count: int) -> list[str]:
    """One language per file in file order: blank or missing entries use the batch's `target_lang`."""
    if len(values) > count:
        raise HTTPException(400, f"target_langs has {len(values)} values for {count} file(s): at most one per file")
    out: list[str] = []
    for i in range(count):
        value = values[i].strip() if i < len(values) else ""
        value = value or default
        if value not in LANGUAGES:
            raise HTTPException(400, f"target_langs[{i}] must be one of {', '.join(LANGUAGES)}")
        out.append(value)
    return out


def safe_name(filename: str | None) -> str:
    """The upload's file name for the page and the manifests: no path, no control characters, 80 characters."""
    name = Path((filename or "").replace("\\", "/")).name
    return "".join(ch for ch in name if ch.isprintable())[:80]


async def read_image(file: UploadFile):
    """Validate and decode one upload (content type, size, decodable, allowed format)."""
    if (file.content_type or "").lower() not in ALLOWED_CONTENT_TYPES:
        raise HTTPException(415, "only image/jpeg, image/png and image/webp uploads are accepted")
    chunks: list[bytes] = []
    size = 0
    while True:
        chunk = await file.read(1024 * 1024)
        if not chunk:
            break
        size += len(chunk)
        if size > MAX_UPLOAD_BYTES:
            raise HTTPException(413, f"upload is larger than {MAX_UPLOAD_BYTES // (1024 * 1024)} MB")
        chunks.append(chunk)
    if size == 0:
        raise HTTPException(400, "empty upload")
    try:
        return await run_in_threadpool(load_image, b"".join(chunks))
    except ImageError as exc:
        raise HTTPException(415, str(exc)) from exc


async def require_token(request: Request) -> None:
    """When LAB_TOKEN is set every request needs it as ?token= or the X-Lab-Token header."""
    expected = get_settings().lab_token
    if not expected:
        return
    supplied = request.query_params.get("token") or request.headers.get("x-lab-token") or ""
    if not secrets.compare_digest(supplied.encode("utf-8"), expected.encode("utf-8")):
        raise HTTPException(status_code=401, detail="missing or invalid lab token")


_templates = Environment(loader=FileSystemLoader(str(TEMPLATE_DIR)), autoescape=select_autoescape(["html"]))

# The lab: the page at /lab/ and its JSON API and files. All of them carry LAB_TOKEN; `lab_api` is also
# mounted at the old root paths for one release.
lab_page = APIRouter(dependencies=[Depends(require_token)])
lab_api = APIRouter(dependencies=[Depends(require_token)])
legacy_page = APIRouter(dependencies=[Depends(require_token)])


@lab_page.get("/", response_class=HTMLResponse)
async def index() -> HTMLResponse:
    settings = get_settings()
    html = _templates.get_template("index.html").render(
        languages=LANGUAGES,
        default_image_model=settings.openrouter_image_model,
        claude_model=settings.claude_model,
        mock=settings.mock,
        max_upload_mb=MAX_UPLOAD_BYTES // (1024 * 1024),
        max_models=MAX_IMAGE_MODELS,
        max_runs=MAX_RUNS,
        max_generations=MAX_CONCURRENT_GENERATIONS,
        max_claude=MAX_CONCURRENT_CLAUDE,
        max_batch=MAX_BATCH_FILES,
        mode_a_retry=settings.mode_a_retry,
        translate_model=settings.translate_model,
        inpaint_model=inpaint.effective_model(),
        judge_model=settings.claude_judge_model,
        output_sizes=[s for s in OUTPUT_SIZES if s],
        default_output_size=DEFAULT_OUTPUT_SIZE,
    )
    return HTMLResponse(html, headers={"Content-Security-Policy": CSP})


@legacy_page.get("/")
async def legacy_index(request: Request) -> RedirectResponse:
    """The page used to live at the root: send the browser to /lab/ and keep its query string (?token=)."""
    query = request.url.query
    return RedirectResponse("/lab/" + (f"?{query}" if query else ""), status_code=307)


@lab_api.get("/api/image-models")
def image_models(refresh: bool = False) -> dict:
    return openrouter.list_image_models(force=refresh)


@lab_api.get("/api/mock-image")
def mock_image() -> Response:
    if not get_settings().mock:
        raise HTTPException(status_code=404, detail="only available with LAB_MOCK=1")
    buf = io.BytesIO()
    mock.make_fixture().image.save(buf, "PNG")
    return Response(buf.getvalue(), media_type="image/png")


@lab_api.post("/api/jobs")
async def create_job(request: Request, file: UploadFile = File(...), opts: Options = Depends(form_options)) -> JSONResponse:
    image = await read_image(file)
    job_id = jobs.start_job(
        request.app.state.store, image, opts.target_lang, opts.mode_a, opts.mode_b, opts.models, opts.verify,
        opts.verify_details, opts.output_size, runs=opts.runs, judge_on=opts.judge,
        mode_a_retry=opts.mode_a_retry, name=safe_name(file.filename), run_c=opts.mode_c,
    )  # fmt: skip
    return JSONResponse({"job_id": job_id})


@lab_api.post("/api/batches")
async def create_batch(
    request: Request,
    files: list[UploadFile] = File(...),
    target_langs: list[str] = Form(default_factory=list),
    opts: Options = Depends(form_options),
) -> JSONResponse:
    if not 1 <= len(files) <= MAX_BATCH_FILES:
        raise HTTPException(400, f"send between 1 and {MAX_BATCH_FILES} images (field `files`)")
    langs = resolve_target_langs(target_langs, opts.target_lang, len(files))
    # every image is checked before the first job starts: a bad one rejects the whole batch
    images = [(await read_image(f), safe_name(f.filename)) for f in files]
    batch_id, job_ids = jobs.start_batch(
        request.app.state.store, images, opts.target_lang, opts.mode_a, opts.mode_b, opts.models, opts.verify,
        opts.verify_details, opts.output_size, runs=opts.runs, judge_on=opts.judge,
        mode_a_retry=opts.mode_a_retry, langs=langs, run_c=opts.mode_c,
    )  # fmt: skip
    return JSONResponse({"batch_id": batch_id, "job_ids": job_ids})


@lab_api.get("/api/jobs/{job_id}")
def job_status(job_id: str, request: Request) -> dict:
    if not jobs.JOB_ID_RE.match(job_id):
        raise HTTPException(404, "unknown job")
    job = request.app.state.store.snapshot(job_id) or jobs.load_persisted(get_settings().data_dir, job_id)
    if job is None:
        raise HTTPException(404, "unknown job")
    return job


@lab_api.get("/api/batches/{batch_id}")
def batch_status(batch_id: str, request: Request) -> dict:
    if not jobs.BATCH_ID_RE.match(batch_id):
        raise HTTPException(404, "unknown batch")
    found = request.app.state.store.snapshot_batch(batch_id) or jobs.load_persisted_batch(
        get_settings().data_dir, batch_id
    )
    if found is None:
        raise HTTPException(404, "unknown batch")
    return found


@lab_api.get("/api/batches/{batch_id}/manifest")
def batch_manifest(batch_id: str) -> FileResponse:
    """The local copy of the batch manifest (written when the batch was published)."""
    if not jobs.BATCH_ID_RE.match(batch_id):
        raise HTTPException(404, "not found")
    path = get_settings().data_dir / f"batch-{batch_id}" / "manifest.json"
    if not path.is_file():
        raise HTTPException(404, "not found")
    return FileResponse(path, media_type="application/json", headers={"Cache-Control": "private, max-age=60"})


@lab_api.get("/files/{job_id}/{name}")
def job_file(job_id: str, name: str, dl: bool = False) -> FileResponse:
    if not jobs.JOB_ID_RE.match(job_id) or not FILE_NAME_RE.match(name):
        raise HTTPException(404, "not found")
    if Path(name).suffix.lower() not in SERVED_SUFFIXES:
        raise HTTPException(404, "not found")
    root = (get_settings().data_dir / job_id).resolve()
    path = (root / name).resolve()
    if path.parent != root or not path.is_file():
        raise HTTPException(404, "not found")
    media = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    headers = {"Cache-Control": "private, max-age=300"}
    if dl:
        return FileResponse(path, media_type=media, filename=f"{job_id}-{name}", headers=headers)
    return FileResponse(path, media_type=media, headers=headers)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """The sweeper that deletes the records of /v1 after V1_RETENTION_MINUTES runs for as long as the app does."""
    sweeper = v1.Sweeper()
    sweeper.start()
    try:
        yield
    finally:
        sweeper.stop()


def create_app() -> FastAPI:
    if not logging.getLogger().handlers:
        logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    app = FastAPI(title="image-translator", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.store = jobs.JobStore()

    app.middleware("http")(v1.v1_gate)  # added first = inner: its refusals still get the security headers below

    @app.middleware("http")
    async def security_headers(request: Request, call_next):
        response = await call_next(request)
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("Referrer-Policy", "no-referrer")
        response.headers.setdefault("Cache-Control", "no-store")
        path = request.url.path
        if path == "/" or path.startswith(("/api/", "/files/")):  # the old root paths of the lab: use /lab/...
            response.headers.setdefault("Deprecation", "true")
        return response

    app.add_exception_handler(v1.V1Error, v1.v1_error_handler)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exc: RequestValidationError):
        if v1.is_v1_path(request.url.path):
            problems = "; ".join(f"{'.'.join(str(p) for p in e.get('loc', ()))}: {e.get('msg', '')}" for e in exc.errors())
            return v1.error_response(400, "bad_request", problems[:300] or "invalid request")
        return await request_validation_exception_handler(request, exc)

    @app.exception_handler(StarletteHTTPException)
    async def http_error(request: Request, exc: StarletteHTTPException):
        if v1.is_v1_path(request.url.path):
            reason = v1.STATUS_REASONS.get(exc.status_code, "error")
            return v1.error_response(exc.status_code, reason, str(exc.detail))
        return await http_exception_handler(request, exc)

    app.include_router(v1.router)  # /v1/translations..., /healthz
    app.include_router(lab_page, prefix="/lab")
    app.include_router(lab_api, prefix="/lab")
    app.include_router(legacy_page)  # GET / -> /lab/
    app.include_router(lab_api)  # the old /api/* and /files/* paths, for one release
    return app


app = create_app()
