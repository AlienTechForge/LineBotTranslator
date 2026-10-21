"""A batch of up to four images: the API, the page data, the summary rows and the batch manifest."""

import io
import json
import time

import pytest
from fastapi.testclient import TestClient
from PIL import Image
from scenario import Scenario, judge_record, ocr, png_of_size

from app import mock, publish
from app.main import create_app

SRC_OCR = ocr(("TEL", "en"), ("77446716", "en"), ("営業時間", "ja"), ("メニュー", "ja"), ("ご飯", "ja"), ("定食", "ja"))
GOOD = ocr(("TEL", "en"), ("77446716", "en"), ("Hours", "en"), ("Menu", "en"), ("Rice", "en"), ("Set", "en"))
IMAGES = [png_of_size((80, 60), (240 - 10 * i, 235, 225)) for i in range(4)]  # four different pictures
NAMES = ["receipt.png", "menu.png", "poster.png", "card.png"]

MINIO_ENV = {
    "MINIO_ENDPOINT": "http://minio:9000",
    "MINIO_PUBLIC_ENDPOINT": "https://s3.azndev.com",
    "MINIO_ACCESS_KEY": "test-access-key",
    "MINIO_SECRET_KEY": "test-secret-key-0123456789",
}


def step(out, **kw):
    return {"ocr": out, **kw}


def make_client(monkeypatch, tmp_path, **env):
    monkeypatch.setenv("LAB_DATA_DIR", str(tmp_path / "data"))
    monkeypatch.setenv("LAB_MOCK", "1")
    for k, v in env.items():
        monkeypatch.setenv(k, v)
    return TestClient(create_app())


@pytest.fixture
def client(monkeypatch, tmp_path):
    return make_client(monkeypatch, tmp_path)


def scenario_for(monkeypatch, plan, images=IMAGES, **kw):
    scenario = Scenario(images[0], SRC_OCR, plan, **kw)
    for data in images[1:]:
        scenario.add_source(data, SRC_OCR)
    return scenario.install(monkeypatch)


def post_batch(client, images=IMAGES, names=NAMES, models=("m/a",), **extra):
    files = [("files", (name, data, "image/png")) for data, name in zip(images, names)]
    data = {"target_lang": "en", "mode_a": "0", "mode_b": "1", "image_models": list(models), **extra}
    return client.post("/api/batches", files=files, data=data)


def wait_batch(client, batch_id, *, published=True, timeout=30.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        batch = client.get(f"/api/batches/{batch_id}").json()
        if batch["status"] == "done" and (not published or batch["publish"]["status"] in ("done", "skipped", "error")):
            return batch
        time.sleep(0.03)
    raise AssertionError("batch did not finish: " + str(client.get(f"/api/batches/{batch_id}").json())[:400])


def plan_for(models=("m/a",), n=4, **kw):
    return {m: [step(GOOD, **kw) for _ in range(n)] for m in models}


# ---------------------------------------------------------------- the API


@pytest.mark.parametrize("n", [1, 2, 4])
def test_one_to_four_images_make_one_batch_with_one_job_per_image_in_submission_order(client, monkeypatch, n):
    scenario_for(monkeypatch, plan_for(n=n))
    r = post_batch(client, IMAGES[:n], NAMES[:n])
    assert r.status_code == 200, r.text
    body = r.json()
    assert set(body) == {"batch_id", "job_ids"} and len(body["job_ids"]) == n == len(set(body["job_ids"]))
    batch = wait_batch(client, body["batch_id"])
    assert batch["batch_id"] == body["batch_id"] and batch["job_ids"] == body["job_ids"] and batch["status"] == "done"
    assert [j["job_id"] for j in batch["jobs"]] == body["job_ids"]  # submission order
    for i, job in enumerate(batch["jobs"]):
        assert job["status"] == "done" and job["batch_id"] == body["batch_id"] and job["index"] == i + 1
        assert job["image"]["name"] == NAMES[i]
        assert client.get(f"/files/{job['job_id']}/original.png").content == IMAGES[i]  # each job keeps its own upload
        assert client.get(f"/api/jobs/{job['job_id']}").json()["batch_id"] == body["batch_id"]  # the job id still polls on its own
    assert [im["name"] for im in batch["images"]] == NAMES[:n] and batch["params"]["mode_b"] is True
    assert len(batch["summary"]) == n and all(row["verdict"] == "pass" for row in batch["summary"])


def test_five_images_are_rejected_and_nothing_is_started(client, monkeypatch, tmp_path):
    scenario = scenario_for(monkeypatch, plan_for(n=5), images=IMAGES + [png_of_size((80, 60), (1, 2, 3))])
    r = post_batch(client, IMAGES + [png_of_size((80, 60), (1, 2, 3))], NAMES + ["extra.png"])
    assert r.status_code == 400 and "between 1 and 4" in r.json()["detail"]
    assert scenario.calls == []
    data_dir = tmp_path / "data"
    assert not data_dir.exists() or list(data_dir.iterdir()) == []


def test_a_batch_without_files_is_a_422_and_a_bad_file_rejects_the_whole_batch(client, monkeypatch, tmp_path):
    scenario = scenario_for(monkeypatch, plan_for())
    assert client.post("/api/batches", data={"target_lang": "en", "mode_b": "1"}).status_code == 422
    files = [("files", ("a.png", IMAGES[0], "image/png")), ("files", ("b.txt", b"hello", "text/plain"))]
    assert client.post("/api/batches", files=files, data={"mode_b": "1"}).status_code == 415
    files = [("files", ("a.png", IMAGES[0], "image/png")), ("files", ("b.png", b"not a png", "image/png"))]
    assert client.post("/api/batches", files=files, data={"mode_b": "1"}).status_code == 415
    files = [("files", ("a.png", IMAGES[0], "image/png")), ("files", ("e.png", b"", "image/png"))]
    assert client.post("/api/batches", files=files, data={"mode_b": "1"}).status_code == 400
    big = b"\x89PNG\r\n\x1a\n" + b"0" * (20 * 1024 * 1024 + 10)
    files = [("files", ("a.png", IMAGES[0], "image/png")), ("files", ("big.png", big, "image/png"))]
    assert client.post("/api/batches", files=files, data={"mode_b": "1"}).status_code == 413  # the per-file limit applies to each
    assert scenario.calls == []
    data_dir = tmp_path / "data"
    assert not data_dir.exists() or list(data_dir.iterdir()) == []  # the first, good image was not started either


def test_the_form_fields_are_the_ones_of_the_job_endpoint(client, monkeypatch):
    scenario_for(monkeypatch, plan_for())
    bad = lambda **kw: post_batch(client, IMAGES[:1], NAMES[:1], **kw).status_code  # noqa: E731
    assert bad(target_lang="xx") == 400 and bad(mode_a="0", mode_b="0") == 400 and bad(runs="4") == 400 and bad(output_size="8K") == 400
    assert post_batch(client, IMAGES[:1], NAMES[:1], models=["bad model!"]).status_code == 400
    assert post_batch(client, IMAGES[:1], NAMES[:1], models=[f"m/{i}" for i in range(5)]).status_code == 400
    assert bad(runs="many") == 422
    ok = post_batch(client, IMAGES[:1], NAMES[:1], output_size="default", judge="0", verify_details="1", mode_a_retry="0")
    assert ok.status_code == 200
    batch = wait_batch(client, ok.json()["batch_id"])
    assert batch["params"]["output_size"] == "" and batch["params"]["judge"] is False and batch["params"]["mode_a_retry"] is False
    assert batch["params"]["verify_details"] is True and batch["jobs"][0]["params"] == batch["params"]


def test_the_single_image_endpoint_still_works_and_is_not_part_of_a_batch(client, monkeypatch):
    scenario_for(monkeypatch, plan_for(n=1))
    r = client.post("/api/jobs", files={"file": ("one.png", IMAGES[0], "image/png")}, data={"mode_b": "1", "image_models": "m/a"})
    assert r.status_code == 200 and set(r.json()) == {"job_id"}
    deadline = time.time() + 20
    while time.time() < deadline:
        job = client.get(f"/api/jobs/{r.json()['job_id']}").json()
        if job["status"] == "done":
            break
        time.sleep(0.03)
    assert job["status"] == "done" and job["batch_id"] is None and job["index"] == 1 and job["image"]["name"] == "one.png"


def test_unknown_batches_and_bad_ids_are_404_and_the_routes_need_the_token(monkeypatch, tmp_path):
    client = make_client(monkeypatch, tmp_path, LAB_TOKEN="s3cret-token")
    for method, path in (
        ("post", "/api/batches"),
        ("get", "/api/batches/0123456789abcdef"),
        ("get", "/api/batches/0123456789abcdef/manifest"),
    ):
        assert getattr(client, method)(path).status_code == 401, path
    assert client.get("/api/batches/0123456789abcdef?token=s3cret-token").status_code == 404
    assert client.get("/api/batches/not-a-batch?token=s3cret-token").status_code == 404
    assert client.get("/api/batches/0123456789abcdef/manifest?token=s3cret-token").status_code == 404
    assert client.get("/api/batches/..%2f..%2fetc/manifest?token=s3cret-token").status_code in (404, 422)


def test_a_batch_can_be_polled_while_it_runs_and_is_read_back_after_a_restart(client, monkeypatch, tmp_path):
    scenario_for(monkeypatch, plan_for(n=4, sleep=0.4))
    r = post_batch(client)
    batch_id = r.json()["batch_id"]
    early = client.get(f"/api/batches/{batch_id}").json()
    assert early["status"] == "running" and early["publish"]["status"] == "pending" and len(early["jobs"]) == 4
    assert early["summary"] and {row["status"] for row in early["summary"]} <= {"pending", "running"}
    done = wait_batch(client, batch_id)
    fresh = TestClient(create_app())  # a restarted server finds batch.json and every result.json on disk
    again = fresh.get(f"/api/batches/{batch_id}").json()
    assert again["status"] == "done" and again["job_ids"] == done["job_ids"] and again["summary"] == done["summary"]
    assert again["publish"]["manifest_url"] == done["publish"]["manifest_url"]
    assert (tmp_path / "data" / f"batch-{batch_id}" / "batch.json").is_file()


# ---------------------------------------------------------------- the summary rows


def fixture_images(n=4):
    out = []
    for i in range(n):
        image = mock.make_fixture().image
        image.putpixel((0, 0), (i * 5, 1, 2))  # a different upload each time, the same words
        buf = io.BytesIO()
        image.save(buf, "PNG")
        out.append(buf.getvalue())
    return out


def test_the_summary_has_one_row_per_image_and_mode_or_model_with_the_cost_split(client, monkeypatch):
    images = fixture_images()
    words = mock.vision_words(640, 480)
    plan = {m: [step(words, cost=0.05) for _ in range(4)] for m in ("m/a", "m/b")}
    scenario = Scenario(images[0], words, plan)
    for data in images[1:]:
        scenario.add_source(data, words)
    scenario.install(monkeypatch)
    r = post_batch(client, images, NAMES, models=("m/a", "m/b"), mode_a="1")
    batch = wait_batch(client, r.json()["batch_id"])
    rows = batch["summary"]
    assert len(rows) == 12  # four images x (Mode A + two models)
    assert [(row["image_index"], row["mode"], row["model"]) for row in rows[:3]] == [
        (1, "A", "claude-opus-5-5"), (1, "B", "m/a"), (1, "B", "m/b"),
    ]  # fmt: skip
    assert [row["image_index"] for row in rows] == [i for i in (1, 2, 3, 4) for _ in range(3)]
    assert {row["image_name"] for row in rows} == set(NAMES) and all(row["status"] == "done" for row in rows)
    for row in rows:
        assert row["verdict"] == "pass" and row["usability"] == 1.0
        if row["mode"] == "A":  # Claude only: the planner tokens and the judge, both Anthropic
            assert row["cost_openrouter_usd"] == 0.0
            assert row["cost_anthropic_usd"] == pytest.approx((2345 * 4 + 612 * 20) / 1e6 + 0.01)  # planner tokens + the scripted judge
        else:  # OpenRouter cash for the generation, Anthropic for the judge
            assert row["cost_openrouter_usd"] == pytest.approx(0.05)
            assert row["cost_anthropic_usd"] == pytest.approx(0.01)  # the scripted judge's price
        assert row["cost_total_usd"] == pytest.approx(row["cost_anthropic_usd"] + row["cost_openrouter_usd"])
        assert isinstance(row["elapsed_ms"], int) and row["output"]
    totals = batch["totals"]
    assert totals["rows"] == 12 and totals["rows_passed"] == 12
    assert totals["cost_anthropic_usd"] == pytest.approx(sum(r["cost_anthropic_usd"] for r in rows))
    assert totals["cost_openrouter_usd"] == pytest.approx(8 * 0.05)  # only the eight Mode B rows cost cash
    assert batch["jobs"][0]["mode_a"]["verdict"] == "pass" and batch["jobs"][0]["mode_a"]["attempts"][0]["output"] == "A_a1.png"


def test_a_row_shows_the_verdict_and_the_usability_of_its_best_run(client, monkeypatch):
    bad = judge_record(usability=0.6, altered=[("77446716", "09446916")], cost=0.02)
    scenario = Scenario(IMAGES[0], SRC_OCR, {})
    scenario.plan[("m/a", IMAGES[0])] = [step(GOOD, judge=bad), step(GOOD, judge=bad)]  # image 1 fails twice (attempt + retry)
    scenario.plan[("m/a", IMAGES[1])] = [step(GOOD)]  # image 2 passes
    scenario.add_source(IMAGES[1], SRC_OCR).install(monkeypatch)
    r = post_batch(client, IMAGES[:2], NAMES[:2])
    batch = wait_batch(client, r.json()["batch_id"])
    first, second = batch["summary"]
    assert first["verdict"] == "numbers+score" and first["failed_checks"] == ["numbers", "score"] and first["usability"] == 0.6
    assert first["cost_anthropic_usd"] == pytest.approx(0.04)  # both attempts were judged
    assert second["verdict"] == "pass" and second["usability"] == 1.0
    assert batch["totals"]["rows_passed"] == 1 and batch["totals"]["rows"] == 2


# ---------------------------------------------------------------- the concurrency limits across the batch


def test_four_images_still_share_the_four_generation_slots(client, monkeypatch):
    scenario = scenario_for(monkeypatch, plan_for(models=("m/a", "m/b"), n=4, sleep=0.25))
    batch = wait_batch(client, post_batch(client, models=("m/a", "m/b")).json()["batch_id"])
    assert len(scenario.calls) == 8 and all(j["status"] == "done" for j in batch["jobs"])
    assert scenario.peak == 4  # eight generations at once would be eight: they queue on the process-wide semaphore


# ---------------------------------------------------------------- the batch manifest


class OrderedMinio(publish.FakeMinio):
    """The in-memory store that remembers the order of the uploads."""

    def __init__(self, fail_when=lambda name: False):
        super().__init__("s3.azndev.com")
        self.puts: list[str] = []
        self.fail_when = fail_when

    def put_object(self, bucket_name, object_name, data, length, content_type="application/octet-stream", **kw):
        if self.fail_when(object_name):
            raise OSError(f"connection reset while writing {object_name} with test-secret-key-0123456789")
        self.puts.append(object_name)
        return super().put_object(bucket_name, object_name, data, length, content_type, **kw)


@pytest.fixture
def store(monkeypatch):
    fake = OrderedMinio()
    monkeypatch.setattr(publish, "make_clients", lambda settings, mock: (fake, fake, []))
    return fake


def manifest_at(fake, key):
    return json.loads(fake.objects[("line-bot", key)]["data"])


def test_the_batch_manifest_is_published_last_with_the_url_of_every_job_manifest(monkeypatch, tmp_path, store):
    client = make_client(monkeypatch, tmp_path, LAB_MOCK="0", **MINIO_ENV)
    scenario_for(monkeypatch, plan_for(models=("m/a", "m/b"), n=4, cost=0.04))
    r = post_batch(client, models=("m/a", "m/b"))
    batch = wait_batch(client, r.json()["batch_id"])
    batch_id = batch["batch_id"]
    batch_key = f"overlay-lab/batch-{batch_id}/manifest.json"
    assert batch["publish"]["status"] == "done" and batch["warnings"] == []

    job_manifests = [f"overlay-lab/{job_id}/manifest.json" for job_id in batch["job_ids"]]
    assert all(key in store.puts for key in job_manifests) and store.puts.count(batch_key) == 1
    assert store.puts.index(batch_key) > max(store.puts.index(key) for key in job_manifests)  # after every job has published
    assert store.puts[-1] == batch_key

    m = manifest_at(store, batch_key)
    assert m["schema_version"] == 3 and m["kind"] == "batch" and m["batch_id"] == batch_id and m["mock"] is False
    assert m["bucket"] == "line-bot" and m["prefix"] == f"overlay-lab/batch-{batch_id}"
    assert m["manifest"]["key"] == batch_key and m["manifest"]["url"] == batch["publish"]["manifest_url"]
    assert m["manifest"]["url"] == f"https://s3.azndev.com/line-bot/{batch_key}?X-Amz-Expires=604800&X-Amz-Signature=mock"
    assert m["url_expiry_hours"] == 168 and m["urls_expire_at"] > m["published_at"] >= m["created_at"]
    assert m["target_language"] == ["en"] and [j["target_language"] for j in m["jobs"]] == ["en"] * 4
    assert m["options"]["image_models"] == ["m/a", "m/b"] and m["options"]["runs"] == 1 and m["options"]["mode_b"] is True
    assert m["options"]["judge_model"] == "claude-sonnet-5-5" and m["options"]["mode_a_retry"] is True and m["options"]["output_size"] == "2K"

    assert [j["index"] for j in m["jobs"]] == [1, 2, 3, 4] and [j["job_id"] for j in m["jobs"]] == batch["job_ids"]
    assert [j["image_name"] for j in m["jobs"]] == NAMES and all(j["status"] == "done" and j["publish_status"] == "done" for j in m["jobs"])
    for entry, job in zip(m["jobs"], batch["jobs"]):  # the presigned URL of every job manifest
        assert entry["manifest"]["url"] == job["publish"]["manifest_url"] and entry["manifest"]["key"] == f"overlay-lab/{job['job_id']}/manifest.json"
        assert entry["manifest"]["url"].startswith("https://s3.azndev.com/line-bot/overlay-lab/")
        assert entry["manifest"]["expires_at"] == job["publish"]["expires_at"]
    assert m["summary"] == batch["summary"] and m["totals"] == batch["totals"]  # the same rows as the page
    assert len(m["summary"]) == 8 and m["warnings"] == []

    # the job manifests are schema 3 too, and know their batch
    job_manifest = manifest_at(store, job_manifests[0])
    assert job_manifest["schema_version"] == 3 and job_manifest["batch_id"] == batch_id and job_manifest["image_index"] == 1
    # the local copy is served for the page
    assert client.get(f"/api/batches/{batch_id}/manifest").json()["batch_id"] == batch_id


def test_no_secret_or_ocr_text_is_in_the_batch_manifest(monkeypatch, tmp_path, store):
    client = make_client(monkeypatch, tmp_path, LAB_MOCK="0", **MINIO_ENV)
    scenario_for(monkeypatch, plan_for())
    batch = wait_batch(client, post_batch(client).json()["batch_id"])
    body = store.objects[("line-bot", f"overlay-lab/batch-{batch['batch_id']}/manifest.json")]["data"].decode("utf-8")
    for secret in ("test-secret-key", "test-access-key", "営業時間", "77446716", "TEL"):
        assert secret not in body, secret


def test_mock_mode_publishes_the_batch_manifest_to_the_memory_fake(monkeypatch, tmp_path):
    publish.mock_client().objects.clear()
    client = make_client(monkeypatch, tmp_path)
    scenario_for(monkeypatch, plan_for(n=2))
    batch = wait_batch(client, post_batch(client, IMAGES[:2], NAMES[:2]).json()["batch_id"])
    key = f"overlay-lab/batch-{batch['batch_id']}/manifest.json"
    assert key in publish.mock_client().keys("line-bot")
    assert batch["publish"]["status"] == "done" and batch["publish"]["mock"] is True and "LAB_MOCK" in batch["publish"]["note"]
    assert json.loads(publish.mock_client().objects[("line-bot", key)]["data"])["mock"] is True


def test_publishing_the_batch_manifest_never_fails_the_batch(monkeypatch, tmp_path):
    flaky = OrderedMinio(lambda name: name.endswith("/manifest.json") and "/batch-" in name)
    monkeypatch.setattr(publish, "make_clients", lambda settings, mock: (flaky, flaky, []))
    client = make_client(monkeypatch, tmp_path, LAB_MOCK="0", **MINIO_ENV)
    scenario_for(monkeypatch, plan_for(n=2))
    batch = wait_batch(client, post_batch(client, IMAGES[:2], NAMES[:2]).json()["batch_id"])
    assert batch["status"] == "done" and all(j["status"] == "done" and j["publish"]["status"] == "done" for j in batch["jobs"])
    assert batch["publish"]["status"] == "error" and "manifest_url" not in batch["publish"]
    assert any("批次 manifest" in w for w in batch["warnings"])
    assert "test-secret-key-0123456789" not in json.dumps(batch) and "***" in json.dumps(batch["warnings"])
    assert client.get(f"/api/batches/{batch['batch_id']}/manifest").status_code == 404


def test_a_batch_without_minio_settings_says_what_is_missing_and_stays_done(monkeypatch, tmp_path):
    client = make_client(monkeypatch, tmp_path, LAB_MOCK="0")  # real mode, no MINIO_*
    scenario_for(monkeypatch, plan_for(n=2))
    batch = wait_batch(client, post_batch(client, IMAGES[:2], NAMES[:2]).json()["batch_id"])
    assert batch["status"] == "done" and batch["publish"]["status"] == "skipped"
    for name in ("MINIO_ACCESS_KEY", "MINIO_SECRET_KEY", "MINIO_ENDPOINT"):
        assert name in batch["publish"]["note"]
    assert all(j["publish"]["status"] == "skipped" for j in batch["jobs"])


def test_a_job_whose_upload_failed_has_no_link_in_the_batch_manifest_and_a_warning(monkeypatch, tmp_path):
    state = {"job": None}
    flaky = OrderedMinio(lambda name: state["job"] is not None and name == f"overlay-lab/{state['job']}/manifest.json")
    monkeypatch.setattr(publish, "make_clients", lambda settings, mock: (flaky, flaky, []))
    client = make_client(monkeypatch, tmp_path, LAB_MOCK="0", **MINIO_ENV)
    scenario_for(monkeypatch, plan_for(n=3, sleep=0.3))
    r = post_batch(client, IMAGES[:3], NAMES[:3])
    state["job"] = r.json()["job_ids"][1]  # the second image's manifest cannot be uploaded
    batch = wait_batch(client, r.json()["batch_id"])
    assert batch["jobs"][1]["publish"]["status"] == "error" and batch["publish"]["status"] == "done"
    m = manifest_at(flaky, f"overlay-lab/batch-{batch['batch_id']}/manifest.json")
    assert [j["manifest"] is None for j in m["jobs"]] == [False, True, False]
    assert m["jobs"][1]["publish_status"] == "error" and any("image 2" in w for w in m["warnings"])
    assert any("image 2" in w for w in batch["warnings"])
    assert m["jobs"][0]["manifest"]["url"] == batch["jobs"][0]["publish"]["manifest_url"]


def test_the_batch_manifest_waits_for_the_slowest_job(monkeypatch, tmp_path, store):
    client = make_client(monkeypatch, tmp_path, LAB_MOCK="0", **MINIO_ENV)
    scenario = Scenario(IMAGES[0], SRC_OCR, {})
    scenario.plan[("m/a", IMAGES[0])] = [step(GOOD)]
    scenario.plan[("m/a", IMAGES[1])] = [step(GOOD, sleep=1.0)]  # image 2 takes a second longer
    scenario.add_source(IMAGES[1], SRC_OCR).install(monkeypatch)
    r = post_batch(client, IMAGES[:2], NAMES[:2])
    time.sleep(0.5)
    early = client.get(f"/api/batches/{r.json()['batch_id']}").json()
    assert early["jobs"][0]["status"] == "done" and early["jobs"][1]["status"] == "running"
    assert early["publish"]["status"] == "pending" and not any("batch-" in k for k in store.puts)  # nothing yet
    batch = wait_batch(client, r.json()["batch_id"])
    assert store.puts[-1] == f"overlay-lab/batch-{batch['batch_id']}/manifest.json" and batch["publish"]["status"] == "done"


def test_the_batch_manifest_key_is_under_the_configured_prefix(monkeypatch, tmp_path):
    from app.config import get_minio_settings

    monkeypatch.setenv("MINIO_PREFIX", "/lab/round5/")
    assert publish.batch_key(get_minio_settings(), "0123456789abcdef") == "lab/round5/batch-0123456789abcdef/manifest.json"
    assert Image.open(io.BytesIO(IMAGES[0])).size == (80, 60)


# ---------------------------------------------------------------- round 6: a target language per image


def langs_of(scenario):
    """The language each generation call was made for, by the order of the images (one model, one run)."""
    return [call["lang"] for call in scenario.calls]


def test_target_langs_give_every_image_its_own_language_end_to_end(client, monkeypatch):
    scenario = scenario_for(monkeypatch, plan_for(n=3))
    r = post_batch(client, IMAGES[:3], NAMES[:3], target_langs=["en", "zh-TW", "ja"])
    assert r.status_code == 200, r.text
    batch = wait_batch(client, r.json()["batch_id"])
    assert [j["params"]["target_lang"] for j in batch["jobs"]] == ["en", "zh-TW", "ja"]  # every job keeps its own language
    assert sorted(langs_of(scenario)) == ["en", "ja", "zh-TW"]
    assert batch["target_langs"] == ["en", "zh-TW", "ja"] and batch["target_language"] == ["en", "zh-TW", "ja"]
    assert [im["target_lang"] for im in batch["images"]] == ["en", "zh-TW", "ja"]
    assert batch["params"]["target_lang"] == "en"  # the batch's own language is the default of the form
    assert [row["target_lang"] for row in batch["summary"]] == ["en", "zh-TW", "ja"]
    for job in batch["jobs"]:  # the judge and the OCR check of each image used that image's language
        expected = {"en": "en", "zh-TW": "zh", "ja": "ja"}[job["params"]["target_lang"]]
        assert job["mode_b"]["results"][0]["verification"]["target_language"] == expected


def test_distinct_languages_of_the_batch_keep_the_order_of_the_images(client, monkeypatch):
    scenario_for(monkeypatch, plan_for(n=4))
    r = post_batch(client, IMAGES, NAMES, target_langs=["zh-TW", "en", "zh-TW", "en"])
    batch = wait_batch(client, r.json()["batch_id"])
    assert batch["target_langs"] == ["zh-TW", "en", "zh-TW", "en"] and batch["target_language"] == ["zh-TW", "en"]


def test_a_shorter_or_missing_target_langs_falls_back_to_the_batch_language(client, monkeypatch):
    scenario_for(monkeypatch, plan_for(n=4))
    short = wait_batch(client, post_batch(client, IMAGES, NAMES, target_lang="ko", target_langs=["ja"]).json()["batch_id"])
    assert short["target_langs"] == ["ja", "ko", "ko", "ko"] and short["target_language"] == ["ja", "ko"]
    assert [j["params"]["target_lang"] for j in short["jobs"]] == ["ja", "ko", "ko", "ko"]
    scenario_for(monkeypatch, plan_for(n=3))
    absent = wait_batch(client, post_batch(client, IMAGES[:3], NAMES[:3], target_lang="vi").json()["batch_id"])
    assert absent["target_langs"] == ["vi"] * 3 and absent["target_language"] == ["vi"]
    scenario_for(monkeypatch, plan_for(n=3))
    blank = wait_batch(client, post_batch(client, IMAGES[:3], NAMES[:3], target_langs=["", "ja", ""]).json()["batch_id"])
    assert blank["target_langs"] == ["en", "ja", "en"]  # an empty entry is "the batch's language" too


def test_a_bad_or_surplus_target_langs_value_rejects_the_whole_batch(client, monkeypatch, tmp_path):
    scenario = scenario_for(monkeypatch, plan_for(n=4))
    assert post_batch(client, IMAGES[:2], NAMES[:2], target_langs=["en", "xx"]).status_code == 400
    r = post_batch(client, IMAGES[:2], NAMES[:2], target_langs=["en", "ja", "ko"])
    assert r.status_code == 400 and "at most one per file" in r.json()["detail"]
    assert scenario.calls == []
    data_dir = tmp_path / "data"
    assert not data_dir.exists() or list(data_dir.iterdir()) == []  # nothing was started


def test_the_single_image_endpoint_is_unchanged_by_target_langs(client, monkeypatch):
    scenario = scenario_for(monkeypatch, plan_for(n=1))
    files = {"file": ("one.png", IMAGES[0], "image/png")}
    r = client.post("/api/jobs", files=files, data={"mode_b": "1", "image_models": "m/a", "target_lang": "ja"})
    assert r.status_code == 200
    deadline = time.time() + 20
    while time.time() < deadline and client.get(f"/api/jobs/{r.json()['job_id']}").json()["status"] != "done":
        time.sleep(0.03)
    assert langs_of(scenario) == ["ja"]


def test_the_batch_manifest_lists_the_distinct_languages_and_every_job_its_own(monkeypatch, tmp_path, store):
    client = make_client(monkeypatch, tmp_path, LAB_MOCK="0", **MINIO_ENV)
    scenario_for(monkeypatch, plan_for(n=3))
    r = post_batch(client, IMAGES[:3], NAMES[:3], target_langs=["en", "zh-TW", "en"])
    batch = wait_batch(client, r.json()["batch_id"])
    m = manifest_at(store, f"overlay-lab/batch-{batch['batch_id']}/manifest.json")
    assert m["schema_version"] == 3 and m["target_language"] == ["en", "zh-TW"]
    assert [j["target_language"] for j in m["jobs"]] == ["en", "zh-TW", "en"]
    assert [row["target_lang"] for row in m["summary"]] == ["en", "zh-TW", "en"]
    for entry, job in zip(m["jobs"], batch["jobs"]):  # the job manifests stay single-language
        assert manifest_at(store, entry["manifest"]["key"])["target_language"] == job["params"]["target_lang"]
    assert client.get(f"/api/batches/{batch['batch_id']}/manifest").json()["target_language"] == ["en", "zh-TW"]


def test_a_batch_from_before_round_6_still_reads_back_with_its_one_language(client, monkeypatch, tmp_path):
    scenario_for(monkeypatch, plan_for(n=2))
    batch = wait_batch(client, post_batch(client, IMAGES[:2], NAMES[:2]).json()["batch_id"])
    path = tmp_path / "data" / f"batch-{batch['batch_id']}" / "batch.json"
    record = json.loads(path.read_text(encoding="utf-8"))
    for key in ("target_langs", "target_language"):
        record.pop(key)
    for image in record["images"]:
        image.pop("target_lang")
    path.write_text(json.dumps(record), encoding="utf-8")
    again = TestClient(create_app()).get(f"/api/batches/{batch['batch_id']}").json()
    assert again["status"] == "done" and [row["target_lang"] for row in again["summary"]] == ["en", "en"]
    assert publish.batch_languages(again) == ["en"]


def test_start_batch_itself_gives_a_missing_or_blank_language_the_language_of_the_batch(monkeypatch, tmp_path):
    from app import jobs
    from app.imaging import load_image

    monkeypatch.setenv("LAB_MOCK", "1")
    monkeypatch.setenv("LAB_DATA_DIR", str(tmp_path / "data"))
    store = jobs.JobStore()

    def images():
        return [(load_image(png_of_size((80, 60), (i * 9, 5, 5))), f"{i}.png") for i in range(3)]

    def langs_of_batch(langs):
        batch_id, job_ids = jobs.start_batch(store, images(), "ko", True, False, [], langs=langs)
        deadline = time.time() + 30
        while time.time() < deadline and not all((store.snapshot(j) or {}).get("status") == "done" for j in job_ids):
            time.sleep(0.03)
        record = store.batch_record(batch_id)
        assert [store.snapshot(j)["params"]["target_lang"] for j in job_ids] == record["target_langs"]
        assert record["params"]["target_lang"] == "ko"
        return record["target_langs"], record["target_language"]

    assert langs_of_batch(None) == (["ko", "ko", "ko"], ["ko"])  # the argument is optional
    assert langs_of_batch(["ja"]) == (["ja", "ko", "ko"], ["ja", "ko"])  # a shorter list
    assert langs_of_batch(["", "vi", "vi"]) == (["ko", "vi", "vi"], ["ko", "vi"])  # a blank entry
    assert jobs.distinct_languages(["en", "zh-TW", "en", ""]) == ["en", "zh-TW"]
