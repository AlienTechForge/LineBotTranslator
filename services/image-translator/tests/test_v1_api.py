"""/v1/translations, /healthz: token handling, input checks, the record and image routes, retention (mock mode)."""

import io
import json
import logging
import os
import re
import threading
import time

import pytest
from fastapi.testclient import TestClient
from PIL import Image
from scenario import png_of_size

from app import policy, v1
from app.main import create_app

TOKEN = "service-secret-token"
HEADERS = {"X-Service-Token": TOKEN}
SCHEMA_KEYS = [
    "request_id", "schema_version", "target_lang", "status", "tier", "reason", "mode", "verdict", "usability", "coverage",
    "image", "source", "text", "judge", "costs", "budget", "timings_ms", "attempts", "warnings",
]  # fmt: skip


def png(size=(640, 480)) -> bytes:
    return png_of_size(size)


@pytest.fixture
def make_client(monkeypatch, tmp_path):
    def build(token: str | None = TOKEN, **env):
        monkeypatch.setenv("LAB_DATA_DIR", str(tmp_path / "data"))
        monkeypatch.setenv("LAB_MOCK", "1")
        if token is not None:
            monkeypatch.setenv("SERVICE_TOKEN", token)
        for k, v in env.items():
            monkeypatch.setenv(k, v)
        return TestClient(create_app())

    return build


@pytest.fixture
def client(make_client):
    return make_client()


def post(client, *, headers=HEADERS, image=("a.png", None, "image/png"), **data):
    name, content, ctype = image
    files = {"image": (name, png() if content is None else content, ctype)} if name else None
    return client.post("/v1/translations", headers=headers, files=files, data={"target_lang": "zh-TW", **data})


# ---------------------------------------------------------------- the service token


def test_without_a_service_token_every_v1_route_answers_503_and_never_opens(make_client):
    client = make_client(token=None)
    for method, path in (("post", "/v1/translations"), ("get", "/v1/translations/abc"), ("get", "/v1/translations/abc/image.png"), ("get", "/v1/whatever")):
        r = getattr(client, method)(path, headers=HEADERS)  # even a token cannot open an unconfigured service
        assert r.status_code == 503, path
        assert r.json() == {
            "status": "error", "reason": "service_token_missing", "schema_version": 1,
            "detail": "SERVICE_TOKEN is not set: the service is not configured",
        }  # fmt: skip
    assert post(client, headers={}).status_code == 503


def test_an_empty_service_token_counts_as_unset(make_client):
    assert post(make_client(token="  ")).status_code == 503


def test_a_missing_token_is_401_and_a_wrong_one_is_403(client):
    for method, path in (("post", "/v1/translations"), ("get", "/v1/translations/abc"), ("get", "/v1/translations/abc/image.png"), ("get", "/v1/nope")):
        r = getattr(client, method)(path)
        assert r.status_code == 401 and r.json()["reason"] == "unauthorized", path
        r = getattr(client, method)(path, headers={"X-Service-Token": "wrong"})
        assert r.status_code == 403 and r.json()["reason"] == "forbidden", path
    assert post(client, headers={"X-Service-Token": TOKEN + "x"}).status_code == 403
    assert post(client, headers={"X-Service-Token": TOKEN[:-1]}).status_code == 403
    assert post(client, headers={"x-service-token": TOKEN}).status_code == 200  # header names are case blind


def test_refusals_of_the_gate_carry_the_security_headers_too(make_client, client):
    for r in (client.get("/v1/translations/abc"), client.get("/v1/translations/abc", headers={"X-Service-Token": "x"}), make_client(token=None).get("/v1/translations/abc")):
        assert r.status_code in (401, 403, 503)
        assert r.headers["cache-control"] == "no-store" and r.headers["x-content-type-options"] == "nosniff"
        assert r.headers["referrer-policy"] == "no-referrer"


def test_a_wrong_method_is_a_405_in_the_v1_shape(client):
    r = client.get("/v1/translations", headers=HEADERS)
    assert r.status_code == 405 and r.json()["status"] == "error" and r.json()["reason"] == "method_not_allowed"


def test_the_token_is_not_accepted_as_a_query_parameter(client):
    assert client.post(f"/v1/translations?token={TOKEN}", files={"image": ("a.png", png(), "image/png")}, data={"target_lang": "en"}).status_code == 401


def test_the_good_token_gets_through_and_the_lab_token_does_not_open_v1(make_client):
    client = make_client(LAB_TOKEN="lab-secret")
    assert post(client).status_code == 200
    assert post(client, headers={"X-Lab-Token": "lab-secret"}).status_code == 401
    assert client.get("/lab/").status_code == 401  # and the service token does not open the lab
    assert client.get("/lab/", headers=HEADERS).status_code == 401


def test_healthz_is_open_and_has_no_secrets(make_client):
    client = make_client(LAB_TOKEN="lab-secret", ANTHROPIC_API_KEY="sk-ant-very-secret-key", OPENROUTER_API_KEY="or-very-secret-key")
    r = client.get("/healthz")
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"status", "mock", "version", "budget"} and body["status"] == "ok" and body["mock"] is True
    assert set(body["budget"]) == {"month", "openrouter_spent_usd", "openrouter_budget_usd", "anthropic_spent_usd", "anthropic_budget_usd"}
    for secret in (TOKEN, "lab-secret", "sk-ant-very-secret-key", "or-very-secret-key"):
        assert secret not in r.text
    assert make_client(token=None).get("/healthz").status_code == 200  # open even when /v1 is not configured


def test_healthz_says_when_it_is_not_the_mock(make_client):
    assert make_client(LAB_MOCK="0").get("/healthz").json()["mock"] is False


# ---------------------------------------------------------------- the response


def test_a_mock_translation_returns_the_whole_document(client):
    r = post(client, request_id="job-1")
    assert r.status_code == 200 and r.headers["content-type"].startswith("application/json")
    body = r.json()
    assert list(body) == SCHEMA_KEYS
    assert (body["request_id"], body["schema_version"], body["target_lang"]) == ("job-1", 1, "zh-TW")
    assert (body["status"], body["tier"], body["reason"], body["mode"], body["verdict"]) == ("image", "clean", "a_pass", "A", "pass")
    assert body["usability"] == 1.0 and body["coverage"] == 1.0
    assert body["image"]["path"] == "/v1/translations/job-1/image.png" and (body["image"]["width"], body["image"]["height"]) == (640, 480)
    assert set(body["text"]) == {"source_language", "recognized_text", "translated_text"} and body["text"]["translated_text"]
    assert list(body["source"]) == ["width", "height", "words", "median_glyph_px"]
    assert (body["source"]["width"], body["source"]["height"]) == (640, 480) and body["source"]["words"] > 0
    assert body["source"]["median_glyph_px"] > 12  # the canned words of a 640x480 picture are big enough for Mode A
    assert set(body["judge"]) == {"summary", "altered_numbers", "untranslated_major", "legibility_major"}
    assert body["costs"] == {"anthropic_usd": 0.0, "openrouter_usd": 0.0, "total_usd": 0.0}
    assert set(body["timings_ms"]) == {"ocr", "mode_a", "mode_b", "judge", "total"} and body["timings_ms"]["total"] >= 0
    assert [a["mode"] for a in body["attempts"]] == ["A"] and body["warnings"] == []
    assert body["budget"]["month"] and body["budget"]["openrouter_budget_usd"] == 15.0


def test_policy_both_runs_mode_a_and_mode_b_in_the_mock(client):
    body = post(client, policy="both").json()
    assert [a["mode"] for a in body["attempts"]] == ["A", "B"] and all(a["error"] is None and a["skipped"] is None for a in body["attempts"])
    assert body["timings_ms"]["mode_a"] is not None and body["timings_ms"]["mode_b"] is not None
    assert (body["status"], body["tier"], body["mode"]) == ("image", "clean", "A")  # a tie goes to A


def test_policy_b_only_in_the_mock_uses_the_canned_image_model(client):
    body = post(client, policy="b_only").json()
    assert (body["status"], body["reason"], body["mode"]) == ("image", "policy_b_only", "B")
    assert body["attempts"][0]["skipped"] == "policy_b_only" and body["text"]["translated_text"] is None


def test_the_image_route_streams_the_png_and_the_record_route_says_it_again(client):
    first = post(client, request_id="abc_123").json()
    image = client.get(first["image"]["path"], headers=HEADERS)
    assert image.status_code == 200 and image.headers["content-type"] == "image/png"
    assert image.headers["cache-control"] == "no-store" and image.content[:8] == b"\x89PNG\r\n\x1a\n"
    assert len(image.content) == first["image"]["bytes"]
    assert Image.open(io.BytesIO(image.content)).size == (first["image"]["width"], first["image"]["height"])
    again = client.get("/v1/translations/abc_123", headers=HEADERS)
    assert again.status_code == 200 and again.json() == first


def test_a_request_id_is_generated_when_absent(client):
    ids = {post(client).json()["request_id"] for _ in range(3)}
    assert len(ids) == 3 and all(re.fullmatch(r"[0-9a-f]{32}", i) for i in ids)


def test_the_response_budget_and_healthz_move_with_requests(client, tmp_path):
    post(client)
    post(client)
    state = json.loads((tmp_path / "data" / "budget.json").read_text())
    assert state["requests"] == 2 and state["openrouter_usd"] == 0.0 and state["anthropic_usd"] == 0.0  # the file exists in mock mode too


def test_only_the_record_and_the_picture_stay_on_disk(client, tmp_path):
    source = png((200, 100))
    post(client, request_id="files-1", image=("a.png", source, "image/png"))
    folder = tmp_path / "data" / "v1" / "files-1"
    assert sorted(p.name for p in folder.iterdir()) == ["image.png", "record.json"]  # no work/ folder, no source picture
    for p in folder.iterdir():
        assert p.read_bytes() != source


def test_a_text_only_result_has_no_image_and_no_image_route(client, monkeypatch):
    def fake_run(image, lang, policy_name, rid, deadline, **kw):
        return policy.Outcome(
            "text_only", "below_threshold", mode="A", verdict="score", usability=0.4, coverage=0.5,
            text={"source_language": "ja", "recognized_text": "原文", "translated_text": "譯文"},
            attempts=[{"mode": "A"}], timings_ms={"total": 1},
        )  # fmt: skip

    monkeypatch.setattr(policy, "run", fake_run)
    body = post(client, request_id="t-1").json()
    assert (body["status"], body["tier"], body["image"], body["text"]["translated_text"]) == ("text_only", None, None, "譯文")
    assert client.get("/v1/translations/t-1/image.png", headers=HEADERS).status_code == 404
    assert client.get("/v1/translations/t-1", headers=HEADERS).json() == body


def test_a_crash_inside_the_policy_is_still_a_200_with_an_error(client, monkeypatch, caplog):
    def explode(*a, **k):
        raise RuntimeError("secret text from the image: TONKATSU")

    monkeypatch.setattr(policy, "run", explode)
    with caplog.at_level(logging.INFO):
        r = post(client, request_id="boom")
    assert r.status_code == 200
    body = r.json()
    assert (body["status"], body["tier"], body["reason"], body["image"]) == ("error", None, "internal_error", None)
    assert "TONKATSU" not in r.text and "TONKATSU" not in caplog.text  # neither the answer nor the log repeats the exception message
    assert client.get("/v1/translations/boom", headers=HEADERS).json() == body


def test_the_deadline_comes_from_v1_deadline_s(client, monkeypatch):
    seen = {}
    real = policy.run

    def spy(image, lang, policy_name, rid, deadline, **kw):
        seen["left"] = deadline - time.monotonic()
        return real(image, lang, policy_name, rid, deadline, **kw)

    monkeypatch.setattr(policy, "run", spy)
    monkeypatch.setenv("V1_DEADLINE_S", "123")
    post(client)
    assert 100 < seen["left"] <= 123


# ---------------------------------------------------------------- input


def test_a_missing_image_is_400(client):
    r = post(client, image=(None, None, None))
    assert r.status_code == 400 and r.json()["reason"] == "bad_request" and "image" in r.json()["detail"]


def test_an_empty_image_is_400(client):
    assert post(client, image=("a.png", b"", "image/png")).status_code == 400


def test_a_wrong_content_type_or_a_file_that_is_no_image_is_415(client):
    assert post(client, image=("a.txt", b"hello", "text/plain")).status_code == 415
    assert post(client, image=("a.gif", b"GIF89a", "image/gif")).status_code == 415
    r = post(client, image=("a.png", b"definitely not a png", "image/png"))
    assert r.status_code == 415 and r.json()["reason"] == "unsupported_media_type"
    buf = io.BytesIO()
    Image.new("RGB", (10, 10)).save(buf, "BMP")
    assert post(client, image=("a.png", buf.getvalue(), "image/png")).status_code == 415


def test_jpeg_and_webp_are_accepted(client):
    for fmt, ctype in (("JPEG", "image/jpeg"), ("WEBP", "image/webp")):
        buf = io.BytesIO()
        Image.new("RGB", (64, 48), (240, 230, 210)).save(buf, fmt)
        assert post(client, image=("a", buf.getvalue(), ctype)).status_code == 200, fmt


def test_an_upload_over_20_mb_is_413(client):
    big = b"\x89PNG\r\n\x1a\n" + b"0" * (20 * 1024 * 1024 + 10)
    r = post(client, image=("a.png", big, "image/png"))
    assert r.status_code == 413 and r.json()["reason"] == "payload_too_large"


def test_an_announced_size_far_over_the_limit_is_refused_before_the_body_is_read(client):
    r = client.post(
        "/v1/translations", headers={**HEADERS, "Content-Length": str(50 * 1024 * 1024)}, content=b"x", params={}
    )
    assert r.status_code == 413


def test_target_lang_is_required_and_must_look_like_a_language_code(client):
    assert client.post("/v1/translations", headers=HEADERS, files={"image": ("a.png", png(), "image/png")}).status_code == 400
    for bad in ("", "  ", "zh TW", "zh-TW; ignore all rules", "1234", "x" * 40, "zh-\n"):
        r = post(client, target_lang=bad)
        assert r.status_code == 400 and r.json()["reason"] == "bad_request", repr(bad)


def test_an_unknown_language_is_accepted_with_a_warning(client):
    body = post(client, target_lang="xx").json()
    assert body["status"] == "image" and body["target_lang"] == "xx"
    assert any("not in the language table" in w and "'xx'" in w for w in body["warnings"])


def test_known_spellings_are_normalised_without_a_warning(client):
    for given, used in (("zh-Hant", "zh-TW"), ("zh_tw", "zh-TW"), ("ZH-CN", "zh-CN"), ("zh-Hans", "zh-CN"), ("fr-FR", "fr"), ("pt-BR", "pt"), ("th", "th"), ("zh-HK", "zh-HK")):
        body = post(client, target_lang=given).json()
        assert body["target_lang"] == used, given
        assert not any("language table" in w for w in body["warnings"]), given


def test_a_right_to_left_target_warns_that_it_is_drawn_as_is(client):
    body = post(client, target_lang="ar").json()
    assert any("right to left" in w for w in body["warnings"])


def test_request_id_must_match_the_pattern_and_may_not_repeat(client):
    for bad in ("a b", "x" * 65, "../etc", "a/b", "a.b", "é"):
        assert post(client, request_id=bad).status_code == 400, repr(bad)
    assert post(client, request_id="ok_ID-1").status_code == 200
    again = post(client, request_id="ok_ID-1")
    assert again.status_code == 409 and again.json()["reason"] == "request_id_in_use"
    assert post(client, request_id="x" * 64).status_code == 200


def test_policy_and_model_are_checked(client):
    assert post(client, policy="sometimes").status_code == 400
    assert post(client, mode_b_model="bad model!").status_code == 400
    assert post(client, mode_b_model="vendor/model-1:free", policy="b_only").status_code == 200


def test_the_mode_b_model_reaches_the_policy(client, monkeypatch):
    seen = {}
    real = policy.run

    def spy(image, lang, policy_name, rid, deadline, **kw):
        seen.update(kw, policy=policy_name, lang=lang)
        return real(image, lang, policy_name, rid, deadline, **kw)

    monkeypatch.setattr(policy, "run", spy)
    post(client, mode_b_model="vendor/image-9", policy="both", target_lang="ja")
    assert seen["mode_b_model"] == "vendor/image-9" and seen["policy"] == "both" and seen["lang"] == "ja"
    post(client)
    assert seen["mode_b_model"] is None and seen["policy"] == "a_then_b"


# ---------------------------------------------------------------- lookups and retention


def test_unknown_and_malformed_ids_are_404_in_the_v1_shape(client):
    for path in ("/v1/translations/nope", "/v1/translations/nope/image.png", "/v1/translations/..%2f..%2fetc", "/v1/translations/a.b"):
        r = client.get(path, headers=HEADERS)
        assert r.status_code == 404 and r.json()["status"] == "error" and r.json()["reason"] == "not_found", path
    assert client.get("/v1/unknown", headers=HEADERS).json()["reason"] == "not_found"


def test_a_record_older_than_the_retention_is_gone_before_the_sweeper_runs(client, tmp_path):
    first = post(client, request_id="old-1").json()
    folder = tmp_path / "data" / "v1" / "old-1"
    old = time.time() - 16 * 60
    os.utime(folder / "record.json", (old, old))
    assert client.get("/v1/translations/old-1", headers=HEADERS).status_code == 404
    assert client.get(first["image"]["path"], headers=HEADERS).status_code == 404
    fresh = time.time() - 14 * 60
    os.utime(folder / "record.json", (fresh, fresh))
    assert client.get("/v1/translations/old-1", headers=HEADERS).status_code == 200


def test_the_retention_is_v1_retention_minutes(client, tmp_path, monkeypatch):
    post(client, request_id="ret-1")
    record = tmp_path / "data" / "v1" / "ret-1" / "record.json"
    os.utime(record, (time.time() - 120, time.time() - 120))
    assert client.get("/v1/translations/ret-1", headers=HEADERS).status_code == 200
    monkeypatch.setenv("V1_RETENTION_MINUTES", "1")
    assert client.get("/v1/translations/ret-1", headers=HEADERS).status_code == 404


def test_the_sweeper_removes_what_is_older_than_the_retention_and_nothing_else(client, tmp_path, monkeypatch):
    for rid in ("keep-1", "drop-1", "drop-2"):
        post(client, request_id=rid)
    root = tmp_path / "data" / "v1"
    old = time.time() - 20 * 60
    for rid in ("drop-1", "drop-2"):
        os.utime(root / rid / "record.json", (old, old))
    (root / "crashed").mkdir()  # a request that never finished
    os.utime(root / "crashed", (old, old))
    (root / "running").mkdir()
    os.utime(root / "running", (old, old))
    with v1._inflight_lock:
        v1._inflight.add("running")
    try:
        assert v1.sweep() == 3  # drop-1, drop-2 and the one that never finished
        assert sorted(p.name for p in root.iterdir()) == ["keep-1", "running"]
        assert v1.sweep() == 0
        assert v1.sweep(now=time.time() + 16 * 60) == 1  # keep-1 is old by then; a running request is not swept even then
        assert [p.name for p in root.iterdir()] == ["running"]
    finally:
        with v1._inflight_lock:
            v1._inflight.discard("running")
    assert v1.sweep() == 1  # once it is no longer running, an old folder without a record goes too


def test_sweep_without_a_folder_is_a_no_op(client):
    assert v1.sweep() == 0


def test_the_sweeper_thread_runs_at_start_and_on_a_timer(client, tmp_path, monkeypatch):
    post(client, request_id="thr-1")
    record = tmp_path / "data" / "v1" / "thr-1" / "record.json"
    os.utime(record, (time.time() - 3600, time.time() - 3600))
    monkeypatch.setattr(v1.Sweeper, "interval", lambda self: 0.05)
    sweeper = v1.Sweeper()
    sweeper.start()
    try:
        deadline = time.time() + 5
        while record.parent.exists() and time.time() < deadline:
            time.sleep(0.02)
        assert not record.parent.exists()
        post(client, request_id="thr-2")
        os.utime(tmp_path / "data" / "v1" / "thr-2" / "record.json", (time.time() - 3600, time.time() - 3600))
        deadline = time.time() + 5
        while (tmp_path / "data" / "v1" / "thr-2").exists() and time.time() < deadline:
            time.sleep(0.02)
        assert not (tmp_path / "data" / "v1" / "thr-2").exists()
    finally:
        sweeper.stop()
        sweeper.join(timeout=5)
    assert not sweeper.is_alive()


def test_the_app_starts_and_stops_a_sweeper_with_its_lifespan(make_client):
    before = {t.name for t in threading.enumerate()}
    with make_client() as running:
        assert running.get("/healthz").status_code == 200
        assert any(t.name == "v1-sweeper" for t in threading.enumerate() if t.name not in before)
    time.sleep(0.2)
    assert not any(t.name == "v1-sweeper" and t.is_alive() for t in threading.enumerate() if t.name not in before)


def test_the_sweeper_interval_is_a_quarter_of_a_short_retention_and_a_minute_at_most(monkeypatch):
    assert v1.Sweeper().interval() == 60.0
    monkeypatch.setenv("V1_RETENTION_MINUTES", "2")
    assert v1.Sweeper().interval() == 30.0
    monkeypatch.setenv("V1_RETENTION_MINUTES", "0.01")
    assert v1.Sweeper().interval() == 1.0


# ---------------------------------------------------------------- privacy


def test_nothing_from_the_image_or_the_tokens_reaches_the_log(client, caplog):
    with caplog.at_level(logging.DEBUG):
        r = post(client, request_id="log-1", policy="both")
    body = r.json()
    assert body["text"]["recognized_text"] and body["text"]["translated_text"]
    text = caplog.text
    assert "stage=v1 request=log-1 status=image" in text  # what is logged: the status and counts
    for needle in ("TONKATSU", "Pork Cutlet", "NT$120", "LUNCH", TOKEN, "紅燒肉", "Braised pork"):
        assert needle not in text, needle


def test_the_v1_line_carries_every_attempt_and_the_glyph_height(client, caplog):
    with caplog.at_level(logging.INFO):
        body = post(client, request_id="log-2", policy="both").json()
    line = next(r.getMessage() for r in caplog.records if r.getMessage().startswith("stage=v1 request=log-2 "))
    assert line.endswith(f" attempts=A:pass/1.00,B:pass/1.00 glyph_px={body['source']['median_glyph_px']:.1f}")
    assert "attempts=A:pass/1.00,B:pass/1.00" in line and re.search(r" glyph_px=\d+\.\d$", line)
    for needle in ("TONKATSU", "Pork Cutlet", "NT$120", "LUNCH", "紅燒肉", "Braised pork"):
        assert needle not in line, needle


def test_the_attempts_summary_has_the_mode_the_verdict_and_two_decimals():
    rows = [
        {"mode": "A", "verdict": "garbled+score", "usability": 0.1, "error": None, "skipped": None},
        {"mode": "B", "verdict": "numbers+score", "usability": 0.6, "error": None, "skipped": None},
    ]
    assert v1.attempts_summary(rows) == "A:garbled+score/0.10,B:numbers+score/0.60"
    both = [{"mode": "B", "verdict": "pass", "usability": 0.85}, {"mode": "A", "skipped": "small_text", "verdict": None, "usability": None}]
    assert v1.attempts_summary(both) == "B:pass/0.85,A:skipped(small_text)"
    failed = {"mode": "B", "verdict": None, "usability": None, "error": "OSError: TONKATSU in the message", "skipped": None}
    assert v1.attempts_summary([failed]) == "B:error"  # never the message
    assert v1.attempts_summary([{"mode": "A", "verdict": "unverified", "usability": None, "error": None, "skipped": None}]) == "A:unverified/-"
    assert v1.attempts_summary([]) == "-"


def test_a_log_line_without_ocr_has_a_dash_for_the_glyph_height(client, monkeypatch, caplog):
    def fake_run(image, lang, policy_name, rid, deadline, **kw):
        return policy.Outcome(
            "error", "b_failed", attempts=[{"mode": "B", "verdict": None, "usability": None, "error": "OSError: x", "skipped": None}],
            timings_ms={"total": 1},
        )  # fmt: skip

    monkeypatch.setattr(policy, "run", fake_run)
    with caplog.at_level(logging.INFO):
        body = post(client, request_id="log-3").json()
    assert body["source"] is None
    line = next(r.getMessage() for r in caplog.records if r.getMessage().startswith("stage=v1 request=log-3 "))
    assert line.endswith(" attempts=B:error glyph_px=-")


def test_a_tiny_mock_upload_is_not_tiny_text(client):
    body = post(client, image=("a.png", png((64, 48)), "image/png")).json()  # the canned words shrink with the picture
    assert body["source"]["median_glyph_px"] < 12  # they would count as tiny ...
    assert (body["status"], body["reason"], body["mode"]) == ("image", "a_pass", "A")  # ... but the mock never takes the small_text path
    assert [a["mode"] for a in body["attempts"]] == ["A"]


def test_a_tiny_mock_upload_with_the_rule_off_or_on_gives_the_same_answer(make_client):
    for env in ({"MODE_A_MIN_GLYPH_PX": "0"}, {"MODE_A_MIN_GLYPH_PX": "999"}):
        body = post(make_client(**env), image=("a.png", png((64, 48)), "image/png")).json()
        assert (body["reason"], body["mode"]) == ("a_pass", "A"), env


# ---------------------------------------------------------------- the old root paths of the lab keep working


def test_the_lab_api_still_answers_at_the_old_root_paths_and_says_it_is_deprecated(make_client):
    client = make_client()
    r = client.get("/api/image-models")
    assert r.status_code == 200 and r.headers["deprecation"] == "true"
    assert "deprecation" not in client.get("/lab/api/image-models").headers
    assert "deprecation" not in client.get("/healthz").headers
