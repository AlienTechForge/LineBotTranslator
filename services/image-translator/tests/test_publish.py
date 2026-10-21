"""Publishing a finished job to MinIO: settings, key layout, manifest, and never failing the job."""

import io
import json
from datetime import timedelta
from urllib.parse import parse_qs, urlparse

import pytest
from fastapi.testclient import TestClient
from scenario import Scenario, judge_record, ocr, png_of_size, wait_for

from app import mock, publish
from app.config import get_minio_settings
from app.main import create_app

SOURCE = png_of_size((80, 60))
SRC_OCR = ocr(("TEL", "en"), ("77446716", "en"), ("営業時間", "ja"), ("メニュー", "ja"), ("ご飯", "ja"), ("定食", "ja"))
GOOD = ocr(("TEL", "en"), ("77446716", "en"), ("Hours", "en"), ("Menu", "en"), ("Rice", "en"), ("Set", "en"))
DIGITS = ocr(("TEL", "en"), ("09446916", "en"), ("Hours", "en"), ("Menu", "en"), ("Rice", "en"), ("Set", "en"))
NUMBERS_JUDGE = judge_record(usability=0.9, altered=[("77446716", "09446916")], cost=0.01)


def step(out, **kw):
    return {"ocr": out, **kw}


def make_client(monkeypatch, tmp_path, **env):
    monkeypatch.setenv("LAB_DATA_DIR", str(tmp_path / "data"))
    for k, v in env.items():
        monkeypatch.setenv(k, v)
    return TestClient(create_app())


def submit(client, source=SOURCE, models=("m/a",), **extra):
    data = {"target_lang": "en", "mode_b": "1", "image_models": list(models), **extra}
    r = client.post("/api/jobs", files={"file": ("a.png", source, "image/png")}, data=data)
    assert r.status_code == 200, r.text
    return wait_for(client, r.json()["job_id"], published=True)


@pytest.fixture
def fake(monkeypatch):
    """A configured (non-mock) lab whose MinIO is the in-memory fake."""
    client = publish.FakeMinio("s3.azndev.com")
    monkeypatch.setattr(publish, "make_clients", lambda settings, mock: (client, client, []))
    return client


MINIO_ENV = {
    "MINIO_ENDPOINT": "http://minio:9000",
    "MINIO_PUBLIC_ENDPOINT": "https://s3.azndev.com",
    "MINIO_ACCESS_KEY": "test-access-key",
    "MINIO_SECRET_KEY": "test-secret-key-0123456789",
}


def manifest_of(fake_client, job):
    key = f"overlay-lab/{job['job_id']}/manifest.json"
    return json.loads(fake_client.objects[("line-bot", key)]["data"])


# ---------------------------------------------------------------- settings


def test_defaults_when_nothing_is_set():
    s = get_minio_settings()
    assert (s.endpoint, s.public_endpoint, s.access_key, s.secret_key) == ("", "", "", "")
    assert (s.bucket, s.prefix, s.expiry_hours, s.region) == ("line-bot", "overlay-lab", 168, "us-east-1")
    assert not s.configured
    assert s.missing() == ["MINIO_ENDPOINT (or MINIO_PUBLIC_ENDPOINT)", "MINIO_ACCESS_KEY", "MINIO_SECRET_KEY"]


def test_endpoint_defaults_to_the_public_endpoint(monkeypatch):
    monkeypatch.setenv("MINIO_PUBLIC_ENDPOINT", "https://s3.azndev.com")
    monkeypatch.setenv("MINIO_ACCESS_KEY", "k")
    monkeypatch.setenv("MINIO_SECRET_KEY", "s")
    s = get_minio_settings()
    assert s.endpoint == "https://s3.azndev.com" and s.public_endpoint == "https://s3.azndev.com" and s.configured
    monkeypatch.setenv("MINIO_ENDPOINT", "http://minio:9000")
    s = get_minio_settings()
    assert s.endpoint == "http://minio:9000" and s.public_endpoint == "https://s3.azndev.com"


def test_bucket_prefix_and_expiry_overrides_and_clamping(monkeypatch):
    monkeypatch.setenv("MINIO_BUCKET_NAME", "my-bucket")
    monkeypatch.setenv("MINIO_PREFIX", "/lab/round3/")
    monkeypatch.setenv("MINIO_URL_EXPIRY_HOURS", "24")
    s = get_minio_settings()
    assert (s.bucket, s.prefix, s.expiry_hours) == ("my-bucket", "lab/round3", 24)
    for raw, hours in (("1000", 168), ("0", 1), ("-5", 1), ("soon", 168)):  # SigV4 links live at most 7 days
        monkeypatch.setenv("MINIO_URL_EXPIRY_HOURS", raw)
        assert get_minio_settings().expiry_hours == hours


@pytest.mark.parametrize(
    "value, expected",
    [
        ("https://s3.azndev.com", ("s3.azndev.com", True)),
        ("http://minio:9000", ("minio:9000", False)),
        ("https://s3.example.com:9443/", ("s3.example.com:9443", True)),
        ("s3.azndev.com", ("s3.azndev.com", True)),
        ("minio:9000", ("minio:9000", False)),  # a docker service name: plain http
        ("localhost:9000", ("localhost:9000", False)),
        ("127.0.0.1:9000", ("127.0.0.1:9000", False)),
        ("10.0.0.5:9000", ("10.0.0.5:9000", False)),
    ],
)
def test_endpoint_parsing(value, expected):
    assert publish.endpoint_parts(value) == expected


def test_the_in_memory_fake_keeps_the_newest_objects_only():
    fake = publish.FakeMinio(max_objects=3)
    for i in range(5):
        fake.put_object("b", f"k{i}", io.BytesIO(b"x" * i), i, content_type="text/plain")
    fake.put_object("b", "k3", io.BytesIO(b"again"), 5)  # an overwrite counts as the newest
    assert fake.keys() == ["k2", "k3", "k4"] and fake.objects[("b", "k3")]["data"] == b"again"
    assert fake.presigned_get_object("b", "k4", expires=timedelta(hours=1)).endswith("X-Amz-Expires=3600&X-Amz-Signature=mock")


def test_object_key_layout():
    s = get_minio_settings()
    assert publish.object_key(s, "0123456789abcdef", "A.png") == "overlay-lab/0123456789abcdef/A.png"


# ---------------------------------------------------------------- the real SDK (presigning needs no network)


def test_real_sdk_presigns_offline_against_the_public_endpoint(monkeypatch):
    for k, v in MINIO_ENV.items():
        monkeypatch.setenv(k, v)
    settings = get_minio_settings()
    uploader, signer, notes = publish.make_clients(settings, mock=False)
    assert notes == []
    url = signer.presigned_get_object("line-bot", "overlay-lab/0123456789abcdef/manifest.json", expires=timedelta(hours=168))
    parsed = urlparse(url)
    assert (parsed.scheme, parsed.netloc) == ("https", "s3.azndev.com")  # signed for the public host
    assert parsed.path == "/line-bot/overlay-lab/0123456789abcdef/manifest.json"
    query = parse_qs(parsed.query)
    assert query["X-Amz-Expires"] == ["604800"] and query["X-Amz-Algorithm"] == ["AWS4-HMAC-SHA256"]
    assert "us-east-1" in query["X-Amz-Credential"][0] and "test-secret-key" not in url
    internal = urlparse(uploader.presigned_get_object("line-bot", "x"))
    assert (internal.scheme, internal.netloc) == ("http", "minio:9000")  # uploads go to the internal endpoint


def test_a_link_signed_for_an_internal_endpoint_is_called_out(monkeypatch):
    monkeypatch.setenv("MINIO_ENDPOINT", "http://minio:9000")
    monkeypatch.setenv("MINIO_ACCESS_KEY", "k")
    monkeypatch.setenv("MINIO_SECRET_KEY", "s")
    _, signer, notes = publish.make_clients(get_minio_settings(), mock=False)
    assert urlparse(signer.presigned_get_object("line-bot", "k")).netloc == "minio:9000"
    assert len(notes) == 1 and "MINIO_PUBLIC_ENDPOINT" in notes[0]


def test_a_public_only_setup_has_nothing_to_complain_about(monkeypatch):
    monkeypatch.setenv("MINIO_PUBLIC_ENDPOINT", "https://s3.azndev.com")
    monkeypatch.setenv("MINIO_ACCESS_KEY", "k")
    monkeypatch.setenv("MINIO_SECRET_KEY", "s")
    _, signer, notes = publish.make_clients(get_minio_settings(), mock=False)
    assert notes == [] and urlparse(signer.presigned_get_object("line-bot", "k")).netloc == "s3.azndev.com"


# ---------------------------------------------------------------- skipped without credentials


def test_publishing_is_skipped_with_a_visible_note_when_not_configured(monkeypatch, tmp_path):
    client = make_client(monkeypatch, tmp_path)  # not LAB_MOCK, no MINIO_*
    Scenario(SOURCE, SRC_OCR, {"m/a": [step(GOOD)]}).install(monkeypatch)
    job = submit(client)
    assert job["status"] == "done" and job["mode_b"]["results"][0]["verdict"] == "pass"
    p = job["publish"]
    assert p["status"] == "skipped"
    assert "MINIO_ACCESS_KEY" in p["note"] and "MINIO_SECRET_KEY" in p["note"] and "MINIO_ENDPOINT" in p["note"]
    assert "manifest_url" not in p and job["warnings"] == []
    assert client.get(f"/files/{job['job_id']}/manifest.json").status_code == 404


# ---------------------------------------------------------------- key layout and manifest


def test_everything_is_uploaded_under_prefix_job_id_name_with_a_manifest_of_presigned_urls(monkeypatch, tmp_path, fake):
    client = make_client(monkeypatch, tmp_path, **MINIO_ENV)
    plan = {
        "google/gemini-3-pro-image": [step(DIGITS, cost=0.05, ms=2000, judge=NUMBERS_JUDGE), step(GOOD, cost=0.06, ms=3000)],
        "m/b": [step(GOOD, cost=0.01, ms=500, size=(160, 120))],
    }
    Scenario(SOURCE, SRC_OCR, plan).install(monkeypatch)
    job = submit(client, models=list(plan), output_size="2K")
    job_id = job["job_id"]
    assert job["publish"]["status"] == "done" and job["warnings"] == []

    prefix = f"overlay-lab/{job_id}/"
    keys = fake.keys("line-bot")
    assert keys == sorted(
        prefix + n
        for n in (
            "original.png",
            "B1_google_gemini-3-pro-image_r1_a1.png",
            "B1_google_gemini-3-pro-image_r1_a1_verify.json",
            "B1_google_gemini-3-pro-image_r1_a2.png",
            "B1_google_gemini-3-pro-image_r1_a2_verify.json",
            "B2_m_b_r1_a1.png",
            "B2_m_b_r1_a1_verify.json",
            "manifest.json",
        )
    )
    assert fake.objects[("line-bot", prefix + "original.png")]["data"] == SOURCE  # the original as it was sent
    assert fake.objects[("line-bot", prefix + "original.png")]["content_type"] == "image/png"
    assert fake.objects[("line-bot", prefix + "manifest.json")]["content_type"] == "application/json"
    assert fake.objects[("line-bot", prefix + "B2_m_b_r1_a1_verify.json")]["content_type"] == "application/json"

    m = manifest_of(fake, job)
    assert m["job_id"] == job_id and m["target_language"] == "en" and m["schema_version"] == 3
    assert m["batch_id"] is None and m["image_index"] == 1  # a job submitted on its own is no batch
    assert m["bucket"] == "line-bot" and m["prefix"] == f"overlay-lab/{job_id}"
    assert m["url_expiry_hours"] == 168 and m["urls_expire_at"] > m["published_at"] >= m["created_at"]
    assert m["options"]["image_models"] == list(plan) and m["options"]["output_size"] == "2K"
    assert m["options"]["verify"] is True and m["options"]["mode_a"] is False
    assert m["options"]["runs"] == 1 and m["options"]["judge"] is True and m["options"]["judge_model"] == "claude-sonnet-5-5"
    assert m["options"]["mode_a_retry"] is True
    assert m["original"]["width"] == 80 and m["original"]["file"]["url"].startswith("https://s3.azndev.com/line-bot/")
    assert m["mode_a"] is None

    # mode_b[] is one entry per MODEL; its runs[] hold what schema 1 had per model, and summary folds them
    gem, other = m["mode_b"]
    assert gem["model"] == "google/gemini-3-pro-image" and gem["route_preference"] == "images"
    assert gem["resolution_requested"] == "2K" and gem["runs_requested"] == 1
    assert gem["summary"]["passes"] == 1 and gem["summary"]["pass_rate"] == 1.0 and gem["summary"]["best_run"] == 1
    assert gem["summary"]["best_image"]["name"] == "B1_google_gemini-3-pro-image_r1_a2.png"
    (run,) = gem["runs"]
    assert run["run"] == 1 and run["verdict"] == "pass" and run["selected_attempt"] == 2 and run["status"] == "done"
    assert run["cost_usd"] == pytest.approx(0.11) and run["judge_cost_usd"] == pytest.approx(0.02)
    assert run["cost_openrouter_usd"] == pytest.approx(0.11) and run["cost_anthropic_usd"] == pytest.approx(0.02)  # cash / credit
    assert run["total_cost_usd"] == pytest.approx(0.13) and run["elapsed_ms"] >= 5000 and run["coverage"] == 1.0
    assert run["usability"] == 1.0 and run["failed_checks"] == []
    assert run["output_size"] == [80, 60] and run["api_used"] == "images" and run["resolution_applied"] is True
    assert [(a["attempt"], a["selected"], a["verdict"]) for a in run["attempts"]] == [(1, False, "numbers"), (2, True, "pass")]
    assert run["attempts"][0]["failed_checks"] == ["numbers"] and run["attempts"][0]["usability"] == 0.9
    warnings = run["attempts"][0]["ocr_warnings"]  # the OCR digit check saw it too, as a warning
    assert warnings["warnings"] == ["digits-missing"] and warnings["missing_digit_runs"] == ["77446716"]
    assert warnings["unverified_digit_runs"] == []
    assert run["attempts"][0]["image"]["key"] == prefix + "B1_google_gemini-3-pro-image_r1_a1.png"
    assert run["attempts"][1]["verification_file"]["key"].endswith("_r1_a2_verify.json")
    assert run["image"]["name"] == run["attempts"][1]["image"]["name"]  # the selected attempt
    judged = run["attempts"][0]["judge"]
    assert judged["status"] == "ok" and judged["usability"] == 0.9 and judged["legibility_issues"] == [] and judged["cost_usd"] == 0.01
    assert judged["altered_numbers"] == [{"source": "77446716", "output": "09446916", "where": "somewhere"}]
    assert run["attempts"][0]["judge_cost_usd"] == 0.01 and run["attempts"][0]["coverage"] == 0.9
    assert other["model"] == "m/b" and other["runs"][0]["output_size"] == [160, 120] and other["runs"][0]["usage"]["prompt_tokens"] == 100

    # every object has a presigned GET URL, expiring in 168 hours, and so does the manifest itself
    assert {o["name"] for o in m["objects"]} == {k[len(prefix):] for k in keys} - {"manifest.json"}
    for o in m["objects"]:
        assert o["key"] == prefix + o["name"] and o["bytes"] > 0
        assert o["url"] == f"https://s3.azndev.com/line-bot/{o['key']}?X-Amz-Expires=604800&X-Amz-Signature=mock"
    assert m["manifest"]["key"] == prefix + "manifest.json"
    assert m["manifest"]["url"] == job["publish"]["manifest_url"]
    assert job["publish"]["expiry_hours"] == 168 and job["publish"]["objects"] == len(keys)

    # a local copy is served too, and the verification JSON is what the page's details show
    assert client.get(f"/files/{job_id}/manifest.json").json()["job_id"] == job_id
    verify_doc = json.loads(fake.objects[("line-bot", prefix + "B1_google_gemini-3-pro-image_r1_a1_verify.json")]["data"])
    assert verify_doc["verdict"] == "numbers" and verify_doc["model"] == "google/gemini-3-pro-image"
    assert verify_doc["run"] == 1 and verify_doc["attempt"] == 1 and verify_doc["judge"]["status"] == "ok"


def test_the_manifest_carries_every_run_of_a_batch_and_the_summary_per_model(monkeypatch, tmp_path, fake):
    client = make_client(monkeypatch, tmp_path, **MINIO_ENV)
    plan = {
        "m/steady": [step(GOOD, cost=0.04, ms=1000) for _ in range(3)],
        "m/flaky": [step(GOOD, cost=0.06, ms=3000), step(None, fail=RuntimeError("m/flaky exploded")), step(GOOD, cost=0.06, ms=3000)],
    }
    Scenario(SOURCE, SRC_OCR, plan).install(monkeypatch)
    job = submit(client, models=list(plan), runs="3")
    job_id = job["job_id"]
    assert job["publish"]["status"] == "done" and job["warnings"] == []

    prefix = f"overlay-lab/{job_id}/"
    keys = {k[len(prefix):] for k in fake.keys("line-bot")}
    for run in (1, 2, 3):  # every run of the steady model has its own image and verification file
        assert f"B1_m_steady_r{run}_a1.png" in keys and f"B1_m_steady_r{run}_a1_verify.json" in keys
    assert len([k for k in keys if k.startswith("B2_m_flaky_") and k.endswith(".png")]) == 2  # the failed run made no image

    m = manifest_of(fake, job)
    assert m["options"]["runs"] == 3 and [e["model"] for e in m["mode_b"]] == ["m/steady", "m/flaky"]
    steady, flaky = m["mode_b"]
    assert steady["runs_requested"] == 3 and [r["run"] for r in steady["runs"]] == [1, 2, 3]
    assert [r["verdict"] for r in steady["runs"]] == ["pass"] * 3
    assert steady["summary"]["pass_rate"] == 1.0 and steady["summary"]["mean_total_cost_usd"] == pytest.approx(0.05)
    assert steady["summary"]["runs_done"] == 3 and steady["summary"]["best_run"] == 1
    assert steady["summary"]["best_image"]["name"] == "B1_m_steady_r1_a1.png" and "url" in steady["summary"]["best_image"]

    assert [r["run"] for r in flaky["runs"]] == [1, 2, 3]
    failed = [r for r in flaky["runs"] if r["status"] == "error"]
    assert len(failed) == 1 and "exploded" in failed[0]["error"] and failed[0]["attempts"] == [] and failed[0]["image"] is None
    assert flaky["summary"]["runs_failed"] == 1 and flaky["summary"]["passes"] == 2
    assert flaky["summary"]["pass_rate"] == pytest.approx(2 / 3, abs=1e-4) and sorted(flaky["summary"]["verdicts"]) == ["error", "pass", "pass"]
    assert flaky["summary"]["mean_total_cost_usd"] == pytest.approx(0.07)
    # the objects list has every image and verification file of every run
    assert {o["name"] for o in m["objects"]} == keys - {"manifest.json"}


def test_a_failed_attempt_shows_up_in_the_manifest_with_the_judge_findings(monkeypatch, tmp_path, fake):
    client = make_client(monkeypatch, tmp_path, **MINIO_ENV)
    judged = judge_record(
        usability=0.35, cost=0.02, garbled=[("middle column", "Banero Salaino", True), ("logo", "Sinoe", False)],
        untranslated=["ご飯"], rows=[("duplicated_row", "scallion rice")], mistranslated=[("Perm", "漂髮")],
    )  # fmt: skip
    Scenario(SOURCE, SRC_OCR, {"m/a": [step(GOOD, judge=judged), step(GOOD)]}).install(monkeypatch)
    job = submit(client)
    m = manifest_of(fake, job)
    (run,) = m["mode_b"][0]["runs"]
    first, second = run["attempts"]
    assert (first["verdict"], second["verdict"]) == ("rows+garbled+partial+score", "pass") and run["selected_attempt"] == 2
    assert first["failed_checks"] == ["rows", "garbled", "partial", "score"] and first["usability"] == 0.35
    assert first["judge"]["legibility_issues"][0] == {"where": "middle column", "output_text": "Banero Salaino", "problem": "not real text", "major": True}
    assert first["judge"]["legibility_issues"][1]["major"] is False  # minor findings are kept in the manifest, never fail a verdict
    assert first["judge"]["structure_issues"] == [{"type": "duplicated_row", "detail": "scallion rice"}]
    assert first["judge"]["untranslated"][0]["text"] == "ご飯" and first["judge"]["mistranslated"][0]["output_text"] == "Perm"
    assert first["judge"]["summary"] == "scripted judge"
    verify_doc = json.loads(fake.objects[("line-bot", f"overlay-lab/{job['job_id']}/B1_m_a_r1_a1_verify.json")]["data"])
    assert verify_doc["verdict"] == "rows+garbled+partial+score" and verify_doc["judge"]["usability"] == 0.35
    assert verify_doc["ocr_warnings"]["status"] == "ok" and verify_doc["checks"]["rows"] == {"ok": False, "count": 1}


def test_bucket_prefix_and_expiry_come_from_the_environment(monkeypatch, tmp_path, fake):
    client = make_client(monkeypatch, tmp_path, MINIO_BUCKET_NAME="my-bucket", MINIO_PREFIX="lab/round3",
                         MINIO_URL_EXPIRY_HOURS="24", **MINIO_ENV)  # fmt: skip
    Scenario(SOURCE, SRC_OCR, {"m/a": [step(GOOD)]}).install(monkeypatch)
    job = submit(client)
    keys = fake.keys("my-bucket")
    assert f"lab/round3/{job['job_id']}/manifest.json" in keys and fake.keys("line-bot") == []
    assert "X-Amz-Expires=86400" in job["publish"]["manifest_url"] and "/my-bucket/lab/round3/" in job["publish"]["manifest_url"]


def test_mock_mode_uses_the_in_memory_fake_and_still_produces_the_manifest(monkeypatch, tmp_path):
    publish.mock_client().objects.clear()
    client = make_client(monkeypatch, tmp_path, LAB_MOCK="1")  # no MINIO_* at all
    fixture = mock.make_fixture()
    buf = io.BytesIO()
    fixture.image.save(buf, "PNG")
    r = client.post(
        "/api/jobs",
        files={"file": ("f.png", buf.getvalue(), "image/png")},
        data={"target_lang": "en", "mode_a": "1", "mode_b": "1", "image_models": "mock/image-model"},
    )
    job = wait_for(client, r.json()["job_id"], published=True)
    assert job["publish"]["status"] == "done" and job["publish"]["mock"] is True and "LAB_MOCK" in job["publish"]["note"]
    prefix = f"overlay-lab/{job['job_id']}/"
    keys = publish.mock_client().keys("line-bot")
    for name in (
        "original.png", "A_a1.png", "A_a1_erased.png", "A_a1_units.json", "A_a1_verify.json",
        "B1_mock_image-model_r1_a1.png", "B1_mock_image-model_r1_a1_verify.json", "manifest.json",
    ):  # fmt: skip
        assert prefix + name in keys, name
    assert prefix + "A.png" not in keys  # round 5: every Mode A attempt has its own files
    m = json.loads(publish.mock_client().objects[("line-bot", prefix + "manifest.json")]["data"])
    assert m["schema_version"] == 3 and m["mock"] is True and m["mode_a"]["translated_count"] == 3
    assert m["mode_a"]["units"]["name"] == "A_a1_units.json" and m["mode_a"]["image"]["name"] == "A_a1.png"
    assert m["mode_a"]["tokens"] == {"input": 2345, "output": 612} and m["mode_a"]["verdict"] == "pass"
    assert m["mode_a"]["cost_anthropic_usd"] == pytest.approx((2345 * 4 + 612 * 20) / 1e6 + (3400 * 2 + 420 * 10) / 1e6)
    assert m["mode_a"]["cost_openrouter_usd"] == 0.0 and m["mode_a"]["selected_attempt"] == 1
    (attempt,) = m["mode_a"]["attempts"]
    assert attempt["selected"] and attempt["erased"]["name"] == "A_a1_erased.png" and attempt["verification_file"]["name"] == "A_a1_verify.json"
    assert attempt["erase_methods"] == {"flat": 3, "inpaint": 0} and attempt["text_used"]["full"] + attempt["text_used"]["short"] == 3
    assert attempt["judge"]["status"] == "ok" and attempt["judge"]["usability"] == 1.0 and attempt["retry_feedback"] is None
    (mock_run,) = m["mode_b"][0]["runs"]
    assert mock_run["verdict"] == "pass" and mock_run["api_used"] == "mock"
    assert mock_run["attempts"][0]["judge"]["status"] == "ok" and mock_run["attempts"][0]["judge"]["input_tokens"] == 3400
    units = json.loads(publish.mock_client().objects[("line-bot", prefix + "A_a1_units.json")]["data"])
    assert len(units["units"]) == 4 and units["job_id"] == job["job_id"] and units["attempt"] == 1


# ---------------------------------------------------------------- failures never fail the job


class FlakyMinio(publish.FakeMinio):
    def __init__(self, fail_when):
        super().__init__("s3.azndev.com")
        self.fail_when = fail_when

    def put_object(self, bucket_name, object_name, data, length, content_type="application/octet-stream", **kw):
        if self.fail_when(object_name):
            raise OSError(f"connection reset while writing {object_name} with test-secret-key-0123456789")
        return super().put_object(bucket_name, object_name, data, length, content_type, **kw)


def test_a_failed_object_upload_is_a_warning_and_the_rest_still_goes_up(monkeypatch, tmp_path):
    flaky = FlakyMinio(lambda name: name.endswith("_a1.png"))
    monkeypatch.setattr(publish, "make_clients", lambda settings, mock: (flaky, flaky, []))
    client = make_client(monkeypatch, tmp_path, **MINIO_ENV)
    Scenario(SOURCE, SRC_OCR, {"m/a": [step(GOOD)]}).install(monkeypatch)
    job = submit(client)
    assert job["status"] == "done" and job["mode_b"]["results"][0]["status"] == "done"
    assert job["publish"]["status"] == "done" and job["publish"]["failed_count"] == 1
    assert any("B1_m_a_r1_a1.png" in w and "MinIO" in w for w in job["warnings"])
    assert "test-secret-key-0123456789" not in json.dumps(job) and "***" in json.dumps(job["warnings"])
    m = manifest_of(flaky, job)
    failed = [o for o in m["mode_b"][0]["runs"][0]["attempts"] if "error" in o["image"]]
    assert len(failed) == 1 and "url" not in failed[0]["image"]
    assert any("upload failed" in w for w in m["warnings"])
    assert not any(o["name"].endswith("_a1.png") for o in m["objects"] if "url" in o)


def test_a_failing_manifest_upload_is_reported_not_raised(monkeypatch, tmp_path):
    flaky = FlakyMinio(lambda name: name.endswith("manifest.json"))
    monkeypatch.setattr(publish, "make_clients", lambda settings, mock: (flaky, flaky, []))
    client = make_client(monkeypatch, tmp_path, **MINIO_ENV)
    Scenario(SOURCE, SRC_OCR, {"m/a": [step(GOOD)]}).install(monkeypatch)
    job = submit(client)
    assert job["status"] == "done" and job["publish"]["status"] == "error"
    assert "manifest_url" not in job["publish"]
    assert any("發佈到 MinIO 失敗" in w for w in job["warnings"])
    assert "test-secret-key-0123456789" not in json.dumps(job)


def test_a_client_that_cannot_even_be_built_is_a_publish_error_only(monkeypatch, tmp_path):
    def broken(settings, mock):
        raise ValueError("invalid endpoint")

    monkeypatch.setattr(publish, "make_clients", broken)
    client = make_client(monkeypatch, tmp_path, **MINIO_ENV)
    Scenario(SOURCE, SRC_OCR, {"m/a": [step(GOOD)]}).install(monkeypatch)
    job = submit(client)
    assert job["status"] == "done" and job["publish"]["status"] == "error" and "invalid endpoint" in job["publish"]["error"]


def test_publish_job_never_raises_even_for_a_broken_job_record(monkeypatch, tmp_path):
    monkeypatch.setenv("LAB_MOCK", "1")
    out = publish.publish_job({"job_id": "0123456789abcdef", "image": None, "mode_b": "garbage"}, tmp_path)
    assert out["status"] == "error"


def test_the_job_page_data_carries_the_reviewer_link_and_warnings_fields(monkeypatch, tmp_path, fake):
    client = make_client(monkeypatch, tmp_path, **MINIO_ENV)
    Scenario(SOURCE, SRC_OCR, {"m/a": [step(GOOD)]}).install(monkeypatch)
    job = submit(client)
    # what the page's "給審閱者的連結" line reads
    assert job["publish"]["manifest_url"].startswith("https://s3.azndev.com/line-bot/overlay-lab/")
    assert isinstance(job["warnings"], list)
    persisted = TestClient(create_app()).get(f"/api/jobs/{job['job_id']}").json()  # also after a restart
    assert persisted["publish"]["manifest_url"] == job["publish"]["manifest_url"]
