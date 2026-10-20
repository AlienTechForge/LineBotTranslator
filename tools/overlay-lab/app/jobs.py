"""Background jobs: Mode A (optional) next to up to four Mode B models, then publish the result.

One job = one uploaded image. Every Mode B model runs `runs` times (1-3, batch mode); every
(model, run) pair is a unit of its own in its own thread:

    generate (attempt 1) -> Vision OCR of the output -> semantic judge -> verify.verify_output
        -> when the verdict is not `pass`: ONE retry (prompt + what went wrong) -> verify again
        -> verify.choose_attempt picks the attempt that is shown as the result

Units are independent (own attempts, retry, verification and judge). Only the image GENERATIONS
are throttled: at most MAX_CONCURRENT_GENERATIONS of them are in flight at once, across all jobs,
so that a 4-model x 3-run batch does not hit OpenRouter's rate limits; verification and judging
are not throttled. `batch.summarise_runs` folds the runs of each model into a pass rate and means.

The Vision call on the SOURCE image is made once per job (SourceOcr) and shared by Mode A and
by the verification of every Mode B unit.
"""

from __future__ import annotations

import copy
import json
import logging
import re
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from . import batch, judge, openrouter, pipeline_a, publish, verify, vision
from .config import MAX_CONCURRENT_GENERATIONS, MAX_RUNS, get_settings
from .imaging import LoadedImage, bytes_for_vision
from .redaction import SECRET_ENV, describe_error, redact  # noqa: F401 - re-exported

log = logging.getLogger("lab.jobs")

JOB_ID_RE = re.compile(r"^[0-9a-f]{16}$")
LIVE = ("pending", "running")

# one slot per image generation in flight (see the module docstring)
_GENERATION_SLOTS = threading.BoundedSemaphore(MAX_CONCURRENT_GENERATIONS)


class SourceOcr:
    """Vision on the source image, called at most once per job (Mode A and Mode B share it)."""

    def __init__(self, image: LoadedImage) -> None:
        self._image = image
        self._lock = threading.Lock()
        self._done = False
        self._result = None
        self._error: BaseException | None = None
        self.ms: int | None = None
        self.calls = 0

    def get(self):
        with self._lock:
            if not self._done:
                self.calls += 1
                t0 = time.perf_counter()
                try:
                    self._result = vision.ocr_words(self._image.data, self._image.width, self._image.height)
                except Exception as exc:  # noqa: BLE001 - kept and re-raised for every caller
                    self._error = exc
                self.ms = int((time.perf_counter() - t0) * 1000)
                self._done = True
        if self._error is not None:
            raise self._error
        return self._result

    def prefetch(self) -> None:
        """Start the call early (the verification needs it once the first image is back)."""
        try:
            self.get()
        except Exception:  # noqa: BLE001 - every user of get() sees the error itself
            log.debug("source OCR prefetch failed", exc_info=True)


def _refresh(job: dict) -> None:
    """Derive Mode B's overall status and the job status from the parts."""
    results = job["mode_b"]["results"]
    if results:
        states = [r["status"] for r in results]
        if any(s in LIVE for s in states):
            job["mode_b"]["status"] = "running"
        else:
            job["mode_b"]["status"] = "error" if all(s == "error" for s in states) else "done"
    job["mode_b"]["summary"] = batch.summarise_runs(results) if results else []
    parts = [job["mode_a"]["status"]] + [r["status"] for r in results]
    running = any(s in LIVE for s in parts)
    job["status"] = "running" if running else "done"
    if not running and not job.get("finished_at"):
        job["finished_at"] = time.time()


class JobStore:
    def __init__(self) -> None:
        self._jobs: dict[str, dict] = {}
        self._dirs: dict[str, Path] = {}
        self._lock = threading.Lock()

    def put(self, job: dict, job_dir: Path) -> None:
        with self._lock:
            self._jobs[job["job_id"]] = job
            self._dirs[job["job_id"]] = job_dir

    def snapshot(self, job_id: str) -> dict | None:
        with self._lock:
            job = self._jobs.get(job_id)
            return json.loads(json.dumps(job)) if job else None

    def update(self, job_id: str, change) -> None:
        """Apply `change(job)` under the lock, refresh the derived statuses and persist result.json."""
        with self._lock:
            job = self._jobs[job_id]
            change(job)
            _refresh(job)
            payload = json.dumps(job, ensure_ascii=False)
            try:  # inside the lock: concurrent model threads must not write an older state last
                (self._dirs[job_id] / "result.json").write_text(payload, encoding="utf-8")
            except OSError:
                log.warning("could not persist result.json for %s", job_id)

    # the writers keep mutating their own dicts after storing them, so the store gets a copy
    def set_mode_a(self, job_id: str, data: dict) -> None:
        self.update(job_id, lambda job: job.__setitem__("mode_a", copy.deepcopy(data)))

    def set_model(self, job_id: str, index: int, data: dict) -> None:
        self.update(job_id, lambda job: job["mode_b"]["results"].__setitem__(index, copy.deepcopy(data)))


def load_persisted(data_dir: Path, job_id: str) -> dict | None:
    path = data_dir / job_id / "result.json"
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


# ---------------------------------------------------------------- Mode A


def _mode_a(store: JobStore, job_id: str, image: LoadedImage, lang: str, job_dir: Path, source: SourceOcr) -> None:
    store.set_mode_a(job_id, {"status": "running", "error": None})
    try:
        result = pipeline_a.run_mode_a(image, lang, job_dir, source)
        result.update(status="done", error=None)
        try:  # the units as a file of their own, for the published bundle
            doc = {"job_id": job_id, "target_lang": lang, "page": result["page"], "units": result["units"]}
            (job_dir / "A_units.json").write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding="utf-8")
            result["units_file"] = "A_units.json"
        except OSError:
            log.warning("could not write A_units.json for %s", job_id)
    except Exception as exc:  # noqa: BLE001 - shown in Mode A's error box only
        log.exception("mode A failed for job %s", job_id)
        result = {"status": "error", "error": describe_error(exc)}
    store.set_mode_a(job_id, result)


# ---------------------------------------------------------------- Mode B, one model


def model_slug(model: str) -> str:
    return re.sub(r"[^A-Za-z0-9.-]+", "_", model).strip("_")[:36] or "model"


def _sum_usage(tries: list[dict]) -> dict:
    out: dict = {}
    for key in ("prompt_tokens", "completion_tokens", "total_tokens", "cost"):
        values = [t["usage"][key] for t in tries if isinstance((t.get("usage") or {}).get(key), (int, float))]
        if values:
            out[key] = round(sum(values), 6) if key == "cost" else sum(values)
    return out


def _sum_costs(values: list) -> float | None:
    known = [v for v in values if isinstance(v, (int, float))]
    return round(sum(known), 6) if known else None


def summarise_model(result: dict, tries: list[dict]) -> dict:
    """Fold the attempts of one run into its result: the chosen attempt's fields plus summed cost / time.

    `cost_usd` is the image generation (OpenRouter's `usage.cost`), `judge_cost_usd` the judge calls
    (Anthropic, from the price table) and `total_cost_usd` both; `verify_ms` covers the OCR and the
    judge of every attempt, `judge_ms` the judge part of it.
    """
    chosen = verify.choose_attempt([t.get("verification") for t in tries])
    for i, t in enumerate(tries):
        t["selected"] = i == chosen
    main = tries[chosen]
    usage = _sum_usage(tries)
    generation = sum(t["elapsed_ms"] for t in tries)
    checking = sum(t.get("verify_ms") or 0 for t in tries)
    judge_cost = _sum_costs([t.get("judge_cost_usd") for t in tries])
    result.update(
        output=main["output"],
        output_size=main["output_size"],
        aspect_drift_pct=main["aspect_drift_pct"],
        api_used=main["api_used"],
        route_errors=main["route_errors"],
        model_text=main["model_text"],
        resolution_applied=main["resolution_applied"],
        verdict=main["verdict"],
        verification=main["verification"],
        verify_error=main["verify_error"],
        selected_attempt=main["n"],
        usage=usage,
        cost_usd=usage.get("cost"),
        judge_cost_usd=judge_cost,
        total_cost_usd=_sum_costs([usage.get("cost"), judge_cost]),
        generation_ms=generation,
        verify_ms=checking,
        judge_ms=sum(t.get("judge_ms") or 0 for t in tries),
        elapsed_ms=generation + checking,
        tries=tries,
    )
    return result


def _mode_b_run(
    store: JobStore,
    job_id: str,
    index: int,
    model_index: int,
    run: int,
    image: LoadedImage,
    lang: str,
    model: str,
    opts: dict,
    source: SourceOcr,
    job_dir: Path,
) -> None:
    """One independent run of one model: its attempts, retry, verification and judge.

    `index` is the position in the job's flat result list, `model_index` the position of the model,
    `run` the 1-based run number (they name the files: B<model>_<slug>_r<run>_a<attempt>).
    """
    slug = model_slug(model)
    ident = {"model": model, "index": index, "model_index": model_index, "run": run, "runs": opts["runs"]}
    result: dict = {
        **ident,
        "status": "running",
        "error": None,
        "route_preference": openrouter.preferred_route(model),
        "resolution_requested": opts["output_size"],
        "verify": opts["verify"],
        "judge": opts["judge"],
        "retry_error": None,
        "tries": [],
    }
    store.set_model(job_id, index, dict(result))
    in_ratio = image.width / image.height

    def generate(n: int, prompt: str | None) -> tuple[dict, bytes]:
        with _GENERATION_SLOTS:  # at most MAX_CONCURRENT_GENERATIONS generations in flight
            res = openrouter.run_image_model(image, lang, model, prompt=prompt, resolution=opts["output_size"])
        name = f"B{model_index + 1}_{slug}_r{run}_a{n}.png"
        (job_dir / name).write_bytes(res["png"])
        usage = res["usage"] or {}
        out_ratio = res["size"][0] / res["size"][1]
        attempt = {
            "n": n,
            "output": name,
            "prompt": res["prompt"],
            "elapsed_ms": res["elapsed_ms"],
            "api_used": res["api_used"],
            "route_errors": res["attempts"],
            "usage": usage,
            "cost_usd": usage.get("cost"),
            "output_size": res["size"],
            "aspect_drift_pct": round(abs(out_ratio / in_ratio - 1.0) * 100, 1),
            "resolution_applied": res["resolution_applied"],
            "model_text": res["model_text"],
            "verdict": None,
            "verification": None,
            "verification_file": None,
            "verify_ms": None,
            "verify_error": None,
            "judge_status": None,
            "judge_ms": None,
            "judge_cost_usd": None,
        }
        return attempt, res["png"]

    def check(attempt: dict, png: bytes) -> None:
        """Vision OCR of the output against the (shared) OCR of the source, then the judge."""
        t0 = time.perf_counter()
        try:
            source_ocr = source.get()
            width, height = attempt["output_size"]
            output_ocr = vision.ocr_words(bytes_for_vision(png), width, height)
            judged = None
            if opts["judge"]:
                judged = judge.judge_output(source_ocr, output_ocr, lang)  # never raises
                attempt["judge_status"] = judged.get("status")
                attempt["judge_ms"] = judged.get("elapsed_ms")
                attempt["judge_cost_usd"] = judged.get("cost_usd")
            report = verify.verify_output(
                source_ocr, output_ocr, lang, include_text=opts["verify_details"], judge=judged
            )
            attempt["verification"] = report
            attempt["verdict"] = report["verdict"]
            name = f"B{model_index + 1}_{slug}_r{run}_a{attempt['n']}_verify.json"
            doc = {
                "job_id": job_id, "model": model, "run": run, "attempt": attempt["n"],
                "image": attempt["output"], **report,
            }  # fmt: skip
            try:
                (job_dir / name).write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding="utf-8")
                attempt["verification_file"] = name
            except OSError:
                log.warning("could not write %s", name)
        except Exception as exc:  # noqa: BLE001 - the picture is still shown, just unverified
            log.warning("stage=verify job=%s model=%s run=%d failed: %s", job_id, model, run, type(exc).__name__)
            attempt["verify_error"] = describe_error(exc)
            attempt["verdict"] = verify.VERDICT_UNVERIFIED
        attempt["verify_ms"] = int((time.perf_counter() - t0) * 1000)

    try:
        first, png = generate(1, None)
        tries = [first]
        # publish the picture first; the OCR check can take a few more seconds
        store.set_model(job_id, index, dict(summarise_model(result, tries), status="running"))
        if opts["verify"]:
            check(first, png)
            store.set_model(job_id, index, dict(summarise_model(result, tries), status="running"))
            if first["verdict"] in verify.VERDICT_RANK and first["verdict"] != verify.VERDICT_PASS:
                retry_prompt = openrouter.build_retry_prompt(lang, first["verification"])
                try:
                    second, png2 = generate(2, retry_prompt)
                    tries.append(second)
                    store.set_model(job_id, index, dict(summarise_model(result, tries), status="running"))
                    check(second, png2)
                except Exception as exc:  # noqa: BLE001 - attempt 1 stays the result
                    log.warning(
                        "stage=mode-b-retry job=%s model=%s run=%d failed: %s", job_id, model, run, type(exc).__name__
                    )
                    result["retry_error"] = describe_error(exc)
        summarise_model(result, tries)
        result.update(status="done", error=None)
        log.info(
            "stage=mode-b-done job=%s model=%s run=%d attempts=%d selected=%s verdict=%s",
            job_id, model, run, len(tries), result["selected_attempt"], result["verdict"],
        )  # fmt: skip
    except Exception as exc:  # noqa: BLE001 - shown in this run's error box only
        log.exception("mode B (%s, run %d) failed for job %s", model, run, job_id)
        result = {
            **ident,
            "status": "error",
            "error": describe_error(exc),
            "route_preference": openrouter.preferred_route(model),
            "tries": [],
        }
    store.set_model(job_id, index, result)


# ---------------------------------------------------------------- publishing


def _publish(store: JobStore, job_id: str, job_dir: Path) -> None:
    """Upload the finished job; any failure ends up in the job's warnings, never in its status."""
    store.update(job_id, lambda job: job["publish"].update(status="running"))
    try:
        outcome = publish.publish_job(store.snapshot(job_id) or {}, job_dir)
    except Exception as exc:  # noqa: BLE001 - publish_job does not raise, belt and braces
        outcome = {"status": "error", "error": describe_error(exc)}

    def apply(job: dict) -> None:
        notes = list(outcome.get("notes") or [])
        failed = list(outcome.get("failed") or [])
        warnings = [f"MinIO 上傳失敗：{item}" for item in failed] + notes
        if outcome["status"] == "error":
            warnings.append(f"發佈到 MinIO 失敗：{outcome.get('error')}")
        job["warnings"].extend(warnings)
        job["publish"] = {k: v for k, v in outcome.items() if k not in ("failed", "notes")}
        job["publish"]["failed_count"] = len(failed)

    store.update(job_id, apply)


# ---------------------------------------------------------------- start


def start_job(
    store: JobStore,
    image: LoadedImage,
    lang: str,
    run_a: bool,
    run_b: bool,
    models: list[str],
    verify_on: bool = True,
    verify_details: bool = False,
    output_size: str = "",
    runs: int = 1,
    judge_on: bool = True,
) -> str:
    settings = get_settings()
    runs = max(1, min(MAX_RUNS, int(runs)))
    judge_on = bool(judge_on and verify_on)  # the judge reads the verification's OCR: no check, no judge
    job_id = uuid.uuid4().hex[:16]
    job_dir = settings.data_dir / job_id
    job_dir.mkdir(parents=True, exist_ok=True)
    original = f"original.{image.ext}"
    (job_dir / original).write_bytes(image.data)

    models = list(models) if run_b else []
    opts = {
        "verify": verify_on,
        "verify_details": verify_details,
        "output_size": output_size,
        "judge": judge_on,
        "runs": runs,
    }
    job = {
        "job_id": job_id,
        "status": "running",
        "created_at": time.time(),
        "finished_at": None,
        "warnings": [],
        "params": {
            "target_lang": lang,
            "mode_a": run_a,
            "mode_b": run_b,
            "image_models": models,
            "output_size": output_size,
            "verify": verify_on,
            "verify_details": verify_details,
            "runs": runs,
            "judge": judge_on,
            "judge_model": settings.claude_judge_model,
            "claude_model": settings.claude_model,
            "mock": settings.mock,
        },
        "image": {"url": f"files/{job_id}/{original}", "width": image.width, "height": image.height},
        "mode_a": {"status": "pending" if run_a else "skipped", "error": None},
        "mode_b": {
            "status": "pending" if models else "skipped",
            "error": None,
            # flat and model-major: [m0 run1, m0 run2, ..., m1 run1, ...]
            "results": [
                {
                    "model": m,
                    "index": mi * runs + (k - 1),
                    "model_index": mi,
                    "run": k,
                    "runs": runs,
                    "status": "pending",
                    "error": None,
                    "route_preference": openrouter.preferred_route(m),
                    "tries": [],
                }
                for mi, m in enumerate(models)
                for k in range(1, runs + 1)
            ],
            "summary": [],
        },
        "publish": {"status": "pending", "note": None},
    }
    store.put(job, job_dir)
    log.info(
        "job=%s started lang=%s a=%s b=%s models=%d runs=%d size=%s verify=%s judge=%s image=%dx%d",
        job_id, lang, run_a, run_b, len(models), runs, output_size or "default", verify_on, judge_on,
        image.width, image.height,
    )  # fmt: skip

    source = SourceOcr(image)

    def runner() -> None:
        try:
            workers = len(models) * runs + 2  # one thread per unit; generations queue on _GENERATION_SLOTS
            with ThreadPoolExecutor(max_workers=workers, thread_name_prefix=f"job-{job_id}") as pool:
                if run_a:
                    pool.submit(_mode_a, store, job_id, image, lang, job_dir, source)
                elif models and verify_on:
                    pool.submit(source.prefetch)  # overlaps with the (slow) image generation
                for k in range(1, runs + 1):  # run-major: every model's first run starts before any second run
                    for mi, model in enumerate(models):
                        index = mi * runs + (k - 1)
                        pool.submit(_mode_b_run, store, job_id, index, mi, k, image, lang, model, opts, source, job_dir)
        except Exception:  # noqa: BLE001 - never leave the job "running" forever
            log.exception("job runner crashed for %s", job_id)

            def fail_live(job: dict) -> None:
                if job["mode_a"]["status"] in LIVE:
                    job["mode_a"] = {"status": "error", "error": "the job runner crashed"}
                for r in job["mode_b"]["results"]:
                    if r["status"] in LIVE:
                        r.update(status="error", error="the job runner crashed")

            store.update(job_id, fail_live)
        threading.Thread(target=_publish, args=(store, job_id, job_dir), name=f"publish-{job_id}", daemon=True).start()

    threading.Thread(target=runner, name=f"job-{job_id}", daemon=True).start()
    return job_id
