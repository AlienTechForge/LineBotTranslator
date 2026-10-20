"""Publish a finished job to the owner's MinIO so that a reviewer can fetch it by URL.

After a job completes, a background thread uploads

* the original as it was sent,
* every result image (each attempt of Mode A, each attempt of each Mode B model and run),
* the units JSON of every Mode A attempt and one verification JSON (with the judge's record) per
  attempt of either mode,
* ``manifest.json``: run metadata plus a presigned GET URL for every object,

under ``<MINIO_PREFIX>/<job-id>/<name>`` in ``MINIO_BUCKET_NAME``. The page shows the
manifest's presigned URL as the link to hand to a reviewer.

A batch (up to four images submitted together) adds one more object once every job of it has
published: ``<MINIO_PREFIX>/batch-<batch-id>/manifest.json`` with the presigned URL of every job
manifest, the batch options and the summary rows of the page (`publish_batch`).

Everything is optional and must never fail the job: without credentials publishing is skipped
with a note, and every failure comes back as data for the job's warnings. With LAB_MOCK=1 an
in-memory fake with the same two SDK methods is used, so the manifest is still produced.

The variable names are the production bot's, so the values can be copied from its container
(`docker inspect linebot-translator | grep -E '^MINIO_'`).
"""

from __future__ import annotations

import io
import json
import logging
import os
import re
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlparse

from . import batch
from .config import MinioSettings, get_minio_settings, get_settings
from .redaction import describe_error

log = logging.getLogger("lab.publish")

MANIFEST_NAME = "manifest.json"
SCHEMA_VERSION = 3  # 3: judge verdicts (numbers, rows, garbled, partial, score), Mode A attempts, cost split, batch manifest
# (2: mode_b[] is one entry per model with runs[] and a summary; 1: one entry per model run)
CONTENT_TYPES = {
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".webp": "image/webp",
    ".json": "application/json",
}
_IPV4_RE = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")


# ---------------------------------------------------------------- in-memory fake (LAB_MOCK=1, tests)


class FakeMinio:
    """The two `minio.Minio` methods the lab uses, backed by a dict (the oldest objects are dropped
    beyond `max_objects`, so a long LAB_MOCK=1 session does not grow without bound)."""

    def __init__(self, host: str = "mock-minio.invalid", max_objects: int = 500) -> None:
        self.host = host
        self.max_objects = max_objects
        self.objects: dict[tuple[str, str], dict] = {}
        self._lock = threading.Lock()

    def put_object(self, bucket_name, object_name, data, length, content_type="application/octet-stream", **_):
        payload = data.read(length) if hasattr(data, "read") else bytes(data)
        with self._lock:
            self.objects.pop((bucket_name, object_name), None)
            self.objects[(bucket_name, object_name)] = {"data": payload, "content_type": content_type}
            while len(self.objects) > self.max_objects:
                del self.objects[next(iter(self.objects))]

    def presigned_get_object(self, bucket_name, object_name, expires=timedelta(days=7), **_):
        seconds = int(expires.total_seconds())
        return f"https://{self.host}/{bucket_name}/{object_name}?X-Amz-Expires={seconds}&X-Amz-Signature=mock"

    def keys(self, bucket_name: str | None = None) -> list[str]:
        with self._lock:
            return sorted(k for b, k in self.objects if bucket_name in (None, b))


_MOCK_CLIENT = FakeMinio()


def mock_client() -> FakeMinio:
    """The process-wide fake used when LAB_MOCK=1 (tests read what was 'uploaded' from it)."""
    return _MOCK_CLIENT


# ---------------------------------------------------------------- real clients


def _host_looks_internal(host: str) -> bool:
    name = host.rsplit(":", 1)[0] if not host.startswith("[") else host
    return name == "localhost" or name.startswith("127.") or "." not in name or bool(_IPV4_RE.match(name))


def endpoint_parts(value: str) -> tuple[str, bool]:
    """(host[:port], secure) from 'https://s3.example.com', 'http://minio:9000' or a bare host.

    A scheme is obeyed. Without one the endpoint is plain http when it looks internal
    (localhost, a docker service name without a dot, an IP address) and https otherwise.
    """
    value = value.strip()
    if "://" in value:
        parsed = urlparse(value)
        return parsed.netloc, parsed.scheme.lower() == "https"
    host = value.strip("/")
    return host, not _host_looks_internal(host)


def _build_client(endpoint: str, settings: MinioSettings):
    import certifi
    import urllib3
    from minio import Minio  # imported lazily: mock mode and the unit tests need no SDK

    host, secure = endpoint_parts(endpoint)
    http = urllib3.PoolManager(
        timeout=urllib3.Timeout(connect=10.0, read=120.0),
        maxsize=4,
        cert_reqs="CERT_REQUIRED",
        ca_certs=os.environ.get("SSL_CERT_FILE") or certifi.where(),
        retries=urllib3.Retry(total=3, backoff_factor=0.5, status_forcelist=[500, 502, 503, 504]),
    )
    # an explicit region keeps presigning offline (no bucket-location round trip)
    return Minio(
        host,
        access_key=settings.access_key,
        secret_key=settings.secret_key,
        secure=secure,
        region=settings.region,
        http_client=http,
    )


def make_clients(settings: MinioSettings, mock: bool) -> tuple[object, object, list[str]]:
    """(uploader, signer, notes). The signer uses MINIO_PUBLIC_ENDPOINT, the host the reviewer reaches."""
    if mock:
        return _MOCK_CLIENT, _MOCK_CLIENT, []
    notes: list[str] = []
    uploader = _build_client(settings.endpoint, settings)
    if settings.public_endpoint and settings.public_endpoint.strip() != settings.endpoint.strip():
        signer = _build_client(settings.public_endpoint, settings)
    else:
        signer = uploader
    if not settings.public_endpoint:
        host, secure = endpoint_parts(settings.endpoint)
        if not secure or _host_looks_internal(host):
            notes.append(
                "MINIO_PUBLIC_ENDPOINT is not set, so the links are signed for MINIO_ENDPOINT "
                f"({host}); a reviewer outside your network may not reach them"
            )
    return uploader, signer, notes


# ---------------------------------------------------------------- what to upload


def _iso(epoch: float | None) -> str | None:
    if epoch is None:
        return None
    return datetime.fromtimestamp(epoch, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _is_s3_error(exc: Exception) -> bool:
    """True for an answer from the object store (access denied, no such bucket ...), not a dead connection."""
    try:
        from minio.error import S3Error
    except ImportError:  # pragma: no cover - the SDK is a pinned requirement
        return False
    return isinstance(exc, S3Error)


def _content_type(name: str) -> str:
    return CONTENT_TYPES.get(Path(name).suffix.lower(), "application/octet-stream")


def collect_files(job: dict, job_dir: Path) -> list[str]:
    """Names (relative to the job folder) of every file to publish, in upload order."""
    names: list[str] = []

    def add(name: str | None) -> None:
        if name and name not in names and (job_dir / name).is_file():
            names.append(name)

    add((job.get("image") or {}).get("url", "").rsplit("/", 1)[-1])
    mode_a = job.get("mode_a") or {}
    if mode_a.get("status") == "done":
        for attempt in mode_a.get("attempts") or []:
            add(attempt.get("output"))
            add(attempt.get("erased"))
            add(attempt.get("units_file"))
            add(attempt.get("verification_file"))
    for result in (job.get("mode_b") or {}).get("results") or []:
        for attempt in result.get("tries") or []:
            add(attempt.get("output"))
            add(attempt.get("verification_file"))
    return names


def object_key(settings: MinioSettings, job_id: str, name: str) -> str:
    """`<prefix>/<job-id>/<name>`."""
    return "/".join(p for p in (settings.prefix, job_id, name) if p)


# ---------------------------------------------------------------- the manifest


def _judge_entry(judged: dict, fallback_status=None) -> dict:
    return {
        "status": judged.get("status") or fallback_status,
        "error": judged.get("error"),
        "model": judged.get("served_model") or judged.get("model"),
        "usability": judged.get("usability"),
        "coverage": judged.get("coverage"),
        "legibility_issues": judged.get("legibility_issues"),
        "structure_issues": judged.get("structure_issues"),
        "altered_numbers": judged.get("altered_numbers"),
        "untranslated": judged.get("untranslated"),
        "mistranslated": judged.get("mistranslated"),
        "summary": judged.get("summary"),
        "input_tokens": judged.get("input_tokens"),
        "output_tokens": judged.get("output_tokens"),
        "cost_usd": judged.get("cost_usd"),
    }


def _warnings_entry(v: dict) -> dict:
    w = v.get("ocr_warnings") or {}
    return {
        "status": w.get("status"),
        "error": w.get("error"),
        "warnings": w.get("warnings"),
        "missing_digit_runs": w.get("missing_digit_runs"),
        "unverified_digit_runs": w.get("unverified_digit_runs"),
        "leftover_ratio": w.get("leftover_ratio"),
        "leftover_words": w.get("leftover_words"),
        "big_leftover_words": w.get("big_leftover_words"),
    }


def _attempt_entry(t: dict, ref) -> dict:
    v = t.get("verification") or {}
    return {
        "attempt": t.get("n"),
        "selected": bool(t.get("selected")),
        "image": ref(t.get("output")),
        "verification_file": ref(t.get("verification_file")),
        "verdict": t.get("verdict"),
        "failed_checks": v.get("failed_checks"),
        "usability": v.get("usability"),
        "coverage": v.get("coverage"),
        "judge": _judge_entry(v.get("judge") or {}, t.get("judge_status")),
        "ocr_warnings": _warnings_entry(v),
        "verify_error": t.get("verify_error"),
        "prompt": t.get("prompt"),
        "api_used": t.get("api_used"),
        "output_size": t.get("output_size"),
        "aspect_drift_pct": t.get("aspect_drift_pct"),
        "resolution_applied": t.get("resolution_applied"),
        "generation_ms": t.get("elapsed_ms"),
        "verify_ms": t.get("verify_ms"),
        "judge_ms": t.get("judge_ms"),
        "usage": t.get("usage"),
        "cost_usd": t.get("cost_usd"),
        "judge_cost_usd": t.get("judge_cost_usd"),
    }


def _run_entry(result: dict, ref) -> dict:
    """One run of one model: its outcome and every attempt (the old per-model entry of schema 1)."""
    return {
        "run": result.get("run") or 1,
        "status": result.get("status"),
        "error": result.get("error"),
        "api_used": result.get("api_used"),
        "resolution_applied": result.get("resolution_applied"),
        "verdict": result.get("verdict"),
        "failed_checks": (result.get("verification") or {}).get("failed_checks"),
        "usability": result.get("usability"),
        "coverage": result.get("coverage"),
        "selected_attempt": result.get("selected_attempt"),
        "retry_error": result.get("retry_error"),
        "output_size": result.get("output_size"),
        "aspect_drift_pct": result.get("aspect_drift_pct"),
        "elapsed_ms": result.get("elapsed_ms"),
        "judge_ms": result.get("judge_ms"),
        "usage": result.get("usage"),
        "cost_usd": result.get("cost_usd"),
        "judge_cost_usd": result.get("judge_cost_usd"),
        "cost_openrouter_usd": result.get("cost_openrouter_usd"),
        "cost_anthropic_usd": result.get("cost_anthropic_usd"),
        "total_cost_usd": result.get("total_cost_usd"),
        "image": ref(result.get("output")),
        "attempts": [_attempt_entry(t, ref) for t in result.get("tries") or []],
    }


def _mode_a_attempt_entry(t: dict, ref) -> dict:
    v = t.get("verification") or {}
    units = t.get("units") or []
    return {
        "attempt": t.get("n"),
        "selected": bool(t.get("selected")),
        "image": ref(t.get("output")),
        "erased": ref(t.get("erased")),
        "units": ref(t.get("units_file")),
        "verification_file": ref(t.get("verification_file")),
        "verdict": t.get("verdict"),
        "failed_checks": v.get("failed_checks"),
        "usability": v.get("usability"),
        "coverage": v.get("coverage"),
        "judge": _judge_entry(v.get("judge") or {}, t.get("judge_status")),
        "ocr_warnings": _warnings_entry(v),
        "verify_error": t.get("verify_error"),
        "retry_feedback": t.get("retry_feedback"),
        "elapsed_ms": t.get("elapsed_ms"),
        "claude_ms": t.get("claude_ms"),
        "erase_ms": t.get("erase_ms"),
        "render_ms": t.get("render_ms"),
        "verify_ms": t.get("verify_ms"),
        "judge_ms": t.get("judge_ms"),
        "tokens": t.get("tokens"),
        "cost_usd": t.get("cost_usd"),
        "judge_cost_usd": t.get("judge_cost_usd"),
        "unit_count": t.get("unit_count"),
        "translated_count": t.get("translated_count"),
        "erase_methods": {m: sum(1 for u in units if u.get("erase_method") == m) for m in ("flat", "inpaint")},
        "text_used": {m: sum(1 for u in units if u.get("text_used") == m) for m in ("full", "short")},
        "skipped_too_small": sum(1 for u in units if u.get("skip_reason") == "too small"),
        "warnings": t.get("warnings") or [],
    }


def build_manifest(
    job: dict, files: dict[str, dict], settings: MinioSettings, manifest_ref: dict, published_at: float
) -> dict:
    """The manifest dict. `files` maps a name to {key, url, bytes, content_type} (or {error})."""

    def ref(name: str | None) -> dict | None:
        if not name or name not in files:
            return None
        return {"name": name, **files[name]}

    params = job.get("params") or {}
    expires_at = published_at + settings.expiry_hours * 3600
    mode_a = job.get("mode_a") or {}
    manifest: dict = {
        "schema_version": SCHEMA_VERSION,
        "job_id": job["job_id"],
        "batch_id": job.get("batch_id"),
        "image_index": job.get("index"),
        "created_at": _iso(job.get("created_at")),
        "finished_at": _iso(job.get("finished_at")),
        "published_at": _iso(published_at),
        "urls_expire_at": _iso(expires_at),
        "url_expiry_hours": settings.expiry_hours,
        "mock": bool(params.get("mock")),
        "target_language": params.get("target_lang"),
        "options": {
            "mode_a": bool(params.get("mode_a")),
            "mode_b": bool(params.get("mode_b")),
            "image_models": params.get("image_models") or [],
            "output_size": params.get("output_size") or "default",
            "verify": params.get("verify"),
            "verify_details": params.get("verify_details"),
            "runs": params.get("runs") or 1,
            "judge": params.get("judge"),
            "judge_model": params.get("judge_model"),
            "claude_model": params.get("claude_model"),
            "mode_a_retry": params.get("mode_a_retry"),
        },
        "bucket": settings.bucket,
        "prefix": "/".join(p for p in (settings.prefix, job["job_id"]) if p),
        "manifest": manifest_ref,
        "original": {
            "width": (job.get("image") or {}).get("width"),
            "height": (job.get("image") or {}).get("height"),
            "file": ref((job.get("image") or {}).get("url", "").rsplit("/", 1)[-1]),
        },
        "mode_a": None,
        "mode_b": [],
    }

    if mode_a.get("status") not in (None, "skipped"):
        manifest["mode_a"] = {
            "status": mode_a.get("status"),
            "error": mode_a.get("error"),
            "model": mode_a.get("model"),
            "served_model": mode_a.get("served_model"),
            "elapsed_ms": mode_a.get("elapsed_ms"),
            "vision_ms": mode_a.get("vision_ms"),
            "claude_ms": mode_a.get("claude_ms"),
            "tokens": mode_a.get("tokens"),
            "cost_usd": mode_a.get("cost_usd"),
            "judge_cost_usd": mode_a.get("judge_cost_usd"),
            "total_cost_usd": mode_a.get("total_cost_usd"),
            "cost_anthropic_usd": mode_a.get("cost_anthropic_usd"),
            "cost_openrouter_usd": mode_a.get("cost_openrouter_usd"),
            "word_count": mode_a.get("word_count"),
            "unit_count": mode_a.get("unit_count"),
            "translated_count": mode_a.get("translated_count"),
            "verdict": mode_a.get("verdict"),
            "failed_checks": (mode_a.get("verification") or {}).get("failed_checks"),
            "usability": mode_a.get("usability"),
            "coverage": mode_a.get("coverage"),
            "retry": mode_a.get("retry"),
            "retry_error": mode_a.get("retry_error"),
            "selected_attempt": mode_a.get("selected_attempt"),
            "image": ref(mode_a.get("output")),
            "units": ref(mode_a.get("units_file")),
            "attempts": [_mode_a_attempt_entry(t, ref) for t in mode_a.get("attempts") or []],
            "warnings": mode_a.get("warnings") or [],
        }

    results = (job.get("mode_b") or {}).get("results") or []
    summaries = (job.get("mode_b") or {}).get("summary") or batch.summarise_runs(results)
    by_model: dict = {}
    for result in results:
        by_model.setdefault(result.get("model_index", result.get("model")), []).append(result)
    for key, runs in by_model.items():
        entries = [_run_entry(result, ref) for result in sorted(runs, key=lambda r: r.get("run") or 1)]
        summary = next(
            (s for s in summaries if s.get("model_index", s.get("model")) == key), None
        ) or batch.summarise_runs(runs)[0]
        summary = {**summary, "best_image": ref(summary.get("best_output"))}
        first = runs[0]
        manifest["mode_b"].append(
            {
                "model": first.get("model"),
                "route_preference": first.get("route_preference"),
                "resolution_requested": first.get("resolution_requested") or "default",
                "runs_requested": first.get("runs") or len(runs),
                "summary": summary,
                "runs": entries,
            }
        )

    manifest["objects"] = [{"name": n, **info} for n, info in files.items()]
    manifest["warnings"] = list(job.get("warnings") or []) + [
        f"upload failed for {n}: {info['error']}" for n, info in files.items() if "error" in info
    ]
    return manifest


# ---------------------------------------------------------------- the whole upload


def publish_job(job: dict, job_dir: Path, now: float | None = None) -> dict:
    """Upload the job and return {status: done | skipped | error, note, manifest_url, ...}.

    Never raises: the caller turns the outcome into the job's `publish` block and warnings.
    """
    settings = get_minio_settings()
    mock = get_settings().mock
    if not mock and not settings.configured:
        log.info("stage=publish job=%s skipped: %s not set", job.get("job_id"), ", ".join(settings.missing()))
        return {
            "status": "skipped",
            "note": "未發佈到 MinIO：缺少 " + "、".join(settings.missing()) + "（見 README「給審閱者的連結」）",
        }
    job_id = job["job_id"]
    try:
        published_at = now if now is not None else time.time()
        uploader, signer, notes = make_clients(settings, mock)
        expires = timedelta(hours=settings.expiry_hours)

        files: dict[str, dict] = {}
        for name in collect_files(job, job_dir):
            key = object_key(settings, job_id, name)
            try:
                data = (job_dir / name).read_bytes()
                ctype = _content_type(name)
                uploader.put_object(settings.bucket, key, io.BytesIO(data), len(data), content_type=ctype)
                files[name] = {
                    "key": key,
                    "bytes": len(data),
                    "content_type": ctype,
                    "url": signer.presigned_get_object(settings.bucket, key, expires=expires),
                }
            except Exception as exc:  # noqa: BLE001 - one failed object must not stop the others
                if not any("url" in info for info in files.values()) and not _is_s3_error(exc):
                    raise  # nothing went up and the store does not even answer: report once, not per object
                files[name] = {"key": key, "error": describe_error(exc)}

        manifest_key = object_key(settings, job_id, MANIFEST_NAME)
        manifest_url = signer.presigned_get_object(settings.bucket, manifest_key, expires=expires)
        manifest_ref = {"name": MANIFEST_NAME, "key": manifest_key, "url": manifest_url}
        manifest = build_manifest(job, files, settings, manifest_ref, published_at)
        body = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
        uploader.put_object(settings.bucket, manifest_key, io.BytesIO(body), len(body), content_type="application/json")
        try:
            (job_dir / MANIFEST_NAME).write_bytes(body)  # a local copy, served at /files/<job>/manifest.json
        except OSError:
            log.warning("could not keep a local copy of the manifest for %s", job_id)

        failed = [f"{n}: {info['error']}" for n, info in files.items() if "error" in info]
        log.info(  # no URLs here: a presigned link carries the access key id and the signature
            "stage=publish job=%s uploaded=%d failed=%d bucket=%s",
            job_id, len(files) - len(failed) + 1, len(failed), settings.bucket,
        )  # fmt: skip
        return {
            "status": "done",
            "manifest_url": manifest_url,
            "manifest_key": manifest_key,
            "bucket": settings.bucket,
            "expiry_hours": settings.expiry_hours,
            "expires_at": _iso(published_at + settings.expiry_hours * 3600),
            "objects": len(files) - len(failed) + 1,
            "failed": failed,
            "notes": notes,
            "mock": mock,
            "note": "LAB_MOCK=1：只寫進記憶體，這個連結打不開" if mock else None,
        }
    except Exception as exc:  # noqa: BLE001 - publishing must never fail the job
        log.warning("stage=publish job=%s failed: %s", job_id, type(exc).__name__)
        return {"status": "error", "error": describe_error(exc)}


# ---------------------------------------------------------------- the batch manifest


def batch_key(settings: MinioSettings, batch_id: str) -> str:
    """`<prefix>/batch-<batch-id>/manifest.json`."""
    return "/".join(p for p in (settings.prefix, f"batch-{batch_id}", MANIFEST_NAME) if p)


def build_batch_manifest(batch_doc: dict, settings: MinioSettings, manifest_ref: dict, published_at: float) -> dict:
    """The batch manifest (schema 3): every job manifest's presigned URL, the options and the summary rows."""
    params = batch_doc.get("params") or {}
    warnings: list[str] = list(batch_doc.get("warnings") or [])
    jobs = []
    for job in batch_doc.get("jobs") or []:
        pub = job.get("publish") or {}
        image = job.get("image") or {}
        link = None
        if pub.get("status") == "done" and pub.get("manifest_url"):
            link = {
                "name": MANIFEST_NAME,
                "key": pub.get("manifest_key"),
                "url": pub.get("manifest_url"),
                "expires_at": pub.get("expires_at"),
            }
        else:
            reason = pub.get("note") or pub.get("error") or pub.get("status")
            warnings.append(f"image {job.get('index')}: no job manifest ({reason})")
        jobs.append(
            {
                "index": job.get("index"),
                "job_id": job.get("job_id"),
                "image_name": image.get("name") or "",
                "width": image.get("width"),
                "height": image.get("height"),
                "status": job.get("status"),
                "publish_status": pub.get("status"),
                "manifest": link,
            }
        )
    summary = batch_doc.get("summary") or batch.summarise_batch(batch_doc.get("jobs") or [])
    return {
        "schema_version": SCHEMA_VERSION,
        "kind": "batch",
        "batch_id": batch_doc.get("batch_id"),
        "created_at": _iso(batch_doc.get("created_at")),
        "finished_at": _iso(batch_doc.get("finished_at")),
        "published_at": _iso(published_at),
        "urls_expire_at": _iso(published_at + settings.expiry_hours * 3600),
        "url_expiry_hours": settings.expiry_hours,
        "mock": bool(params.get("mock")),
        "target_language": params.get("target_lang"),
        "options": {
            "mode_a": bool(params.get("mode_a")),
            "mode_b": bool(params.get("mode_b")),
            "image_models": params.get("image_models") or [],
            "output_size": params.get("output_size") or "default",
            "verify": params.get("verify"),
            "verify_details": params.get("verify_details"),
            "runs": params.get("runs") or 1,
            "judge": params.get("judge"),
            "judge_model": params.get("judge_model"),
            "claude_model": params.get("claude_model"),
            "mode_a_retry": params.get("mode_a_retry"),
        },
        "bucket": settings.bucket,
        "prefix": "/".join(p for p in (settings.prefix, f"batch-{batch_doc.get('batch_id')}") if p),
        "manifest": manifest_ref,
        "jobs": jobs,
        "summary": summary,
        "totals": batch_doc.get("totals") or batch.batch_totals(summary),
        "warnings": warnings,
    }


def publish_batch(batch_doc: dict, batch_dir: Path | None, now: float | None = None) -> dict:
    """Upload the batch manifest and return {status: done | skipped | error, manifest_url, ...}.

    Called once every job of the batch has published. Never raises and never fails the batch: the
    caller turns the outcome into the batch's `publish` block and warnings.
    """
    settings = get_minio_settings()
    mock = get_settings().mock
    batch_id = batch_doc.get("batch_id")
    if not mock and not settings.configured:
        log.info("stage=publish batch=%s skipped: %s not set", batch_id, ", ".join(settings.missing()))
        return {
            "status": "skipped",
            "note": "未發佈批次 manifest 到 MinIO：缺少 " + "、".join(settings.missing()) + "（見 README「給審閱者的連結」）",
        }
    try:
        published_at = now if now is not None else time.time()
        uploader, signer, notes = make_clients(settings, mock)
        expires = timedelta(hours=settings.expiry_hours)
        key = batch_key(settings, str(batch_id))
        url = signer.presigned_get_object(settings.bucket, key, expires=expires)
        manifest = build_batch_manifest(
            batch_doc, settings, {"name": MANIFEST_NAME, "key": key, "url": url}, published_at
        )
        body = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
        uploader.put_object(settings.bucket, key, io.BytesIO(body), len(body), content_type="application/json")
        if batch_dir is not None:
            try:
                (batch_dir / MANIFEST_NAME).write_bytes(body)  # a local copy, served at /api/batches/<id>/manifest
            except OSError:
                log.warning("could not keep a local copy of the batch manifest for %s", batch_id)
        linked = sum(1 for j in manifest["jobs"] if j["manifest"])
        log.info(  # no URLs here: a presigned link carries the access key id and the signature
            "stage=publish batch=%s uploaded=1 jobs_linked=%d bucket=%s", batch_id, linked, settings.bucket
        )
        return {
            "status": "done",
            "manifest_url": url,
            "manifest_key": key,
            "bucket": settings.bucket,
            "expiry_hours": settings.expiry_hours,
            "expires_at": _iso(published_at + settings.expiry_hours * 3600),
            "jobs_linked": linked,
            "warnings": [w for w in manifest["warnings"] if w not in (batch_doc.get("warnings") or [])],
            "notes": notes,
            "mock": mock,
            "note": "LAB_MOCK=1：只寫進記憶體，這個連結打不開" if mock else None,
        }
    except Exception as exc:  # noqa: BLE001 - publishing must never fail the batch
        log.warning("stage=publish batch=%s failed: %s", batch_id, type(exc).__name__)
        return {"status": "error", "error": describe_error(exc)}
