"""Batch mode: pass rate, mean cost and time, best run per model, and the rows of the batch summary table."""

import pytest

from app import batch


def run_result(model, run, verdict, *, cost=0.10, elapsed=1000, usability=1.0, coverage=1.0, status="done", model_index=0,
               runs=3, output=None, judge_cost=0.01, failed=None):  # fmt: skip
    if status != "done":
        return {"model": model, "model_index": model_index, "run": run, "runs": runs, "status": status, "tries": []}
    return {
        "model": model, "model_index": model_index, "run": run, "runs": runs, "status": "done",
        "verdict": verdict, "total_cost_usd": cost, "elapsed_ms": elapsed,
        "cost_openrouter_usd": None if cost is None else cost - judge_cost, "cost_anthropic_usd": judge_cost,
        "usability": usability, "coverage": coverage,
        "output": output or f"B{model_index + 1}_x_r{run}_a1.png",
        "verification": {
            "verdict": verdict, "usability": usability, "coverage": coverage,
            "failed_checks": failed if failed is not None else ([] if verdict == "pass" else verdict.split("+")),
        },
    }  # fmt: skip


def test_pass_rate_is_passes_over_finished_runs_and_a_failed_run_counts_as_not_passed():
    rows = batch.summarise_runs([
        run_result("m/a", 1, "pass", cost=0.10, elapsed=1000),
        run_result("m/a", 2, "garbled", cost=0.20, elapsed=3000, usability=0.5),
        run_result("m/a", 3, None, status="error"),
    ])  # fmt: skip
    (row,) = rows
    assert (row["runs_requested"], row["runs_done"], row["runs_failed"], row["runs_live"]) == (3, 2, 1, 0)
    assert row["passes"] == 1 and row["pass_rate"] == pytest.approx(1 / 3, abs=1e-4)
    assert row["verdicts"] == ["pass", "garbled", "error"]
    assert row["mean_total_cost_usd"] == pytest.approx(0.15) and row["total_cost_usd"] == pytest.approx(0.30)
    assert row["mean_elapsed_ms"] == 2000  # only the runs that completed


def test_the_best_run_is_the_most_checks_passed_then_usability_then_coverage_and_the_earlier_run_wins_ties():
    rows = batch.summarise_runs([
        run_result("m/a", 1, "partial", usability=0.9),
        run_result("m/a", 2, "pass", usability=0.83),
        run_result("m/a", 3, "pass", usability=0.95),
    ])  # fmt: skip
    assert rows[0]["best_run"] == 3 and rows[0]["best_verdict"] == "pass" and rows[0]["best_output"] == "B1_x_r3_a1.png"
    tie = batch.summarise_runs([run_result("m/a", 1, "pass"), run_result("m/a", 2, "pass")])
    assert tie[0]["best_run"] == 1
    worse_first = batch.summarise_runs([run_result("m/a", 1, "numbers", usability=0.99), run_result("m/a", 2, "garbled", usability=0.4)])
    assert worse_first[0]["best_run"] == 2  # a changed number is worse than a garbled heading, whatever the usability


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


# ---------------------------------------------------------------- the rows of the batch summary table


def mode_a(*, verdict="pass", usability=0.9, status="done", a_cost=0.12, elapsed=5000, error=None):
    if status != "done":
        return {"status": status, "error": error}
    return {
        "status": "done", "error": None, "model": "claude-opus-5-5", "verdict": verdict, "usability": usability, "coverage": 0.95,
        "verification": {"failed_checks": [] if verdict == "pass" else verdict.split("+")},
        "elapsed_ms": elapsed, "output": "A_a1.png", "cost_anthropic_usd": a_cost, "cost_openrouter_usd": 0.0,
    }  # fmt: skip


def make_job(index, *, a=None, results=(), name="img.png"):
    return {
        "job_id": f"{index:016x}", "index": index, "image": {"name": name}, "params": {"claude_model": "claude-opus-5-5"},
        "mode_a": a or {"status": "skipped", "error": None}, "mode_b": {"results": list(results)},
    }  # fmt: skip


def test_one_row_per_image_and_mode_or_model_in_submission_order():
    jobs = [
        make_job(1, a=mode_a(), results=[run_result("m/a", 1, "pass", runs=1), run_result("m/b", 1, "numbers", model_index=1, runs=1)]),
        make_job(2, results=[run_result("m/a", 1, "partial", runs=1)], name="second.jpg"),
    ]
    rows = batch.summarise_batch(jobs)
    assert [(r["image_index"], r["mode"], r["model"]) for r in rows] == [
        (1, "A", "claude-opus-5-5"), (1, "B", "m/a"), (1, "B", "m/b"), (2, "B", "m/a"),
    ]  # fmt: skip
    assert [r["label"] for r in rows] == ["Mode A · claude-opus-5-5", "Mode B · m/a", "Mode B · m/b", "Mode B · m/a"]
    assert [r["verdict"] for r in rows] == ["pass", "pass", "numbers", "partial"]
    assert rows[3]["image_name"] == "second.jpg" and rows[3]["job_id"] == f"{2:016x}"
    assert rows[2]["failed_checks"] == ["numbers"] and rows[0]["failed_checks"] == []
    assert all(r["status"] == "done" for r in rows)


def test_a_row_carries_the_judge_usability_and_the_cost_split_into_anthropic_and_openrouter():
    rows = batch.summarise_batch([make_job(1, a=mode_a(usability=0.91, a_cost=0.12), results=[run_result("m/a", 1, "pass", cost=0.20, judge_cost=0.02, usability=0.87, runs=1)])])
    a, b = rows
    assert a["usability"] == 0.91 and a["cost_anthropic_usd"] == 0.12 and a["cost_openrouter_usd"] == 0.0
    assert a["cost_total_usd"] == pytest.approx(0.12) and a["elapsed_ms"] == 5000 and a["output"] == "A_a1.png"
    assert b["usability"] == 0.87 and b["cost_anthropic_usd"] == pytest.approx(0.02) and b["cost_openrouter_usd"] == pytest.approx(0.18)
    assert b["cost_total_usd"] == pytest.approx(0.20) and b["elapsed_ms"] == 1000 and b["output"] == "B1_x_r1_a1.png"


def test_a_row_of_several_runs_sums_the_costs_averages_the_time_and_shows_the_best_run():
    runs = [
        run_result("m/a", 1, "score", cost=0.10, judge_cost=0.01, elapsed=2000, usability=0.7),
        run_result("m/a", 2, "pass", cost=0.12, judge_cost=0.02, elapsed=4000, usability=0.9),
        run_result("m/a", 3, None, status="error"),
    ]
    (row,) = batch.summarise_batch([make_job(1, results=runs)])
    assert (row["runs"], row["runs_done"], row["runs_failed"], row["passes"]) == (3, 2, 1, 1)
    assert row["verdict"] == "pass" and row["usability"] == 0.9  # the best run
    assert row["cost_anthropic_usd"] == pytest.approx(0.03) and row["cost_openrouter_usd"] == pytest.approx(0.19)
    assert row["elapsed_ms"] == 3000 and row["status"] == "done"


def test_row_status_follows_the_runs_and_a_failed_mode_has_an_error_and_no_verdict():
    live = batch.summarise_batch([make_job(1, results=[run_result("m/a", 1, "pass", runs=2), run_result("m/a", 2, None, status="running", runs=2)])])
    assert live[0]["status"] == "running" and live[0]["verdict"] == "pass"
    pending = batch.summarise_batch([make_job(1, a=mode_a(status="pending"), results=[run_result("m/a", 1, None, status="pending", runs=1)])])
    assert [r["status"] for r in pending] == ["pending", "pending"] and pending[0]["verdict"] is None
    failed = batch.summarise_batch([make_job(1, a=mode_a(status="error", error="Claude API error 500"), results=[run_result("m/a", 1, None, status="error", runs=1)])])
    assert [r["status"] for r in failed] == ["error", "error"] and failed[0]["error"] == "Claude API error 500"
    assert failed[0]["verdict"] is None and failed[1]["verdict"] is None and failed[0]["cost_total_usd"] is None


def test_totals_split_the_cost_and_count_the_passing_rows():
    rows = batch.summarise_batch([
        make_job(1, a=mode_a(a_cost=0.12), results=[run_result("m/a", 1, "pass", cost=0.20, judge_cost=0.02, runs=1)]),
        make_job(2, a=mode_a(verdict="garbled", a_cost=0.10), results=[run_result("m/a", 1, "numbers", cost=0.30, judge_cost=0.03, runs=1)]),
    ])  # fmt: skip
    totals = batch.batch_totals(rows)
    assert totals["rows"] == 4 and totals["rows_passed"] == 2 and totals["rows_finished"] == 4
    assert totals["cost_anthropic_usd"] == pytest.approx(0.12 + 0.02 + 0.10 + 0.03)
    assert totals["cost_openrouter_usd"] == pytest.approx(0.0 + 0.18 + 0.0 + 0.27)
    assert totals["cost_total_usd"] == pytest.approx(totals["cost_anthropic_usd"] + totals["cost_openrouter_usd"])
    assert batch.batch_totals([])["cost_total_usd"] is None
