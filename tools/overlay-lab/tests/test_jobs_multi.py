"""Mode B through the HTTP API: several models in parallel, verification, the one retry, size option."""

import io
import threading

import pytest
from fastapi.testclient import TestClient
from scenario import Scenario, ocr, png_of_size, wait_for

from app import mock, openrouter
from app.main import create_app

SOURCE = png_of_size((80, 60))
SRC_OCR = ocr(("TEL", "en"), ("77446716", "en"), ("営業時間", "ja"), ("メニュー", "ja"), ("ご飯", "ja"), ("定食", "ja"))
GOOD = ocr(("TEL", "en"), ("77446716", "en"), ("Hours", "en"), ("Menu", "en"), ("Rice", "en"), ("Set", "en"))
DIGITS = ocr(("TEL", "en"), ("09446916", "en"), ("Hours", "en"), ("Menu", "en"), ("Rice", "en"), ("Set", "en"))
PARTIAL = ocr(("TEL", "en"), ("77446716", "en"), ("営業時間", "ja"), ("Menu", "en"), ("Rice", "en"), ("Set", "en"))
BOTH = ocr(("TEL", "en"), ("09446916", "en"), ("営業時間", "ja"), ("Menu", "en"), ("Rice", "en"), ("Set", "en"))


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setenv("LAB_MOCK", "1")  # offline publishing (in-memory fake); the model and OCR calls are scripted
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
    assert scenario.source_ocr_calls == 1  # Mode A + two models' verification: one Vision call
    assert len(scenario.ocr_calls) == 2  # one per output image


def test_mode_b_alone_prefetches_the_source_ocr_once_for_all_models(client, monkeypatch):
    plan = {"m/a": [step(GOOD)], "m/b": [step(GOOD)], "m/c": [step(GOOD)]}
    scenario, job = run(client, monkeypatch, plan)
    assert scenario.source_ocr_calls == 1 and len(scenario.ocr_calls) == 3
    assert job["mode_a"]["status"] == "skipped"


def test_mode_a_is_optional_and_off_by_default(client, monkeypatch):
    Scenario(SOURCE, SRC_OCR, {"m/a": [step(GOOD)]}).install(monkeypatch)
    r = client.post("/api/jobs", files={"file": ("a.png", SOURCE, "image/png")}, data={"mode_b": "1", "image_models": "m/a"})
    job = wait_for(client, r.json()["job_id"])
    assert job["mode_a"]["status"] == "skipped" and job["params"]["mode_a"] is False
    assert job["params"]["verify"] is True  # verification is on by default


# ---------------------------------------------------------------- verification and the one retry


def test_a_failed_check_triggers_one_retry_and_the_better_attempt_is_the_result(client, monkeypatch):
    plan = {"m/a": [step(DIGITS, cost=0.05, ms=2000), step(GOOD, cost=0.06, ms=3000)]}
    scenario, job = run(client, monkeypatch, plan)
    (r,) = job["mode_b"]["results"]

    assert len(scenario.calls) == 2
    first, second = scenario.calls
    assert first["prompt"] is None  # the default prompt
    assert second["prompt"].startswith(openrouter.build_prompt("en") + "\n\n")
    assert "77446716" in second["prompt"] and "Hours" not in second["prompt"]  # names the digits, no leftover words

    t1, t2 = r["tries"]
    assert (t1["n"], t1["verdict"], t1["selected"]) == (1, "digits-changed", False)
    assert (t2["n"], t2["verdict"], t2["selected"]) == (2, "pass", True)
    assert t1["verification"]["missing_digit_runs"] == ["77446716"]
    assert r["verdict"] == "pass" and r["selected_attempt"] == 2 and r["output"] == t2["output"]
    assert t1["output"] != t2["output"]
    for t in (t1, t2):  # both attempts' images and verification files stay available
        assert client.get(f"/files/{job['job_id']}/{t['output']}").status_code == 200
        assert client.get(f"/files/{job['job_id']}/{t['verification_file']}").status_code == 200
    # cost and time of both attempts are summed
    assert r["cost_usd"] == pytest.approx(0.11) and r["usage"]["prompt_tokens"] == 200 and r["usage"]["completion_tokens"] == 400
    assert r["generation_ms"] == 5000 and r["elapsed_ms"] == 5000 + r["verify_ms"]
    assert scenario.source_ocr_calls == 1 and len(scenario.ocr_calls) == 2


def test_retry_prompt_names_leftover_words_when_the_translation_is_partial(client, monkeypatch):
    plan = {"m/a": [step(PARTIAL), step(GOOD)]}
    scenario, job = run(client, monkeypatch, plan)
    prompt = scenario.calls[1]["prompt"]
    assert "営業時間" in prompt and "digit sequences" not in prompt
    assert job["mode_b"]["results"][0]["tries"][0]["verdict"] == "partial"


def test_a_worse_retry_is_not_preferred(client, monkeypatch):
    # attempt 1 left a title untranslated (partial); attempt 2 translated it but changed digits
    plan = {"m/a": [step(PARTIAL, cost=0.05), step(DIGITS, cost=0.05)]}
    _, job = run(client, monkeypatch, plan)
    (r,) = job["mode_b"]["results"]
    assert [t["verdict"] for t in r["tries"]] == ["partial", "digits-changed"]
    assert r["verdict"] == "partial" and r["selected_attempt"] == 1
    assert [t["selected"] for t in r["tries"]] == [True, False]
    assert r["cost_usd"] == pytest.approx(0.10)  # the discarded attempt still costs money


def test_digits_changed_in_both_attempts_still_shows_the_attempt_with_fewer_problems(client, monkeypatch):
    plan = {"m/a": [step(BOTH), step(DIGITS)]}
    _, job = run(client, monkeypatch, plan)
    (r,) = job["mode_b"]["results"]
    assert [t["verdict"] for t in r["tries"]] == ["digits-changed+partial", "digits-changed"]
    assert r["selected_attempt"] == 2 and r["verdict"] == "digits-changed"


def test_a_passing_first_attempt_is_not_retried(client, monkeypatch):
    scenario, job = run(client, monkeypatch, {"m/a": [step(GOOD)]})
    assert len(scenario.calls) == 1
    (r,) = job["mode_b"]["results"]
    assert len(r["tries"]) == 1 and r["verdict"] == "pass" and r["retry_error"] is None


def test_a_failing_retry_keeps_the_first_attempt(client, monkeypatch):
    plan = {"m/a": [step(PARTIAL, cost=0.05), step(None, fail=RuntimeError("retry blew up"))]}
    _, job = run(client, monkeypatch, plan)
    (r,) = job["mode_b"]["results"]
    assert r["status"] == "done" and r["verdict"] == "partial" and r["selected_attempt"] == 1
    assert "retry blew up" in r["retry_error"] and len(r["tries"]) == 1


def test_a_verification_that_cannot_run_leaves_the_picture_unverified_and_does_not_retry(client, monkeypatch):
    scenario, job = run(client, monkeypatch, {"m/a": [step(RuntimeError("Google Vision credentials are not usable"))]})
    (r,) = job["mode_b"]["results"]
    assert r["status"] == "done" and r["verdict"] == "unverified" and r["verification"] is None
    assert "credentials" in r["verify_error"]
    assert len(scenario.calls) == 1  # no retry on a check that did not run
    assert client.get(f"/files/{job['job_id']}/{r['output']}").status_code == 200


def test_verification_can_be_switched_off_through_the_api(client, monkeypatch):
    scenario, job = run(client, monkeypatch, {"m/a": [step(PARTIAL)]}, verify="0")
    (r,) = job["mode_b"]["results"]
    assert r["verdict"] is None and r["verification"] is None and r["verify"] is False
    assert scenario.source_ocr_calls == 0 and scenario.ocr_calls == [] and len(scenario.calls) == 1


def test_ocr_text_is_only_kept_when_details_are_asked_for(client, monkeypatch):
    _, job = run(client, monkeypatch, {"m/a": [step(GOOD)]})
    assert "output_text" not in job["mode_b"]["results"][0]["verification"]
    _, job = run(client, monkeypatch, {"m/a": [step(GOOD)]}, verify_details="1")
    assert job["mode_b"]["results"][0]["verification"]["output_text"].startswith("TEL 77446716")
    assert job["params"]["verify_details"] is True


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
    scenario = Scenario(SOURCE, SRC_OCR, {"m/legacy": [step(GOOD)], "google/gemini-2.5-flash-image": [step(GOOD)]}).install(monkeypatch)
    r = client.post("/api/jobs", files={"file": ("a.png", SOURCE, "image/png")}, data={"mode_b": "1", "image_model": "m/legacy"})
    assert wait_for(client, r.json()["job_id"])["params"]["image_models"] == ["m/legacy"]
    r = client.post("/api/jobs", files={"file": ("a.png", SOURCE, "image/png")}, data={"mode_b": "1"})
    assert wait_for(client, r.json()["job_id"])["params"]["image_models"] == ["google/gemini-2.5-flash-image"]
    assert [c["model"] for c in scenario.calls] == ["m/legacy", "google/gemini-2.5-flash-image"]


def test_invalid_model_id_in_the_list_is_rejected(client, monkeypatch):
    Scenario(SOURCE, SRC_OCR, {}).install(monkeypatch)
    assert post(client, ["m/ok", "bad model!"]).status_code == 400


@pytest.mark.parametrize("size", ["1K", "2K", "4K", ""])
def test_output_size_reaches_the_image_call_and_the_result(client, monkeypatch, size):
    scenario, job = run(client, monkeypatch, {"m/a": [step(GOOD)]}, output_size=size)
    assert scenario.calls[0]["resolution"] == size
    assert job["params"]["output_size"] == size
    assert job["mode_b"]["results"][0]["resolution_requested"] == size


def test_unknown_output_size_is_rejected(client, monkeypatch):
    Scenario(SOURCE, SRC_OCR, {}).install(monkeypatch)
    assert post(client, ["m/a"], output_size="8K").status_code == 400


def test_index_page_offers_the_size_option_and_the_multi_model_picker(client):
    html = client.get("/").text
    for needle in ("預設", ">1K<", ">2K<", ">4K<", "最多 4 個", "顯示驗證細節", "給審閱者的連結", 'id="mode-a"'):
        assert needle in html, needle
    assert 'id="mode-a" checked' not in html  # Mode A defaults to off
    assert 'id="mode-b" checked' in html


def test_models_endpoint_carries_the_route_preference(client):
    body = client.get("/api/image-models").json()
    assert body["details"]["mock/image-model"]["preferred_route"] == "chat"
    assert all("preferred_route" in d for d in body["details"].values())
