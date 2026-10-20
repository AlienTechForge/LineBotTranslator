"""Publishing a finished job to MinIO: settings, key layout, manifest, and never failing the job."""

import io
import json
from datetime import timedelta
from urllib.parse import parse_qs, urlparse

import pytest
from fastapi.testclient import TestClient
from scenario import Scenario, ocr, png_of_size, wait_for

from app import mock, publish
from app.config import get_minio_settings
from app.main import create_app

SOURCE = png_of_size((80, 60))
SRC_OCR = ocr(("TEL", "en"), ("77446716", "en"), ("営業時間", "ja"), ("メニュー", "ja"), ("ご飯", "ja"), ("定食", "ja"))
GOOD = ocr(("TEL", "en"), ("77446716", "en"), ("Hours", "en"), ("Menu", "en"), ("Rice", "en"), ("Set", "en"))
DIGITS = ocr(("TEL", "en"), ("09446916", "en"), ("Hours", "en"), ("Menu", "en"), ("Rice", "en"), ("Set", "en"))


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
        "google/gemini-3-pro-image": [step(DIGITS, cost=0.05, ms=2000), step(GOOD, cost=0.06, ms=3000)],
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
            "B1_google_gemini-3-pro-image_a1.png",
            "B1_google_gemini-3-pro-image_a1_verify.json",
            "B1_google_gemini-3-pro-image_a2.png",
            "B1_google_gemini-3-pro-image_a2_verify.json",
            "B2_m_b_a1.png",
            "B2_m_b_a1_verify.json",
            "manifest.json",
        )
    )
    assert fake.objects[("line-bot", prefix + "original.png")]["data"] == SOURCE  # the original as it was sent
    assert fake.objects[("line-bot", prefix + "original.png")]["content_type"] == "image/png"
    assert fake.objects[("line-bot", prefix + "manifest.json")]["content_type"] == "application/json"
    assert fake.objects[("line-bot", prefix + "B2_m_b_a1_verify.json")]["content_type"] == "application/json"

    m = manifest_of(fake, job)
    assert m["job_id"] == job_id and m["target_language"] == "en" and m["schema_version"] == 1
    assert m["bucket"] == "line-bot" and m["prefix"] == f"overlay-lab/{job_id}"
    assert m["url_expiry_hours"] == 168 and m["urls_expire_at"] > m["published_at"] >= m["created_at"]
    assert m["options"]["image_models"] == list(plan) and m["options"]["output_size"] == "2K"
    assert m["options"]["verify"] is True and m["options"]["mode_a"] is False
    assert m["original"]["width"] == 80 and m["original"]["file"]["url"].startswith("https://s3.azndev.com/line-bot/")
    assert m["mode_a"] is None

    gem, other = m["mode_b"]
    assert gem["model"] == "google/gemini-3-pro-image" and gem["route_preference"] == "images"
    assert gem["verdict"] == "pass" and gem["selected_attempt"] == 2
    assert gem["cost_usd"] == pytest.approx(0.11) and gem["elapsed_ms"] >= 5000
    assert gem["resolution_requested"] == "2K" and gem["output_size"] == [80, 60] and gem["api_used"] == "images"
    assert [(a["attempt"], a["selected"], a["verdict"]) for a in gem["attempts"]] == [(1, False, "digits-changed"), (2, True, "pass")]
    assert gem["attempts"][0]["missing_digit_runs"] == ["77446716"]
    assert gem["attempts"][0]["image"]["key"] == prefix + "B1_google_gemini-3-pro-image_a1.png"
    assert gem["attempts"][1]["verification_file"]["key"].endswith("_a2_verify.json")
    assert gem["image"]["name"] == gem["attempts"][1]["image"]["name"]  # the selected attempt
    assert other["model"] == "m/b" and other["output_size"] == [160, 120] and other["usage"]["prompt_tokens"] == 100

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
    verify_doc = json.loads(fake.objects[("line-bot", prefix + "B1_google_gemini-3-pro-image_a1_verify.json")]["data"])
    assert verify_doc["verdict"] == "digits-changed" and verify_doc["model"] == "google/gemini-3-pro-image"


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
    for name in ("original.png", "A.png", "A_units.json", "B1_mock_image-model_a1.png", "B1_mock_image-model_a1_verify.json", "manifest.json"):
        assert prefix + name in keys, name
    m = json.loads(publish.mock_client().objects[("line-bot", prefix + "manifest.json")]["data"])
    assert m["mock"] is True and m["mode_a"]["translated_count"] == 3 and m["mode_a"]["units"]["name"] == "A_units.json"
    assert m["mode_a"]["tokens"] == {"input": 2345, "output": 612}
    assert m["mode_b"][0]["verdict"] == "pass" and m["mode_b"][0]["api_used"] == "mock"
    units = json.loads(publish.mock_client().objects[("line-bot", prefix + "A_units.json")]["data"])
    assert len(units["units"]) == 4 and units["job_id"] == job["job_id"]


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
    assert any("B1_m_a_a1.png" in w and "MinIO" in w for w in job["warnings"])
    assert "test-secret-key-0123456789" not in json.dumps(job) and "***" in json.dumps(job["warnings"])
    m = manifest_of(flaky, job)
    failed = [o for o in m["mode_b"][0]["attempts"] if "error" in o["image"]]
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
