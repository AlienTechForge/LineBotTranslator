"""Mode B through the HTTP API: several models in parallel, the judge, the one retry, runs, size option."""

import io
import threading

import pytest
from fastapi.testclient import TestClient
from scenario import Scenario, judge_record, ocr, png_of_size, wait_for

from app import mock, openrouter
from app.main import create_app

SOURCE = png_of_size((80, 60))
SRC_OCR = ocr(("TEL", "en"), ("77446716", "en"), ("営業時間", "ja"), ("メニュー", "ja"), ("ご飯", "ja"), ("定食", "ja"))
GOOD = ocr(("TEL", "en"), ("77446716", "en"), ("Hours", "en"), ("Menu", "en"), ("Rice", "en"), ("Set", "en"))
# what Vision reads in outputs the judge condemns (the OCR text is only a warning now)
DIGITS = ocr(("TEL", "en"), ("09446916", "en"), ("Hours", "en"), ("Menu", "en"), ("Rice", "en"), ("Set", "en"))
PARTIAL = ocr(("TEL", "en"), ("77446716", "en"), ("営業時間", "ja"), ("Menu", "en"), ("Rice", "en"), ("Set", "en"))

NUMBERS_JUDGE = judge_record(usability=0.9, altered=[("77446716", "09446916")], cost=0.02)
PARTIAL_JUDGE = judge_record(usability=0.88, untranslated=["営業時間"], cost=0.02)
GARBLED_JUDGE = judge_record(usability=0.35, garbled=[("middle column", "Banero Salaino Az-infe", True)], cost=0.02)


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setenv("LAB_MOCK", "1")  # offline publishing (in-memory fake); the model, OCR and judge calls are scripted
    monkeypatch.setenv("LAB_DATA_DIR", str(tmp_path / "data"))
    return TestClient(create_app())


def post(client, models, source=SOURCE, **extra):
    data = {"target_lang": "en", "mode_a": "0", "mode_b": "1", "image_models": models}
    data.update(extra)
    return client.post("/api/jobs", files={"file": ("a.png", source, "image/png")}, data=data)


def run(client, monkeypatch, plan, models=None, source_ocr=SRC_OCR, barrier=None, **extra):
    scenario = Scenario(SOURCE, source_ocr, plan, barrier=barrier).install(monkeypatch)
    r = post(client, models or list(plan), **extra)
    assert r.status_code == 200, r.text
    return scenario, wait_for(client, r.json()["job_id"])


def step(out, **kw):
    return {"ocr": out, **kw}


# ---------------------------------------------------------------- fan-out


def test_models_run_in_parallel_one_result_each_and_a_failing_model_does_not_affect_the_others(client, monkeypatch):
    plan = {
        "m/alpha": [step(GOOD, cost=0.04, ms=1500)],
        "m/broken": [step(None, fail=RuntimeError("model m/broken exploded"))],
        "m/gamma": [step(GOOD, cost=0.03, ms=900, size=(160, 120))],
    }
    # all three generations must be in flight at once, or the barrier times out and the job fails
    scenario, job = run(client, monkeypatch, plan, barrier=threading.Barrier(3))

    results = job["mode_b"]["results"]
    assert [r["model"] for r in results] == ["m/alpha", "m/broken", "m/gamma"]
    assert [r["status"] for r in results] == ["done", "error", "done"]
    assert "exploded" in results[1]["error"] and results[1]["tries"] == []
    assert job["mode_b"]["status"] == "done" and job["status"] == "done"  # one failure is not the job's failure
    assert job["mode_a"]["status"] == "skipped"

    alpha, gamma = results[0], results[2]
    assert alpha["verdict"] == "pass" and gamma["verdict"] == "pass"
    assert alpha["cost_usd"] == pytest.approx(0.04) and gamma["cost_usd"] == pytest.approx(0.03)
    assert alpha["output_size"] == [80, 60] and gamma["output_size"] == [160, 120]
    assert alpha["output"] != gamma["output"]
    for r in (alpha, gamma):
        for field in ("elapsed_ms", "generation_ms", "verify_ms", "route_preference", "api_used", "aspect_drift_pct", "usage"):
            assert field in r, field
        img = client.get(f"/files/{job['job_id']}/{r['output']}")
        assert img.status_code == 200 and img.headers["content-type"] == "image/png"
    assert gamma["aspect_drift_pct"] == 0.0
    assert alpha["elapsed_ms"] >= 1500 and gamma["generation_ms"] == 900


def test_all_models_failing_makes_mode_b_an_error_but_not_the_job(client, monkeypatch):
    plan = {"m/a": [step(None, fail=RuntimeError("a down"))], "m/b": [step(None, fail=RuntimeError("b down"))]}
    _, job = run(client, monkeypatch, plan)
    assert job["mode_b"]["status"] == "error" and job["status"] == "done"
    assert [r["status"] for r in job["mode_b"]["results"]] == ["error", "error"]


def test_the_source_is_ocr_ed_once_and_shared_by_mode_a_and_every_model(client, monkeypatch):
    fixture = mock.make_fixture()
    buf = io.BytesIO()
    fixture.image.save(buf, "PNG")
    source_bytes = buf.getvalue()
    same = mock.vision_words(640, 480)
    scenario = Scenario(source_bytes, same, {"m/a": [step(same)], "m/b": [step(same)]}).install(monkeypatch)
    r = client.post(
        "/api/jobs",
        files={"file": ("fixture.png", source_bytes, "image/png")},
        data={"target_lang": "en", "mode_a": "1", "mode_b": "1", "image_models": ["m/a", "m/b"]},
    )
    job = wait_for(client, r.json()["job_id"])
    assert job["mode_a"]["status"] == "done", job["mode_a"].get("error")
    assert [x["verdict"] for x in job["mode_b"]["results"]] == ["pass", "pass"]
    assert job["mode_a"]["verdict"] == "pass"  # Mode A goes through the same judge
    assert scenario.source_ocr_calls == 1  # Mode A + two models' checks: one Vision call on the source
    assert len(scenario.ocr_calls) == 3  # one per output image: two models and the Mode A output
    assert len(scenario.judge_calls) == 3


def test_mode_b_alone_prefetches_the_source_ocr_once_for_all_models(client, monkeypatch):
    plan = {"m/a": [step(GOOD)], "m/b": [step(GOOD)], "m/c": [step(GOOD)]}
    scenario, job = run(client, monkeypatch, plan)
    assert scenario.source_ocr_calls == 1 and len(scenario.ocr_calls) == 3
    assert job["mode_a"]["status"] == "skipped"


def test_mode_a_is_optional_and_off_unless_asked_for_through_the_api(client, monkeypatch):
    Scenario(SOURCE, SRC_OCR, {"m/a": [step(GOOD)]}).install(monkeypatch)
    r = client.post("/api/jobs", files={"file": ("a.png", SOURCE, "image/png")}, data={"mode_b": "1", "image_models": "m/a"})
    job = wait_for(client, r.json()["job_id"])
    assert job["mode_a"]["status"] == "skipped" and job["params"]["mode_a"] is False
    assert job["params"]["verify"] is True  # verification is on by default


# ---------------------------------------------------------------- the judge sees the pictures


def test_the_judge_gets_the_source_ocr_the_original_and_the_output_picture(client, monkeypatch):
    scenario, job = run(client, monkeypatch, {"m/a": [step(GOOD, size=(160, 120))]})
    (call,) = scenario.judge_calls
    assert call["source"] == "TEL 77446716 営業時間 メニュー ご飯 定食" and call["lang"] == "en"
    assert call["source_size"] == (80, 60)  # the original, as uploaded
    (result,) = job["mode_b"]["results"]
    assert call["output_png"] == client.get(f"/files/{job['job_id']}/{result['output']}").content  # the output, not its OCR
    assert result["verification"]["judge"]["status"] == "ok"


def test_the_judge_works_from_the_pictures_alone_when_vision_cannot_read_the_source(client, monkeypatch):
    from app import vision
    from app.models import ModeError

    scenario = Scenario(SOURCE, SRC_OCR, {"m/a": [step(GOOD)]}).install(monkeypatch)
    real = vision.ocr_words

    def source_fails(image_bytes, width, height):
        if image_bytes == SOURCE:
            raise ModeError("Google Vision credentials are not usable")
        return real(image_bytes, width, height)

    monkeypatch.setattr(vision, "ocr_words", source_fails)
    job = wait_for(client, post(client, ["m/a"]).json()["job_id"])
    (res,) = job["mode_b"]["results"]
    assert res["status"] == "done" and res["verdict"] == "pass"  # the judge looked at the two pictures
    assert scenario.judge_calls[0]["source"] is None  # no OCR aid for it
    assert res["verification"]["ocr_warnings"]["status"] == "unavailable"


def test_a_vision_failure_costs_only_the_ocr_warnings_the_judge_still_decides(client, monkeypatch):
    scenario, job = run(client, monkeypatch, {"m/a": [step(RuntimeError("Google Vision credentials are not usable"))]})
    (r,) = job["mode_b"]["results"]
    assert r["status"] == "done" and r["verdict"] == "pass" and r["verify_error"] is None
    assert r["verification"]["ocr_warnings"]["status"] == "unavailable"
    assert "credentials" in r["verification"]["ocr_warnings"]["error"]
    assert len(scenario.calls) == 1 and len(scenario.judge_calls) == 1
    assert client.get(f"/files/{job['job_id']}/{r['output']}").status_code == 200


# ---------------------------------------------------------------- verdicts and the one retry


def test_a_failed_check_triggers_one_retry_and_the_better_attempt_is_the_result(client, monkeypatch):
    plan = {"m/a": [step(DIGITS, cost=0.05, ms=2000, judge=NUMBERS_JUDGE), step(GOOD, cost=0.06, ms=3000)]}
    scenario, job = run(client, monkeypatch, plan)
    (r,) = job["mode_b"]["results"]

    assert len(scenario.calls) == 2
    first, second = scenario.calls
    assert first["prompt"] is None  # the default prompt
    assert second["prompt"].startswith(openrouter.build_prompt("en") + "\n\n")
    assert '"77446716" (your output has "09446916"' in second["prompt"]  # what the judge found, to be copied exactly
    assert "digit sequences" not in second["prompt"]  # the OCR checks never write the retry

    t1, t2 = r["tries"]
    assert (t1["n"], t1["verdict"], t1["selected"]) == (1, "numbers", False)
    assert (t2["n"], t2["verdict"], t2["selected"]) == (2, "pass", True)
    # the OCR digit check saw the same change, but only as a warning
    assert t1["verification"]["ocr_warnings"]["missing_digit_runs"] == ["77446716"]
    assert t1["verification"]["failed_checks"] == ["numbers"] and t2["verification"]["ocr_warnings"]["warnings"] == []
    assert r["verdict"] == "pass" and r["selected_attempt"] == 2 and r["output"] == t2["output"]
    assert t1["output"] != t2["output"]
    for t in (t1, t2):  # both attempts' images and verification files stay available
        assert client.get(f"/files/{job['job_id']}/{t['output']}").status_code == 200
        assert client.get(f"/files/{job['job_id']}/{t['verification_file']}").status_code == 200
    # cost and time of both attempts are summed
    assert r["cost_usd"] == pytest.approx(0.11) and r["usage"]["prompt_tokens"] == 200 and r["usage"]["completion_tokens"] == 400
    assert r["generation_ms"] == 5000 and r["elapsed_ms"] == 5000 + r["verify_ms"]
    assert scenario.source_ocr_calls == 1 and len(scenario.ocr_calls) == 2


def test_the_ocr_warnings_never_trigger_a_retry_or_write_the_retry_prompt(client, monkeypatch):
    # the output OCR shows a changed number and an untranslated title, but the judge looked at the picture and is happy
    scenario, job = run(client, monkeypatch, {"m/a": [step(PARTIAL)], "m/b": [step(DIGITS)]})
    for r in job["mode_b"]["results"]:
        assert r["verdict"] == "pass" and len(r["tries"]) == 1 and r["retry_error"] is None
    assert len(scenario.calls) == 2  # no second generation anywhere
    warnings = {r["model"]: r["verification"]["ocr_warnings"]["warnings"] for r in job["mode_b"]["results"]}
    assert warnings["m/b"] == ["digits-missing"] and "leftover-ratio" in warnings["m/a"]


def test_retry_prompt_names_the_major_untranslated_text_the_judge_found(client, monkeypatch):
    plan = {"m/a": [step(PARTIAL, judge=PARTIAL_JUDGE), step(GOOD)]}
    scenario, job = run(client, monkeypatch, plan)
    prompt = scenario.calls[1]["prompt"]
    assert '"営業時間"' in prompt and "still in the source language" in prompt and "digit sequences" not in prompt
    assert job["mode_b"]["results"][0]["tries"][0]["verdict"] == "partial"


def test_a_worse_retry_is_not_preferred(client, monkeypatch):
    # attempt 1 left a title untranslated (partial); attempt 2 translated it but changed a number
    plan = {"m/a": [step(PARTIAL, cost=0.05, judge=PARTIAL_JUDGE), step(DIGITS, cost=0.05, judge=NUMBERS_JUDGE)]}
    _, job = run(client, monkeypatch, plan)
    (r,) = job["mode_b"]["results"]
    assert [t["verdict"] for t in r["tries"]] == ["partial", "numbers"]
    assert r["verdict"] == "partial" and r["selected_attempt"] == 1
    assert [t["selected"] for t in r["tries"]] == [True, False]
    assert r["cost_usd"] == pytest.approx(0.10)  # the discarded attempt still costs money


def test_numbers_changed_in_both_attempts_still_shows_the_attempt_with_fewer_problems(client, monkeypatch):
    both = judge_record(usability=0.9, altered=[("77446716", "09446916")], untranslated=["営業時間"])
    plan = {"m/a": [step(DIGITS, judge=both), step(DIGITS, judge=NUMBERS_JUDGE)]}
    _, job = run(client, monkeypatch, plan)
    (r,) = job["mode_b"]["results"]
    assert [t["verdict"] for t in r["tries"]] == ["numbers+partial", "numbers"]
    assert r["selected_attempt"] == 2 and r["verdict"] == "numbers"


def test_a_passing_first_attempt_is_not_retried(client, monkeypatch):
    scenario, job = run(client, monkeypatch, {"m/a": [step(GOOD)]})
    assert len(scenario.calls) == 1
    (r,) = job["mode_b"]["results"]
    assert len(r["tries"]) == 1 and r["verdict"] == "pass" and r["retry_error"] is None


def test_a_failing_retry_keeps_the_first_attempt(client, monkeypatch):
    plan = {"m/a": [step(PARTIAL, cost=0.05, judge=PARTIAL_JUDGE), step(None, fail=RuntimeError("retry blew up"))]}
    _, job = run(client, monkeypatch, plan)
    (r,) = job["mode_b"]["results"]
    assert r["status"] == "done" and r["verdict"] == "partial" and r["selected_attempt"] == 1
    assert "retry blew up" in r["retry_error"] and len(r["tries"]) == 1


def test_a_garbled_attempt_never_beats_a_clean_one_whichever_comes_first(client, monkeypatch):
    plan = {"m/a": [step(GOOD, judge=GARBLED_JUDGE), step(GOOD, judge=GARBLED_JUDGE)]}
    scenario, job = run(client, monkeypatch, plan)
    (res,) = job["mode_b"]["results"]
    assert [t["verdict"] for t in res["tries"]] == ["garbled+score", "garbled+score"] and res["selected_attempt"] == 1  # tie: the earlier
    assert len(scenario.calls) == 2
    assert 'readable English text: middle column: "Banero Salaino Az-infe"' in scenario.calls[1]["prompt"]


def test_an_unavailable_judge_leaves_the_attempt_unverified_and_never_retries_it(client, monkeypatch):
    unavailable = judge_record(status="unavailable", error="Claude API error 500: upstream down", cost=None)
    scenario, job = run(client, monkeypatch, {"m/a": [step(DIGITS, cost=0.04, judge=unavailable)]})
    (res,) = job["mode_b"]["results"]
    assert res["status"] == "done" and res["verdict"] == "unverified" and len(res["tries"]) == 1 and len(scenario.calls) == 1
    (t,) = res["tries"]
    assert t["judge_status"] == "unavailable" and t["verification"]["judge"]["status"] == "unavailable"
    assert t["verification"]["usability"] is None and t["verify_error"] is None
    assert t["verification"]["ocr_warnings"]["missing_digit_runs"] == ["77446716"]  # the warnings are still there
    assert res["judge_cost_usd"] is None and res["total_cost_usd"] == pytest.approx(0.04)


def test_the_judge_can_be_switched_off_and_then_there_is_no_verdict_to_trust(client, monkeypatch):
    scenario, job = run(client, monkeypatch, {"m/a": [step(GOOD)]}, judge="0")
    (res,) = job["mode_b"]["results"]
    assert scenario.judge_calls == [] and job["params"]["judge"] is False and res["judge"] is False
    assert res["verification"]["judge"] is None and res["tries"][0]["judge_status"] is None
    assert res["verdict"] == "unverified" and len(scenario.calls) == 1 and res["verification"]["ocr_warnings"]["status"] == "ok"
    scenario, job = run(client, monkeypatch, {"m/a": [step(GOOD)]}, verify="0")
    assert scenario.judge_calls == [] and job["params"]["judge"] is False  # no check, no judge
    scenario, job = run(client, monkeypatch, {"m/a": [step(GOOD)]})
    assert len(scenario.judge_calls) == 1 and job["params"]["judge"] is True and job["params"]["judge_model"] == "claude-sonnet-5-5"


def test_a_big_leftover_word_is_only_an_ocr_warning_now(client, monkeypatch):
    src = ocr(*[("こんにちは", "ja")] * 100, ("TEL", "en"), ("77446716", "en"))
    big = ocr(*[("Hello", "en")] * 99, ("メニュー", "ja", 0.9, 40), ("TEL", "en"), ("77446716", "en"))
    scenario = Scenario(SOURCE, src, {"m/a": [step(big)]}).install(monkeypatch)
    job = wait_for(client, post(client, ["m/a"]).json()["job_id"])
    (res,) = job["mode_b"]["results"]
    assert res["verdict"] == "pass" and len(res["tries"]) == 1 and len(scenario.calls) == 1
    warnings = res["verification"]["ocr_warnings"]
    assert warnings["warnings"] == ["leftover-big-glyph"] and warnings["big_leftover_words"][0]["word"] == "メニュー"


def test_the_ocr_confidence_reaches_the_digit_warning_through_the_job(client, monkeypatch):
    # the round-3 false alarm: Vision read the handwritten 77446716 as 97446716 (confidence 0.5); the model had it right
    src = ocr(("TEL", "en", 0.99), ("97446716", "en", 0.5), ("営業時間", "ja"), ("メニュー", "ja"), ("ご飯", "ja"), ("定食", "ja"))
    scenario = Scenario(SOURCE, src, {"m/a": [step(GOOD)]}).install(monkeypatch)
    job = wait_for(client, post(client, ["m/a"]).json()["job_id"])
    (res,) = job["mode_b"]["results"]
    assert res["verdict"] == "pass" and len(res["tries"]) == 1 and len(scenario.calls) == 1  # no needless retry
    run_info = res["verification"]["ocr_warnings"]["digit_runs"][0]
    assert (run_info["soft"], run_info["status"], run_info["matched"]) == (True, "near", "77446716")


def test_ocr_text_is_only_kept_when_details_are_asked_for(client, monkeypatch):
    _, job = run(client, monkeypatch, {"m/a": [step(GOOD)]})
    assert "output_text" not in job["mode_b"]["results"][0]["verification"]["ocr_warnings"]
    _, job = run(client, monkeypatch, {"m/a": [step(GOOD)]}, verify_details="1")
    assert job["mode_b"]["results"][0]["verification"]["ocr_warnings"]["output_text"].startswith("TEL 77446716")
    assert job["params"]["verify_details"] is True


def test_verification_can_be_switched_off_through_the_api(client, monkeypatch):
    scenario, job = run(client, monkeypatch, {"m/a": [step(PARTIAL)]}, verify="0")
    (r,) = job["mode_b"]["results"]
    assert r["verdict"] is None and r["verification"] is None and r["verify"] is False
    assert scenario.source_ocr_calls == 0 and scenario.ocr_calls == [] and len(scenario.calls) == 1


def test_the_cost_of_a_run_is_split_into_openrouter_cash_and_anthropic_credit(client, monkeypatch):
    plan = {"m/a": [step(DIGITS, cost=0.05, judge=NUMBERS_JUDGE), step(GOOD, cost=0.06)]}
    _, job = run(client, monkeypatch, plan)
    (r,) = job["mode_b"]["results"]
    assert r["cost_openrouter_usd"] == pytest.approx(0.11) == pytest.approx(r["cost_usd"])
    assert r["cost_anthropic_usd"] == pytest.approx(0.03) == pytest.approx(r["judge_cost_usd"])  # 0.02 + 0.01
    assert r["total_cost_usd"] == pytest.approx(0.14)
    assert r["usability"] == 1.0 and r["coverage"] == 1.0  # the selected attempt's scores


# ---------------------------------------------------------------- request validation and the size option


def test_at_most_four_models_and_duplicates_are_merged(client, monkeypatch):
    Scenario(SOURCE, SRC_OCR, {}).install(monkeypatch)
    five = [f"m/{i}" for i in range(5)]
    assert post(client, five).status_code == 400
    r = post(client, ["m/0", "m/1", "m/2", "m/3"])
    assert r.status_code == 200
    wait_for(client, r.json()["job_id"])  # every model errors (nothing scripted), the job still ends
    plan = {m: [step(GOOD)] for m in ("m/a", "m/b")}
    scenario = Scenario(SOURCE, SRC_OCR, plan).install(monkeypatch)
    r = client.post(
        "/api/jobs",
        files={"file": ("a.png", SOURCE, "image/png")},
        data={"mode_b": "1", "image_models": ["m/a,m/b", "m/a"], "image_model": "m/b"},
    )
    job = wait_for(client, r.json()["job_id"])
    assert [x["model"] for x in job["mode_b"]["results"]] == ["m/a", "m/b"]
    assert len(scenario.calls) == 2


def test_the_legacy_single_model_field_still_works_and_empty_means_the_default(client, monkeypatch):
    default = "google/gemini-3-pro-image"
    scenario = Scenario(SOURCE, SRC_OCR, {"m/legacy": [step(GOOD)], default: [step(GOOD)]}).install(monkeypatch)
    r = client.post("/api/jobs", files={"file": ("a.png", SOURCE, "image/png")}, data={"mode_b": "1", "image_model": "m/legacy"})
    assert wait_for(client, r.json()["job_id"])["params"]["image_models"] == ["m/legacy"]
    r = client.post("/api/jobs", files={"file": ("a.png", SOURCE, "image/png")}, data={"mode_b": "1"})
    assert wait_for(client, r.json()["job_id"])["params"]["image_models"] == [default]
    assert [c["model"] for c in scenario.calls] == ["m/legacy", default]


def test_invalid_model_id_in_the_list_is_rejected(client, monkeypatch):
    Scenario(SOURCE, SRC_OCR, {}).install(monkeypatch)
    assert post(client, ["m/ok", "bad model!"]).status_code == 400


@pytest.mark.parametrize("size, sent", [("1K", "1K"), ("2K", "2K"), ("4K", "4K"), ("default", "")])
def test_output_size_reaches_the_image_call_and_the_result(client, monkeypatch, size, sent):
    scenario, job = run(client, monkeypatch, {"m/a": [step(GOOD)]}, output_size=size)
    assert scenario.calls[0]["resolution"] == sent
    assert job["params"]["output_size"] == sent
    assert job["mode_b"]["results"][0]["resolution_requested"] == sent


def test_the_output_size_defaults_to_2K_when_it_is_left_out_or_empty(client, monkeypatch):
    scenario, job = run(client, monkeypatch, {"m/a": [step(GOOD)]})
    assert scenario.calls[0]["resolution"] == "2K" and job["params"]["output_size"] == "2K"
    scenario, job = run(client, monkeypatch, {"m/a": [step(GOOD)]}, output_size="")  # an empty form value is "not given"
    assert scenario.calls[0]["resolution"] == "2K"


def test_unknown_output_size_is_rejected(client, monkeypatch):
    Scenario(SOURCE, SRC_OCR, {}).install(monkeypatch)
    assert post(client, ["m/a"], output_size="8K").status_code == 400


def test_index_page_offers_the_size_option_the_multi_model_picker_and_the_round_5_defaults(client):
    html = client.get("/").text
    for needle in ("預設", ">1K<", ">2K<", ">4K<", "最多 4 個", "顯示驗證細節", "給審閱者的連結", 'id="mode-a"'):
        assert needle in html, needle
    assert 'id="mode-a" checked' in html and 'id="mode-b" checked' in html  # round 5: both modes on
    assert 'id="mode-a-retry" checked' in html  # the Mode A retry is on by default (MODE_A_RETRY)
    assert 'data-default-model="google/gemini-3-pro-image"' in html  # Pro is preselected; the multi-select stays
    assert "multiple" in html and 'id="file"' in html and 'data-max-batch="4"' in html


def test_index_page_has_the_batch_summary_the_judge_controls_and_defaults_to_2K(client):
    html = client.get("/").text
    assert "每個模型連跑 N 次" in html and 'id="runs"' in html
    assert '<option value="1" selected>1</option>' in html and '<option value="3">3</option>' in html
    assert '<option value="4"' not in html  # 1 to 3 runs
    assert '<option value="2K" selected>2K</option>' in html and '<option value="1K" selected' not in html
    assert '<option value="default">' in html  # the provider's own size is still selectable
    assert 'id="judge" checked' in html and "claude-sonnet-5-5" in html
    assert "最多 4 個生成" in html and "Claude 呼叫" in html and 'id="summary"' in html and 'id="batch-share"' in html
    for needle in ("Anthropic（額度）", "OpenRouter（現金）", "批次彙總", "批次 manifest", "OCR 警告", "numbers", "rows", "garbled", "partial", "score"):
        assert needle in html, needle
    assert html.index('id="summary"') < html.index('id="batch-share"') < html.index('id="jobs"')  # the table comes first


def test_models_endpoint_carries_the_route_preference(client):
    body = client.get("/api/image-models").json()
    assert body["details"]["mock/image-model"]["preferred_route"] == "chat"
    assert all("preferred_route" in d for d in body["details"].values())


# ---------------------------------------------------------------- batch runs of one model


def batch_job(client, monkeypatch, plan, *, runs, models=None, **kw):
    scenario = Scenario(SOURCE, SRC_OCR, plan, **kw).install(monkeypatch)
    r = post(client, models or list(plan), runs=str(runs))
    assert r.status_code == 200, r.text
    return scenario, wait_for(client, r.json()["job_id"])


def test_every_run_is_its_own_independent_column_with_its_own_files(client, monkeypatch):
    plan = {"m/a": [step(GOOD, cost=0.04) for _ in range(3)], "m/b": [step(GOOD, cost=0.05) for _ in range(3)]}
    scenario, job = batch_job(client, monkeypatch, plan, runs=3)
    results = job["mode_b"]["results"]
    assert len(results) == 6 and len(scenario.calls) == 6 and job["params"]["runs"] == 3
    # flat and model-major, one entry per (model, run)
    assert [(r["model"], r["model_index"], r["run"], r["runs"], r["index"]) for r in results] == [
        ("m/a", 0, 1, 3, 0), ("m/a", 0, 2, 3, 1), ("m/a", 0, 3, 3, 2),
        ("m/b", 1, 1, 3, 3), ("m/b", 1, 2, 3, 4), ("m/b", 1, 3, 3, 5),
    ]  # fmt: skip
    assert all(r["status"] == "done" and r["verdict"] == "pass" for r in results)
    names = [r["output"] for r in results]
    assert names == [f"B{m}_m_{x}_r{k}_a1.png" for m, x in ((1, "a"), (2, "b")) for k in (1, 2, 3)]
    assert len(set(names)) == 6
    for r in results:  # own attempts, verification file, judge
        assert len(r["tries"]) == 1 and r["tries"][0]["verification_file"] == r["output"].replace(".png", "_verify.json")
        assert client.get(f"/files/{job['job_id']}/{r['output']}").status_code == 200
        assert client.get(f"/files/{job['job_id']}/{r['tries'][0]['verification_file']}").status_code == 200
    assert job["mode_b"]["status"] == "done" and job["status"] == "done"
    assert scenario.source_ocr_calls == 1 and len(scenario.ocr_calls) == 6 and len(scenario.judge_calls) == 6


def test_the_summary_has_one_row_per_model_with_pass_rate_means_and_the_best_run(client, monkeypatch):
    plan = {
        "m/a": [step(GOOD, cost=0.04, ms=1000) for _ in range(3)],
        "m/b": [step(GOOD, cost=0.06, ms=3000), step(None, fail=RuntimeError("m/b blew up")), step(GOOD, cost=0.06, ms=3000)],
    }
    _, job = batch_job(client, monkeypatch, plan, runs=3)
    a, b = job["mode_b"]["summary"]
    assert (a["model"], a["runs_requested"], a["runs_done"], a["runs_failed"]) == ("m/a", 3, 3, 0)
    assert a["passes"] == 3 and a["pass_rate"] == 1.0 and a["best_run"] == 1 and a["best_verdict"] == "pass"
    # per run total cost = generation + judge ($0.01 scripted per attempt)
    assert a["mean_total_cost_usd"] == pytest.approx(0.05) and a["total_cost_usd"] == pytest.approx(0.15)
    assert a["mean_elapsed_ms"] >= 1000

    # one run of m/b failed: the others finished, and the failure counts against the pass rate
    assert (b["runs_done"], b["runs_failed"], b["runs_live"]) == (2, 1, 0)
    assert b["passes"] == 2 and b["pass_rate"] == pytest.approx(2 / 3, abs=1e-4)
    assert sorted(b["verdicts"]) == ["error", "pass", "pass"]
    assert b["mean_total_cost_usd"] == pytest.approx(0.07) and b["mean_elapsed_ms"] >= 3000
    failed = [r for r in job["mode_b"]["results"] if r["model"] == "m/b" and r["status"] == "error"]
    assert len(failed) == 1 and "blew up" in failed[0]["error"] and failed[0]["tries"] == []
    assert b["best_run"] in (r["run"] for r in job["mode_b"]["results"] if r["model"] == "m/b" and r["status"] == "done")
    assert job["mode_b"]["status"] == "done" and job["status"] == "done"  # a failed run is not a failed job


def test_a_single_run_keeps_the_round_3_shape_and_still_has_a_summary(client, monkeypatch):
    _, job = run(client, monkeypatch, {"m/a": [step(GOOD, cost=0.04)]})
    (r,) = job["mode_b"]["results"]
    assert (r["run"], r["runs"], r["model_index"], r["index"]) == (1, 1, 0, 0) and job["params"]["runs"] == 1
    assert r["output"] == "B1_m_a_r1_a1.png"
    (s,) = job["mode_b"]["summary"]
    assert s["pass_rate"] == 1.0 and s["runs_requested"] == 1 and s["best_run"] == 1


def test_a_run_with_a_failed_check_retries_on_its_own_and_does_not_touch_the_other_runs(client, monkeypatch):
    # every first attempt is judged "numbers", every retry is clean: 2 runs = 4 generations
    steps = [step(DIGITS, cost=0.05, judge=NUMBERS_JUDGE), step(DIGITS, cost=0.05, judge=NUMBERS_JUDGE), step(GOOD, cost=0.06), step(GOOD, cost=0.06)]
    scenario, job = batch_job(client, monkeypatch, {"m/a": steps}, runs=2)
    first_prompts = [c["prompt"] for c in scenario.calls]
    assert first_prompts[:2] == [None, None] and all("77446716" in p for p in first_prompts[2:])
    for r in job["mode_b"]["results"]:
        assert [t["verdict"] for t in r["tries"]] == ["numbers", "pass"] and r["selected_attempt"] == 2
        assert r["cost_usd"] == pytest.approx(0.11)
    assert job["mode_b"]["summary"][0]["pass_rate"] == 1.0


def test_at_most_four_generations_are_in_flight_at_once_whatever_models_times_runs_asks_for(client, monkeypatch):
    plan = {m: [step(GOOD, sleep=0.2) for _ in range(3)] for m in ("m/a", "m/b", "m/c")}  # 3 models x 3 runs
    scenario, job = batch_job(client, monkeypatch, plan, runs=3)
    assert len(scenario.calls) == 9 and all(r["status"] == "done" for r in job["mode_b"]["results"])
    assert scenario.peak == 4  # parallel, but never more than 4 (the 5th..9th wait for a slot)


def test_four_models_of_one_run_each_still_run_together(client, monkeypatch):
    plan = {f"m/{c}": [step(GOOD)] for c in "abcd"}
    # the barrier times out (and the runs fail) unless all four generations are in flight at the same moment
    scenario, job = batch_job(client, monkeypatch, plan, runs=1, barrier=threading.Barrier(4))
    assert [r["verdict"] for r in job["mode_b"]["results"]] == ["pass"] * 4


def test_a_failing_generation_gives_its_slot_back(client, monkeypatch):
    from app import jobs

    plan = {"m/a": [step(None, fail=RuntimeError("x")) for _ in range(6)]}
    batch_job(client, monkeypatch, plan, runs=3, models=["m/a"])
    # all slots are free again: 4 can be taken without blocking
    held = [jobs._GENERATION_SLOTS.acquire(blocking=False) for _ in range(4)]
    assert all(held) and not jobs._GENERATION_SLOTS.acquire(blocking=False)
    for _ in held:
        jobs._GENERATION_SLOTS.release()


@pytest.mark.parametrize("runs, status", [(0, 400), (4, 400), (-1, 400), (1, 200), (3, 200)])
def test_runs_must_be_between_one_and_three(client, monkeypatch, runs, status):
    Scenario(SOURCE, SRC_OCR, {"m/a": [step(GOOD) for _ in range(3)]}).install(monkeypatch)
    r = post(client, ["m/a"], runs=str(runs))
    assert r.status_code == status
    if status == 400:
        assert "runs must be between 1 and 3" in r.json()["detail"]
    else:
        wait_for(client, r.json()["job_id"])


def test_runs_default_to_one_and_a_non_number_is_rejected(client, monkeypatch):
    scenario, job = run(client, monkeypatch, {"m/a": [step(GOOD)]})
    assert job["params"]["runs"] == 1 and len(job["mode_b"]["results"]) == 1
    Scenario(SOURCE, SRC_OCR, {}).install(monkeypatch)
    assert post(client, ["m/a"], runs="many").status_code == 422


# ---------------------------------------------------------------- round 7: structure_checks and the OCR switch of check_output


def test_check_output_takes_rows_out_of_the_verdict_for_mode_a_and_can_skip_the_output_ocr(monkeypatch):
    from app import jobs
    from app.imaging import load_image

    image = load_image(SOURCE)
    out_png = png_of_size((80, 60), (1, 2, 3))
    scenario = Scenario(SOURCE, SRC_OCR, {}).install(monkeypatch)
    scenario.outputs[out_png] = GOOD
    scenario.judge_by_png[out_png] = judge_record(0.95, rows=[("missing_row", "the fourth dish")])
    opts = {"judge": True, "verify_details": False}
    source = jobs.SourceOcr(image)

    mode_b = jobs.check_output(source, image, out_png, (80, 60), "en", opts)
    mode_a = jobs.check_output(source, image, out_png, (80, 60), "en", opts, structure_checks=False)
    assert mode_b["verdict"] == "rows" and mode_a["verdict"] == "pass"
    assert mode_a["verification"]["structure_checks"] is False and len(mode_a["verification"]["structure_warnings"]) == 1
    assert scenario.ocr_calls == [out_png, out_png]  # the output was read twice, once per check

    skipped = jobs.check_output(source, image, out_png, (80, 60), "en", {**opts, "ocr": False})
    assert skipped["verdict"] == "rows" and scenario.ocr_calls == [out_png, out_png]  # no third Vision call
    assert skipped["verification"]["ocr_warnings"]["status"] == "unavailable"
    assert "not run" in skipped["verification"]["ocr_warnings"]["error"]
    assert scenario.source_ocr_calls == 1  # and the source was still read only once
