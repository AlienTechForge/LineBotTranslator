"""Batch mode summary: pass rate, mean cost and time, best run (pure functions over run results)."""

import pytest

from app import batch


def run_result(model, run, verdict, *, cost=0.10, elapsed=1000, coverage=1.0, status="done", model_index=0,
               missing=(), ratio=0.0, runs=3, output=None):  # fmt: skip
    if status != "done":
        return {"model": model, "model_index": model_index, "run": run, "runs": runs, "status": status, "tries": []}
    return {
        "model": model, "model_index": model_index, "run": run, "runs": runs, "status": "done",
        "verdict": verdict, "total_cost_usd": cost, "elapsed_ms": elapsed,
        "output": output or f"B{model_index + 1}_x_r{run}_a1.png",
        "verification": {"verdict": verdict, "coverage": coverage, "missing_digit_runs": list(missing), "leftover_ratio": ratio},
    }  # fmt: skip


def test_pass_rate_is_passes_over_finished_runs_and_a_failed_run_counts_as_not_passed():
    rows = batch.summarise_runs([
        run_result("m/a", 1, "pass", cost=0.10, elapsed=1000),
        run_result("m/a", 2, "garbled", cost=0.20, elapsed=3000, coverage=0.5),
        run_result("m/a", 3, None, status="error"),
    ])  # fmt: skip
    (row,) = rows
    assert (row["runs_requested"], row["runs_done"], row["runs_failed"], row["runs_live"]) == (3, 2, 1, 0)
    assert row["passes"] == 1 and row["pass_rate"] == pytest.approx(1 / 3, abs=1e-4)
    assert row["verdicts"] == ["pass", "garbled", "error"]
    assert row["mean_total_cost_usd"] == pytest.approx(0.15) and row["total_cost_usd"] == pytest.approx(0.30)
    assert row["mean_elapsed_ms"] == 2000  # only the runs that completed


def test_the_best_run_is_the_best_verdict_then_coverage_and_the_earlier_run_wins_ties():
    rows = batch.summarise_runs([
        run_result("m/a", 1, "partial", ratio=0.2),
        run_result("m/a", 2, "pass", coverage=0.93),
        run_result("m/a", 3, "pass", coverage=0.98),
    ])  # fmt: skip
    assert rows[0]["best_run"] == 3 and rows[0]["best_verdict"] == "pass" and rows[0]["best_output"] == "B1_x_r3_a1.png"
    tie = batch.summarise_runs([run_result("m/a", 1, "pass"), run_result("m/a", 2, "pass")])
    assert tie[0]["best_run"] == 1
    worse_first = batch.summarise_runs([run_result("m/a", 1, "digits-changed", missing=["1234"]), run_result("m/a", 2, "garbled", coverage=0.4)])
    assert worse_first[0]["best_run"] == 2  # garbled text with good digits beats changed digits


def test_runs_that_are_still_going_are_counted_but_not_in_the_rate():
    rows = batch.summarise_runs([
        run_result("m/a", 1, "pass"),
        run_result("m/a", 2, None, status="running"),
        run_result("m/a", 3, None, status="pending"),
    ])  # fmt: skip
    (row,) = rows
    assert row["runs_live"] == 2 and row["pass_rate"] == 1.0 and row["verdicts"] == ["pass", "running", "pending"]
    nothing_yet = batch.summarise_runs([run_result("m/a", 1, None, status="pending")])
    assert nothing_yet[0]["pass_rate"] is None and nothing_yet[0]["best_run"] is None
    assert nothing_yet[0]["mean_total_cost_usd"] is None and nothing_yet[0]["mean_elapsed_ms"] is None


def test_a_model_whose_runs_all_failed_has_a_zero_rate_and_no_best_run():
    rows = batch.summarise_runs([run_result("m/a", k, None, status="error") for k in (1, 2)])
    assert rows[0]["pass_rate"] == 0.0 and rows[0]["best_run"] is None and rows[0]["runs_failed"] == 2


def test_one_row_per_model_in_model_order_whatever_the_order_of_the_results():
    rows = batch.summarise_runs([
        run_result("m/b", 2, "pass", model_index=1), run_result("m/a", 1, "pass", model_index=0),
        run_result("m/b", 1, "partial", model_index=1), run_result("m/a", 2, "pass", model_index=0),
    ])  # fmt: skip
    assert [r["model"] for r in rows] == ["m/b", "m/a"]  # first appearance
    assert rows[0]["verdicts"] == ["partial", "pass"] and rows[0]["pass_rate"] == 0.5 and rows[0]["best_run"] == 2
    assert batch.summarise_runs([]) == []


def test_results_without_a_total_cost_are_left_out_of_the_cost_mean():
    unknown = run_result("m/a", 2, "pass", cost=None)
    rows = batch.summarise_runs([run_result("m/a", 1, "pass", cost=0.30), unknown])
    assert rows[0]["mean_total_cost_usd"] == pytest.approx(0.30)
    assert batch.summarise_runs([unknown])[0]["mean_total_cost_usd"] is None
