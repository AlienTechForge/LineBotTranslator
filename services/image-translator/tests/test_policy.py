"""The A-then-B policy: every status, tier and reason, with scripted Mode A / Mode B outcomes."""

import pytest
from policy_rig import Rig, judged, units
from scenario import judge_record

from app import budget, policy

PLAN = units(("TONKATSU SET", "Pork cutlet set"), ("NT$120", "NT$120", True), ("紅燒肉", "Braised pork"))
PLAN_TEXT = "Pork cutlet set\nNT$120\nBraised pork"


def rig(monkeypatch, tmp_path, **kw) -> Rig:
    return Rig(monkeypatch, tmp_path, **kw)


# ---------------------------------------------------------------- Mode A passes


def test_a_pass_is_a_clean_picture_and_mode_b_is_never_called(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.9, "plan_cost": 0.05}])
    out = r.run()
    assert (out.status, out.tier, out.reason, out.mode, out.verdict) == ("image", "clean", "a_pass", "A", "pass")
    assert out.usability == 0.9 and out.png is not None and out.size == (80, 60)
    assert r.calls_b == [] and [a["mode"] for a in out.attempts] == ["A"] and out.attempts[0]["selected"] is True
    assert r.calls_a == [{"lang": "zh-TW", "retry": False, "has_check": True, "meta": r.calls_a[0]["meta"]}]  # the lab's retry is off
    assert out.text["translated_text"] == PLAN_TEXT and out.text["source_language"] == "en"
    assert out.text["recognized_text"] == "TONKATSU SET"
    assert out.costs == {"anthropic_usd": pytest.approx(0.06), "openrouter_usd": 0.0, "total_usd": pytest.approx(0.06)}
    assert r.spent()["anthropic_usd"] == pytest.approx(0.06) and r.spent()["requests"] == 1
    assert out.judge["summary"] == "scripted judge" and out.warnings == []
    assert set(out.timings_ms) == {"ocr", "mode_a", "mode_b", "judge", "total"} and out.timings_ms["mode_b"] is None


def test_the_source_is_read_by_vision_exactly_once(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.5}], b=[{"usability": 0.5}])
    r.run()
    assert r.ocr_calls == 1  # Mode A and the judge of both modes share it


def test_mode_a_rows_are_not_a_check_but_are_noted(monkeypatch, tmp_path):
    noise = judged(0.95, rows=[("missing_row", "the fourth dish")])
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "judge": noise}])
    out = r.run()
    assert (out.tier, out.reason, out.verdict) == ("clean", "a_pass", "pass")
    assert any("structure issue" in w and "missing_row" in w for w in out.warnings)


# ---------------------------------------------------------------- Mode A does not pass: Mode B


def test_a_fails_and_b_is_better(monkeypatch, tmp_path):
    bad = judged(0.9, altered=[("120", "12")])
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "judge": bad}], b=[{"usability": 0.93, "cost": 0.07}])
    out = r.run()
    assert (out.status, out.tier, out.reason, out.mode, out.verdict) == ("image", "clean", "b_better", "B", "pass")
    assert [a["mode"] for a in out.attempts] == ["A", "B"] and [a["selected"] for a in out.attempts] == [False, True]
    assert out.attempts[0]["verdict"] == "numbers" and out.attempts[1]["verdict"] == "pass"
    assert len(r.calls_b) == 1 and r.calls_b[0]["resolution"] == "2K" and r.calls_b[0]["prompt"] is None  # one generation, no retry prompt
    assert out.costs["openrouter_usd"] == pytest.approx(0.07) and r.spent()["openrouter_usd"] == pytest.approx(0.07)
    assert out.costs["anthropic_usd"] == pytest.approx(0.02 + 0.01 + 0.01)  # planner, judge of A, judge of B
    assert out.text["translated_text"] == PLAN_TEXT  # Mode A's planner answered, whichever picture wins
    assert out.attempts[1]["cost_usd"] == pytest.approx(0.07 + 0.01)


def test_a_fails_and_b_is_worse_so_a_stays(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.7}], b=[{"usability": 0.5}])
    out = r.run()
    assert (out.status, out.tier, out.reason, out.mode, out.verdict) == ("image", "warned", "a_better", "A", "score")
    assert out.usability == 0.7 and [a["selected"] for a in out.attempts] == [True, False]


def test_a_tie_goes_to_mode_a(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.7}], b=[{"usability": 0.7}])
    out = r.run()
    assert out.mode == "A" and out.reason == "a_better"


def test_b_better_but_still_not_a_pass_is_a_warned_picture(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.65}], b=[{"usability": 0.75}])
    out = r.run()
    assert (out.status, out.tier, out.reason, out.mode) == ("image", "warned", "b_better", "B")


# ---------------------------------------------------------------- Mode B is skipped


def test_b_skipped_for_the_openrouter_budget(monkeypatch, tmp_path):
    monkeypatch.setenv("OPENROUTER_MONTHLY_BUDGET_USD", "1.00")
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.7}])
    budget.add(openrouter_usd=1.00)  # used up
    out = r.run()
    assert (out.status, out.tier, out.reason, out.mode) == ("image", "warned", "openrouter_budget", "A")
    assert r.calls_b == [] and out.attempts[1] == {
        "mode": "B", "verdict": None, "usability": None, "cost_usd": 0.0, "elapsed_ms": None, "error": None,
        "skipped": "openrouter_budget", "selected": False,
    }  # fmt: skip
    assert any("Mode B was skipped: openrouter_budget" in w for w in out.warnings)


def test_b_skipped_for_the_deadline(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.7, "takes": 150}])  # 210 - 150 = 60 s left < 90
    out = r.run()
    assert (out.status, out.tier, out.reason, out.mode) == ("image", "warned", "deadline", "A")
    assert r.calls_b == []


def test_the_deadline_rule_needs_exactly_mode_b_min_remaining_seconds(monkeypatch, tmp_path):
    for left, runs in ((90.0, True), (89.9, False)):
        r = rig(monkeypatch, tmp_path / str(left), a=[{"units": PLAN, "usability": 0.7, "takes": 210.0 - left}], b=[{"usability": 0.95}])
        out = r.run()
        assert bool(r.calls_b) is runs, left
        assert out.reason == ("b_better" if runs else "deadline")
        budget_file = budget.path()
        if budget_file.exists():
            budget_file.unlink()


def test_mode_b_min_remaining_s_comes_from_the_environment(monkeypatch, tmp_path):
    monkeypatch.setenv("MODE_B_MIN_REMAINING_S", "10")
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.7, "takes": 150}], b=[{"usability": 0.95}])
    assert r.run().reason == "b_better"  # 60 s left is plenty for a threshold of 10


def test_b_skipped_when_the_anthropic_budget_ran_out_during_mode_a(monkeypatch, tmp_path):
    monkeypatch.setenv("ANTHROPIC_MONTHLY_BUDGET_USD", "0.05")
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.7, "plan_cost": 0.05}])  # the planner alone uses it up
    out = r.run()
    assert (out.status, out.reason, out.mode) == ("image", "anthropic_budget", "A") and r.calls_b == []


def test_the_anthropic_budget_used_up_before_the_start_calls_nothing(monkeypatch, tmp_path):
    monkeypatch.setenv("ANTHROPIC_MONTHLY_BUDGET_USD", "10")
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN}], b=[{}])
    budget.add(anthropic_usd=10)
    out = r.run()
    assert (out.status, out.tier, out.reason, out.png) == ("error", None, "anthropic_budget", None)
    assert r.calls_a == [] and r.calls_b == [] and r.ocr_calls == 0 and r.judge_calls == []
    assert out.attempts == [] and out.verdict is None


def test_a_deadline_that_has_already_passed_starts_nothing(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN}])
    out = r.run(deadline_s=0.0)
    assert (out.status, out.reason) == ("error", "deadline") and r.calls_a == [] and r.ocr_calls == 0


# ---------------------------------------------------------------- one mode fails


def test_mode_a_raises_and_mode_b_does_the_job(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"fail": RuntimeError("planner exploded")}], b=[{"usability": 0.9}])
    out = r.run()
    assert (out.status, out.tier, out.reason, out.mode) == ("image", "clean", "a_failed", "B")
    assert out.attempts[0]["error"] == "RuntimeError: planner exploded" and out.attempts[0]["verdict"] is None
    assert out.text["translated_text"] is None  # the planner never answered
    assert any("Mode A failed" in w for w in out.warnings)


def test_a_planner_answer_survives_a_render_that_fails_afterwards(monkeypatch, tmp_path):
    r = rig(
        monkeypatch, tmp_path,
        a=[{"units": PLAN, "fail": RuntimeError("render"), "after_plan": True, "plan_cost": 0.03}], b=[{"usability": 0.9}],
    )  # fmt: skip
    out = r.run()
    assert out.reason == "a_failed" and out.text["translated_text"] == PLAN_TEXT
    assert out.costs["anthropic_usd"] == pytest.approx(0.03 + 0.01)  # the planner was paid for


def test_mode_b_raises_and_mode_a_stays(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.7}], b=[{"fail": OSError("no route")}])
    out = r.run()
    assert (out.status, out.tier, out.reason, out.mode) == ("image", "warned", "b_failed", "A")
    assert out.attempts[1]["error"].startswith("OSError") and any("Mode B failed" in w for w in out.warnings)


def test_both_fail_is_a_200_style_error_with_a_reason(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "fail": RuntimeError("a"), "after_plan": True}], b=[{"fail": RuntimeError("b")}])
    out = r.run()
    assert (out.status, out.tier, out.reason, out.mode, out.png) == ("error", None, "both_failed", None, None)
    assert out.text["translated_text"] == PLAN_TEXT  # whatever the planner said is still handed over
    assert [a["error"] is not None for a in out.attempts] == [True, True] and all(a["selected"] is False for a in out.attempts)


def test_a_fails_and_b_is_skipped_for_budget_is_an_error_naming_the_budget(monkeypatch, tmp_path):
    monkeypatch.setenv("OPENROUTER_MONTHLY_BUDGET_USD", "0.5")
    r = rig(monkeypatch, tmp_path, a=[{"fail": RuntimeError("a")}])
    budget.add(openrouter_usd=0.5)
    out = r.run()
    assert (out.status, out.reason) == ("error", "openrouter_budget")


def test_vision_failing_on_the_source_leaves_mode_b(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[], b=[{"usability": 0.9}], ocr_error=RuntimeError("vision down"))
    out = r.run()
    assert (out.status, out.reason, out.mode) == ("image", "a_failed", "B") and r.calls_a == []
    assert out.attempts[0]["error"] == "the OCR of the source failed" and out.text["source_language"] is None
    assert any("OCR of the source failed" in w for w in out.warnings)


# ---------------------------------------------------------------- the three tiers


@pytest.mark.parametrize(
    "usability, tier",
    [(0.95, "clean"), (0.80, "clean"), (0.79, "warned"), (0.60, "warned"), (0.59, None), (0.0, None)],
)
def test_the_tiers_follow_the_judge_and_the_default_threshold_of_0_60(monkeypatch, tmp_path, usability, tier):
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": usability}], b=[{"usability": usability}])
    out = r.run("a_only")
    assert out.tier == tier
    assert out.status == ("image" if tier else "text_only")
    assert (out.png is not None) == bool(tier)
    if tier is None:
        assert (out.reason, out.mode, out.verdict, out.usability, out.size) == ("below_threshold", "A", "score", usability, None)


def test_the_send_threshold_is_send_min_usability(monkeypatch, tmp_path):
    monkeypatch.setenv("SEND_MIN_USABILITY", "0.7")
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.69}, {"units": PLAN, "usability": 0.7}])
    assert r.run("a_only").status == "text_only"
    assert r.run("a_only").tier == "warned"


def test_text_only_keeps_the_text_the_verdict_and_the_judge(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.4}], b=[{"usability": 0.3}])
    out = r.run()
    assert (out.status, out.tier, out.reason, out.png, out.size) == ("text_only", None, "below_threshold", None, None)
    assert out.text["translated_text"] == PLAN_TEXT and out.judge is not None
    assert [a["mode"] for a in out.attempts] == ["A", "B"] and out.attempts[0]["selected"] is True


def test_an_unverified_picture_is_not_sent(monkeypatch, tmp_path):
    unavailable = judge_record(status="unavailable", error="no key")
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "judge": unavailable}], b=[{"judge": unavailable}])
    out = r.run()
    assert (out.status, out.tier, out.reason, out.verdict, out.usability, out.judge) == (
        "text_only", None, "unverified", "unverified", None, None,
    )


def test_no_text_is_reported_without_calling_either_mode(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, words=(), a=[{"units": PLAN}], b=[{}])
    out = r.run()
    assert (out.status, out.tier, out.reason, out.mode, out.png) == ("no_text", None, "no_text", None, None)
    assert r.calls_a == [] and r.calls_b == [] and out.attempts == []
    assert r.spent()["requests"] == 1
    r2 = rig(monkeypatch, tmp_path / "blank", words=(" ", "  "), a=[], b=[])
    assert r2.run().status == "no_text"  # words that carry no text are no words


# ---------------------------------------------------------------- the policy argument


def test_a_only_never_runs_mode_b(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.7}], b=[{"usability": 0.99}])
    out = r.run("a_only")
    assert (out.status, out.reason, out.mode) == ("image", "policy_a_only", "A") and r.calls_b == []
    assert out.attempts[1]["skipped"] == "policy_a_only"
    assert not any("policy_a_only" in w for w in out.warnings)  # a choice, not a problem


def test_a_only_with_a_pass_has_nothing_to_say_about_b(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.95}])
    out = r.run("a_only")
    assert out.reason == "a_pass" and [a["mode"] for a in out.attempts] == ["A"]


def test_a_only_with_a_failing_mode_a_is_an_a_failed_error(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"fail": RuntimeError("a")}])
    out = r.run("a_only")
    assert (out.status, out.reason) == ("error", "a_failed")


def test_b_only_skips_mode_a(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN}], b=[{"usability": 0.9}])
    out = r.run("b_only")
    assert (out.status, out.tier, out.reason, out.mode) == ("image", "clean", "policy_b_only", "B")
    assert r.calls_a == [] and out.attempts[0]["skipped"] == "policy_b_only"
    assert out.text["translated_text"] is None  # no planner was asked


def test_b_only_failing_is_a_b_failed_error(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, b=[{"fail": RuntimeError("b")}])
    out = r.run("b_only")
    assert (out.status, out.reason) == ("error", "b_failed")


def test_both_runs_a_and_b_even_when_a_passes_and_picks_the_best(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.85}], b=[{"usability": 0.97}])
    out = r.run("both")
    assert len(r.calls_a) == 1 and len(r.calls_b) == 1
    assert (out.status, out.tier, out.reason, out.mode) == ("image", "clean", "b_better", "B")
    tie = rig(monkeypatch, tmp_path / "tie", a=[{"units": PLAN, "usability": 0.9}], b=[{"usability": 0.9}]).run("both")
    assert (tie.reason, tie.mode) == ("a_pass", "A")  # A first on ties


def test_an_unknown_policy_is_refused(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path)
    with pytest.raises(ValueError):
        r.run("whatever")


def test_mode_b_uses_the_given_model_else_the_configured_one(monkeypatch, tmp_path):
    monkeypatch.setenv("OPENROUTER_IMAGE_MODEL", "google/configured")
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.5}, {"units": PLAN, "usability": 0.5}], b=[{}, {}])
    r.run()
    r.run(model_b="vendor/other-image")
    assert [c["model"] for c in r.calls_b] == ["google/configured", "vendor/other-image"]


# ---------------------------------------------------------------- money and notes


def test_every_cost_is_added_to_the_budget_file_as_it_is_known(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.7, "plan_cost": 0.5}], b=[{"usability": 0.9, "cost": 0.25}])
    r.run()
    assert r.spent()["anthropic_usd"] == pytest.approx(0.5 + 0.01 + 0.01) and r.spent()["openrouter_usd"] == pytest.approx(0.25)
    r.a_steps.append({"units": PLAN, "usability": 0.95})
    r.run("a_only")
    assert r.spent()["requests"] == 2 and r.spent()["anthropic_usd"] == pytest.approx(0.52 + 0.02 + 0.01)


def test_an_unknown_price_counts_as_zero_and_says_so_once(monkeypatch, tmp_path):
    unpriced = judged(0.9, cost=None)
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "judge": unpriced, "plan_cost": None}], b=[{"judge": unpriced, "cost": None}])
    out = r.run("both")
    assert out.costs == {"anthropic_usd": 0.0, "openrouter_usd": 0.0, "total_usd": 0.0}
    assert sum("price of the judge is unknown" in w for w in out.warnings) == 1
    assert any("price of the planner is unknown" in w for w in out.warnings)
    assert any("price of the image generation is unknown" in w for w in out.warnings)
    assert r.spent()["anthropic_usd"] == 0.0


def test_a_judge_that_did_not_answer_costs_nothing_and_needs_no_price_warning(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "judge": judge_record(status="unavailable", error="x")}], b=[{"usability": 0.9}])
    out = r.run()
    assert not any("judge is unknown" in w for w in out.warnings)


def test_mock_mode_counts_every_cost_as_zero_but_still_writes_the_file(monkeypatch, tmp_path):
    monkeypatch.setenv("LAB_MOCK", "1")
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.95, "plan_cost": 0.9}])
    out = r.run()
    assert out.costs["total_usd"] == 0.0 and budget.path().is_file()
    assert budget.state() == {"month": budget.current_month(), "openrouter_usd": 0.0, "anthropic_usd": 0.0, "requests": 1}


def test_warnings_carry_the_font_notes_the_caller_passed_and_stay_bounded(monkeypatch, tmp_path):
    r = rig(monkeypatch, tmp_path, a=[{"units": PLAN, "usability": 0.95, "warnings": [f"w{i}" for i in range(100)]}])
    out = r.run(lang="ar", warnings=["target_lang 'xx' is not in the language table"])
    assert out.warnings[0].startswith("target_lang 'xx'")
    assert any("right to left" in w for w in out.warnings)
    assert len(out.warnings) <= policy.MAX_WARNINGS


# ---------------------------------------------------------------- the helpers


def test_translated_text_is_the_planner_s_units_in_its_own_order():
    text = policy.assemble_translated_text(
        [*units(("S1", "T1"), ("¥300", "¥300", True), ("S3", "")), *units(("S4", "multi\nline"))]
    )
    assert text == "T1\n¥300\nS3\nmulti\nline"
    assert policy.assemble_translated_text(None) is None and policy.assemble_translated_text([]) is None
    assert policy.assemble_translated_text(units(("", ""))) is None


def test_the_reasons_of_the_module_are_unique_and_known():
    assert len(set(policy.REASONS)) == len(policy.REASONS)
    assert set(policy.STATUSES) == {"image", "text_only", "no_text", "error"} and policy.TIERS == ("clean", "warned")


def test_b_skip_reason_order_is_policy_then_openrouter_then_clock_then_anthropic(monkeypatch, tmp_path):
    monkeypatch.setenv("LAB_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("OPENROUTER_MONTHLY_BUDGET_USD", "1")
    monkeypatch.setenv("ANTHROPIC_MONTHLY_BUDGET_USD", "1")
    assert policy.b_skip_reason("a_then_b", 500) is None
    budget.add(openrouter_usd=1, anthropic_usd=1)
    assert policy.b_skip_reason("a_only", 10) == "policy_a_only"
    assert policy.b_skip_reason("a_then_b", 10) == "openrouter_budget"
    monkeypatch.setenv("OPENROUTER_MONTHLY_BUDGET_USD", "0")  # unlimited
    assert policy.b_skip_reason("a_then_b", 10) == "deadline"
    assert policy.b_skip_reason("a_then_b", 500) == "anthropic_budget"


def test_tier_for_follows_the_verdict_and_the_threshold():
    assert policy.tier_for("pass", None) == "clean" and policy.tier_for("pass", 0.1) == "clean"
    assert policy.tier_for("score", 0.6) == "warned" and policy.tier_for("numbers+score", 0.59) is None
    assert policy.tier_for("unverified", None) is None and policy.tier_for("garbled", None) is None
