"""Background jobs: run Mode A and Mode B side by side, keep the result in memory and on disk."""

from __future__ import annotations

import json
import logging
import os
import re
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from . import openrouter, pipeline_a, vision
from .config import get_settings
from .imaging import LoadedImage

log = logging.getLogger("lab.jobs")

JOB_ID_RE = re.compile(r"^[0-9a-f]{16}$")
SECRET_ENV = (
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_AUTH_TOKEN",
    "OPENROUTER_API_KEY",
    "OPEN_ROUTE_API_KEY",
    "LAB_TOKEN",
)


def redact(message: str) -> str:
    """Never let a credential travel into the UI or the result file."""
    for name in SECRET_ENV:
        value = os.environ.get(name, "")
        if len(value) >= 8:
            message = message.replace(value, "***")
    return message


def describe_error(exc: BaseException) -> str:
    from .models import ModeError

    text = str(exc) if isinstance(exc, ModeError) else f"{type(exc).__name__}: {exc}"
    return redact(text)[:2000]


class JobStore:
    def __init__(self) -> None:
        self._jobs: dict[str, dict] = {}
        self._lock = threading.Lock()

    def put(self, job: dict) -> None:
        with self._lock:
            self._jobs[job["job_id"]] = job

    def snapshot(self, job_id: str) -> dict | None:
        with self._lock:
            job = self._jobs.get(job_id)
            return json.loads(json.dumps(job)) if job else None

    def update_mode(self, job_id: str, mode: str, data: dict, job_dir: Path) -> None:
        with self._lock:
            job = self._jobs[job_id]
            job[mode] = data
            running = any(job[m]["status"] in ("pending", "running") for m in ("mode_a", "mode_b"))
            job["status"] = "running" if running else "done"
            if not running:
                job["finished_at"] = time.time()
            payload = json.dumps(job, ensure_ascii=False)
        try:
            (job_dir / "result.json").write_text(payload, encoding="utf-8")
        except OSError:
            log.warning("could not persist result.json for %s", job_id)


def load_persisted(data_dir: Path, job_id: str) -> dict | None:
    path = data_dir / job_id / "result.json"
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def _mode_a(store: JobStore, job_id: str, image: LoadedImage, lang: str, job_dir: Path) -> None:
    store.update_mode(job_id, "mode_a", {"status": "running", "error": None}, job_dir)
    try:
        result = pipeline_a.run_mode_a(image, lang, job_dir)
        result.update(status="done", error=None)
    except Exception as exc:  # noqa: BLE001 - shown in Mode A's error box only
        log.exception("mode A failed for job %s", job_id)
        result = {"status": "error", "error": describe_error(exc)}
    store.update_mode(job_id, "mode_a", result, job_dir)


def _mode_b(
    store: JobStore, job_id: str, image: LoadedImage, lang: str, model: str, verify: bool, job_dir: Path
) -> None:
    store.update_mode(job_id, "mode_b", {"status": "running", "error": None, "model": model}, job_dir)
    try:
        res = openrouter.run_image_model(image, lang, model)
        (job_dir / "B.png").write_bytes(res["png"])
        usage = res["usage"] or {}
        in_ratio = image.width / image.height
        out_ratio = res["size"][0] / res["size"][1]
        result = {
            "status": "done",
            "error": None,
            "elapsed_ms": res["elapsed_ms"],
            "model": model,
            "api_used": res["api_used"],
            "attempts": res["attempts"],
            "usage": usage,
            "cost_usd": usage.get("cost"),
            "output": "B.png",
            "output_size": res["size"],
            "aspect_drift_pct": round(abs(out_ratio / in_ratio - 1.0) * 100, 1),
            "model_text": res["model_text"],
            "verify": verify,
            "verify_text": None,
            "verify_ms": None,
            "verify_error": None,
        }
        # publish the picture first; the OCR check can take a few more seconds
        store.update_mode(job_id, "mode_b", dict(result, status="running"), job_dir)
        if verify:
            t0 = time.perf_counter()
            try:
                result["verify_text"] = vision.ocr_text(res["png"])
            except Exception as exc:  # noqa: BLE001
                result["verify_error"] = describe_error(exc)
            result["verify_ms"] = int((time.perf_counter() - t0) * 1000)
    except Exception as exc:  # noqa: BLE001 - shown in Mode B's error box only
        log.exception("mode B failed for job %s", job_id)
        result = {"status": "error", "error": describe_error(exc), "model": model}
    store.update_mode(job_id, "mode_b", result, job_dir)


def start_job(
    store: JobStore,
    image: LoadedImage,
    lang: str,
    run_a: bool,
    run_b: bool,
    model: str,
    verify: bool,
) -> str:
    settings = get_settings()
    job_id = uuid.uuid4().hex[:16]
    job_dir = settings.data_dir / job_id
    job_dir.mkdir(parents=True, exist_ok=True)
    original = f"original.{image.ext}"
    (job_dir / original).write_bytes(image.data)

    pending = {"status": "pending", "error": None}
    skipped = {"status": "skipped", "error": None}
    job = {
        "job_id": job_id,
        "status": "running",
        "created_at": time.time(),
        "params": {
            "target_lang": lang,
            "mode_a": run_a,
            "mode_b": run_b,
            "image_model": model,
            "verify": verify,
            "claude_model": settings.claude_model,
            "mock": settings.mock,
        },
        "image": {"url": f"files/{job_id}/{original}", "width": image.width, "height": image.height},
        "mode_a": dict(pending if run_a else skipped),
        "mode_b": dict(pending if run_b else skipped),
    }
    store.put(job)
    log.info("job=%s started lang=%s a=%s b=%s image=%dx%d", job_id, lang, run_a, run_b, image.width, image.height)

    def runner() -> None:
        with ThreadPoolExecutor(max_workers=2, thread_name_prefix=f"job-{job_id}") as pool:
            if run_a:
                pool.submit(_mode_a, store, job_id, image, lang, job_dir)
            if run_b:
                pool.submit(_mode_b, store, job_id, image, lang, model, verify, job_dir)

    threading.Thread(target=runner, name=f"job-{job_id}", daemon=True).start()
    return job_id
