"""Batch mode: N independent runs of every Mode B model, and one row per image x mode / model.

One Mode B result of ``job["mode_b"]["results"]`` is one run of one model (``model``,
``model_index``, ``run``, ``runs``). A single run of an image model says little because the same
image can pass once and come back as gibberish the next time, so the useful number is the pass
rate over several runs. `summarise_runs` folds the runs of each model into one row: the pass rate
(verdict ``pass`` over the runs that have finished, a failed run counts as not passed), the mean
total cost and time of the runs that completed, and the best run (verify.choose_attempt: the
checks that pass, then usability, then coverage, the earlier run).

`summarise_batch` folds the jobs of one submission (one per image) into the rows of the batch
summary table: one row per image x mode / model with the verdict and judge usability of its best
run, the cost split into Anthropic (covered by a subscription credit) and OpenRouter (cash), and the
time. The page and the batch manifest show the same rows.

Pure functions over plain dicts; used by jobs.py (live, on every update) and publish.py.
"""

from __future__ import annotations

from . import verify

LIVE = ("pending", "running")


def _mean(values: list[float]) -> float | None:
    return (sum(values) / len(values)) if values else None


def summarise_runs(results: list[dict]) -> list[dict]:
    """One summary row per model, in the order the models first appear in `results`."""
    groups: dict[object, list[dict]] = {}
    for result in results:
        key = result.get("model_index", result.get("model"))
        groups.setdefault(key, []).append(result)

    rows: list[dict] = []
    for runs in groups.values():
        runs = sorted(runs, key=lambda r: r.get("run") or 1)
        done = [r for r in runs if r.get("status") == "done"]
        failed = [r for r in runs if r.get("status") == "error"]
        live = [r for r in runs if r.get("status") in LIVE]
        finished = len(done) + len(failed)
        passes = sum(1 for r in done if r.get("verdict") == verify.VERDICT_PASS)
        costs = [r["total_cost_usd"] for r in done if isinstance(r.get("total_cost_usd"), (int, float))]
        times = [r["elapsed_ms"] for r in done if isinstance(r.get("elapsed_ms"), (int, float))]
        best = done[verify.choose_attempt([r.get("verification") for r in done])] if done else None
        rows.append(
            {
                "model": runs[0].get("model"),
                "model_index": runs[0].get("model_index"),
                "runs_requested": runs[0].get("runs") or len(runs),
                "runs_done": len(done),
                "runs_failed": len(failed),
                "runs_live": len(live),
                "passes": passes,
                "pass_rate": round(passes / finished, 4) if finished else None,
                "verdicts": [r.get("verdict") if r.get("status") == "done" else r.get("status") for r in runs],
                "mean_total_cost_usd": round(_mean(costs), 6) if costs else None,
                "total_cost_usd": round(sum(costs), 6) if costs else None,
                "mean_elapsed_ms": int(_mean(times)) if times else None,
                "best_run": best.get("run") if best else None,
                "best_verdict": best.get("verdict") if best else None,
                "best_output": best.get("output") if best else None,
            }
        )
    return rows


def _sum_known(values: list) -> float | None:
    known = [v for v in values if isinstance(v, (int, float))]
    return round(sum(known), 6) if known else None


def _combine_status(statuses: list[str]) -> str:
    if any(st == "running" for st in statuses):
        return "running"
    if any(st == "pending" for st in statuses):
        return "pending" if all(st == "pending" for st in statuses) else "running"
    if statuses and all(st == "error" for st in statuses):
        return "error"
    return "done"


def _row(job: dict, mode: str, label: str, model: str | None) -> dict:
    image = job.get("image") or {}
    return {
        "image_index": job.get("index"),
        "job_id": job.get("job_id"),
        "image_name": image.get("name") or "",
        "mode": mode,
        "model": model,
        "label": label,
        "status": "pending",
        "runs": 1,
        "runs_done": 0,
        "runs_failed": 0,
        "passes": 0,
        "verdict": None,
        "failed_checks": [],
        "usability": None,
        "coverage": None,
        "cost_anthropic_usd": None,
        "cost_openrouter_usd": None,
        "cost_total_usd": None,
        "elapsed_ms": None,
        "output": None,
        "error": None,
    }


def _fill_costs(row: dict, anthropic: float | None, openrouter: float | None) -> None:
    row["cost_anthropic_usd"] = anthropic
    row["cost_openrouter_usd"] = openrouter
    row["cost_total_usd"] = _sum_known([anthropic, openrouter])


def summarise_batch(jobs: list[dict]) -> list[dict]:
    """One row per image x mode / model, in submission order (Mode A first, then each Mode B model)."""
    rows: list[dict] = []
    for job in jobs:
        a = job.get("mode_a") or {}
        if a.get("status") not in (None, "skipped"):
            params = job.get("params") or {}
            row = _row(job, "A", "Mode A · " + str(a.get("model") or params.get("claude_model") or ""), a.get("model") or params.get("claude_model"))
            row["status"] = a.get("status") or "pending"
            row["error"] = a.get("error")
            if a.get("status") == "done":
                verification = a.get("verification") or {}
                row.update(
                    verdict=a.get("verdict"),
                    failed_checks=verification.get("failed_checks") or [],
                    usability=a.get("usability"),
                    coverage=a.get("coverage"),
                    elapsed_ms=a.get("elapsed_ms"),
                    output=a.get("output"),
                    runs_done=1,
                    passes=int(a.get("verdict") == verify.VERDICT_PASS),
                )
                _fill_costs(row, a.get("cost_anthropic_usd"), a.get("cost_openrouter_usd"))
            elif a.get("status") == "error":
                row["runs_failed"] = 1
            rows.append(row)
        results = (job.get("mode_b") or {}).get("results") or []
        for summary in summarise_runs(results):
            runs = [r for r in results if r.get("model_index", r.get("model")) == summary["model_index"]]
            done = [r for r in runs if r.get("status") == "done"]
            row = _row(job, "B", "Mode B · " + str(summary["model"]), summary["model"])
            row.update(
                status=_combine_status([r.get("status") or "pending" for r in runs]),
                runs=summary["runs_requested"],
                runs_done=summary["runs_done"],
                runs_failed=summary["runs_failed"],
                passes=summary["passes"],
                verdict=summary["best_verdict"],
                output=summary["best_output"],
                elapsed_ms=summary["mean_elapsed_ms"],
            )
            best = next((r for r in done if r.get("run") == summary["best_run"]), None)
            if best:
                verification = best.get("verification") or {}
                row.update(
                    usability=best.get("usability"),
                    coverage=best.get("coverage"),
                    failed_checks=verification.get("failed_checks") or [],
                )
            errors = [r.get("error") for r in runs if r.get("status") == "error" and r.get("error")]
            row["error"] = errors[0] if errors and not done else None
            _fill_costs(
                row,
                _sum_known([r.get("cost_anthropic_usd") for r in done]),
                _sum_known([r.get("cost_openrouter_usd") for r in done]),
            )
            rows.append(row)
    return rows


def batch_totals(rows: list[dict]) -> dict:
    """What the whole batch cost, split into Anthropic and OpenRouter, and how many rows passed."""
    anthropic = _sum_known([r.get("cost_anthropic_usd") for r in rows])
    openrouter = _sum_known([r.get("cost_openrouter_usd") for r in rows])
    finished = [r for r in rows if r.get("status") in ("done", "error")]
    return {
        "rows": len(rows),
        "rows_finished": len(finished),
        "rows_passed": sum(1 for r in rows if r.get("verdict") == verify.VERDICT_PASS),
        "cost_anthropic_usd": anthropic,
        "cost_openrouter_usd": openrouter,
        "cost_total_usd": _sum_known([anthropic, openrouter]),
    }
