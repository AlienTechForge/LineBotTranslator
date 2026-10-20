"""FastAPI app: one page, a small JSON API, and static serving of job outputs."""

from __future__ import annotations

import io
import logging
import mimetypes
import re
import secrets
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response
from jinja2 import Environment, FileSystemLoader, select_autoescape

from . import jobs, mock, openrouter
from .config import LANGUAGES, MAX_IMAGE_MODELS, MAX_UPLOAD_BYTES, OUTPUT_SIZES, get_settings
from .imaging import ALLOWED_CONTENT_TYPES, ImageError, load_image

log = logging.getLogger("lab.main")

TEMPLATE_DIR = Path(__file__).parent / "templates"
FILE_NAME_RE = re.compile(r"^[A-Za-z0-9_.-]{1,64}$")
MODEL_RE = re.compile(r"^[A-Za-z0-9._:/@+-]{1,120}$")
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


async def require_token(request: Request) -> None:
    """When LAB_TOKEN is set every request needs it as ?token= or the X-Lab-Token header."""
    expected = get_settings().lab_token
    if not expected:
        return
    supplied = request.query_params.get("token") or request.headers.get("x-lab-token") or ""
    if not secrets.compare_digest(supplied.encode("utf-8"), expected.encode("utf-8")):
        raise HTTPException(status_code=401, detail="missing or invalid lab token")


def create_app() -> FastAPI:
    if not logging.getLogger().handlers:
        logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    app = FastAPI(
        title="overlay-lab",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        dependencies=[Depends(require_token)],
    )
    app.state.store = jobs.JobStore()
    env = Environment(loader=FileSystemLoader(str(TEMPLATE_DIR)), autoescape=select_autoescape(["html"]))

    @app.middleware("http")
    async def security_headers(request: Request, call_next):
        response = await call_next(request)
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("Referrer-Policy", "no-referrer")
        response.headers.setdefault("Cache-Control", "no-store")
        return response

    @app.get("/", response_class=HTMLResponse)
    async def index() -> HTMLResponse:
        settings = get_settings()
        html = env.get_template("index.html").render(
            languages=LANGUAGES,
            default_image_model=settings.openrouter_image_model,
            claude_model=settings.claude_model,
            mock=settings.mock,
            max_upload_mb=MAX_UPLOAD_BYTES // (1024 * 1024),
            max_models=MAX_IMAGE_MODELS,
            output_sizes=[s for s in OUTPUT_SIZES if s],
        )
        return HTMLResponse(html, headers={"Content-Security-Policy": CSP})

    @app.get("/api/image-models")
    def image_models(refresh: bool = False) -> dict:
        return openrouter.list_image_models(force=refresh)

    @app.get("/api/mock-image")
    def mock_image() -> Response:
        if not get_settings().mock:
            raise HTTPException(status_code=404, detail="only available with LAB_MOCK=1")
        buf = io.BytesIO()
        mock.make_fixture().image.save(buf, "PNG")
        return Response(buf.getvalue(), media_type="image/png")

    @app.post("/api/jobs")
    async def create_job(
        request: Request,
        file: UploadFile = File(...),
        target_lang: str = Form("en"),
        mode_a: bool = Form(False),
        mode_b: bool = Form(False),
        image_models: list[str] = Form(default_factory=list),
        image_model: str = Form(""),
        output_size: str = Form(""),
        verify: bool = Form(True),
        verify_details: bool = Form(False),
    ) -> JSONResponse:
        settings = get_settings()
        if target_lang not in LANGUAGES:
            raise HTTPException(400, f"target_lang must be one of {', '.join(LANGUAGES)}")
        if not (mode_a or mode_b):
            raise HTTPException(400, "select at least one of Mode A and Mode B")
        if (file.content_type or "").lower() not in ALLOWED_CONTENT_TYPES:
            raise HTTPException(415, "only image/jpeg, image/png and image/webp uploads are accepted")
        models = parse_models(image_models, image_model) or [settings.openrouter_image_model]
        if len(models) > MAX_IMAGE_MODELS:
            raise HTTPException(400, f"select at most {MAX_IMAGE_MODELS} image models")
        if not all(MODEL_RE.match(m) for m in models):
            raise HTTPException(400, "invalid image model id")
        if output_size not in OUTPUT_SIZES:
            raise HTTPException(400, f"output_size must be one of: default, {', '.join(s for s in OUTPUT_SIZES if s)}")

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
            image = await run_in_threadpool(load_image, b"".join(chunks))
        except ImageError as exc:
            raise HTTPException(415, str(exc)) from exc

        job_id = jobs.start_job(
            request.app.state.store, image, target_lang, mode_a, mode_b, models, verify, verify_details, output_size
        )
        return JSONResponse({"job_id": job_id})

    @app.get("/api/jobs/{job_id}")
    def job_status(job_id: str, request: Request) -> dict:
        if not jobs.JOB_ID_RE.match(job_id):
            raise HTTPException(404, "unknown job")
        job = request.app.state.store.snapshot(job_id) or jobs.load_persisted(get_settings().data_dir, job_id)
        if job is None:
            raise HTTPException(404, "unknown job")
        return job

    @app.get("/files/{job_id}/{name}")
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

    return app


app = create_app()
