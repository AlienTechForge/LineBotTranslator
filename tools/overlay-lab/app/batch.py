"""Batch mode: N independent runs of every Mode B model, and what to say about them together.

One Mode B result of ``job["mode_b"]["results"]`` is one run of one model (``model``,
``model_index``, ``run``, ``runs``). A single run of an image model says little because the same
image can pass once and come back as gibberish the next time, so the useful number is the pass
rate over several runs. `summarise_runs` folds the runs of each model into one row: the pass rate
(verdict ``pass`` over the runs that have finished, a failed run counts as not passed), the mean
total cost and time of the runs that completed, and the best run (verify.choose_attempt: the best
verdict, then coverage, fewer missing digits, lower leftover ratio, the earlier run).

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
