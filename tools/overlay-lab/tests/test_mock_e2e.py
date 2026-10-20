"""LAB_MOCK=1 end to end through the HTTP API: erase + render without any credentials."""

import io
import json
import time

import cv2
import numpy as np
import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app import mock
from app.main import create_app


def png_of(img: Image.Image) -> bytes:
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return buf.getvalue()


def fetch_png(client: TestClient, job_id: str, name: str) -> np.ndarray:
    r = client.get(f"/files/{job_id}/{name}")
    assert r.status_code == 200 and r.headers["content-type"] == "image/png", name
    assert r.content[:8] == b"\x89PNG\r\n\x1a\n"
    return np.array(Image.open(io.BytesIO(r.content)).convert("RGB"))


def wait_done(client: TestClient, job_id: str, timeout: float = 60.0) -> dict:
    deadline = time.time() + timeout
    while time.time() < deadline:
        job = client.get(f"/api/jobs/{job_id}").json()
        if job["status"] == "done":
            return job
        time.sleep(0.05)
    raise AssertionError("job did not finish")


def wait_published(client: TestClient, job_id: str, timeout: float = 30.0) -> dict:
    """Like wait_done, but also waits for the background upload to settle."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        job = client.get(f"/api/jobs/{job_id}").json()
        if job["status"] == "done" and job["publish"]["status"] in ("done", "skipped", "error"):
            return job
        time.sleep(0.05)
    raise AssertionError("job was not published")


def polygon_mask(shape, polygons, grow=0) -> np.ndarray:
    mask = np.zeros(shape[:2], np.uint8)
    for poly in polygons:
        cv2.fillPoly(mask, [np.round(np.asarray(poly)).astype(np.int32)], 255)
    if grow:
        mask = cv2.dilate(mask, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * grow + 1, 2 * grow + 1)))
    return mask


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setenv("LAB_MOCK", "1")
    monkeypatch.setenv("LAB_DATA_DIR", str(tmp_path / "data"))
    return TestClient(create_app())


def test_fixture_image_has_ink_inside_every_canned_word_box():
    fx = mock.make_fixture()
    arr = np.array(fx.image).astype(int)
    ink = (np.abs(arr - np.array(mock.BACKGROUND)).max(axis=2) > 24).astype(np.uint8) * 255
    inside = polygon_mask(arr.shape, [w.polygon for w in fx.words], grow=1)
    assert ink.any() and not (ink & ~inside).any(), "canned Vision boxes must cover all fixture ink"
    for w in fx.words:
        assert (ink & polygon_mask(arr.shape, [w.polygon])).any(), w.text


def test_mock_end_to_end_both_modes(client):
    fx = mock.make_fixture()
    original = np.array(fx.image)
    r = client.post(
        "/api/jobs",
        files={"file": ("fixture.png", png_of(fx.image), "image/png")},
        data={"target_lang": "en", "mode_a": "1", "mode_b": "1", "image_models": "mock/image-model", "verify": "1",
              "verify_details": "1"},
    )  # fmt: skip
    assert r.status_code == 200
    job = wait_done(client, r.json()["job_id"])
    job_id = job["job_id"]

    # ---- Mode A
    a = job["mode_a"]
    assert a["status"] == "done" and a["error"] is None, a.get("error")
    assert a["tokens"] == {"input": 2345, "output": 612}
    assert a["cost_usd"] == pytest.approx((2345 * 4 + 612 * 20) / 1e6)  # the planner; the judge comes on top
    assert a["total_cost_usd"] == pytest.approx(a["cost_usd"] + (3400 * 2 + 420 * 10) / 1e6) == pytest.approx(a["cost_anthropic_usd"])
    assert a["cost_openrouter_usd"] == 0.0
    assert a["fallback_form"] == "mock"
    assert (a["word_count"], a["unit_count"], a["translated_count"]) == (7, 4, 3)
    for key in ("elapsed_ms", "vision_ms", "claude_ms", "erase_ms", "render_ms"):
        assert isinstance(a[key], int) and a[key] >= 0
    units = {u["id"]: u for u in a["units"]}
    assert [units[i]["action"] for i in ("u01", "u02", "u03", "u04")] == ["translated", "kept", "translated", "translated"]
    for u in a["units"]:
        for field in ("word_ids", "source_text", "translation", "direction", "role", "keep", "bbox", "angle"):
            assert field in u, (u["id"], field)
    translated = [u for u in a["units"] if u["action"] == "translated"]
    for u in translated:
        assert isinstance(u["font_size"], int) and u["font_size"] >= 10
        assert u["fitted"] is True and u["overflowed"] is False and u["readable"] is True, u
        assert u["erase_method"] == "flat" and u["text_used"] in ("full", "short") and u["importance"] == "major"
    assert units["u04"]["angle"] == pytest.approx(15.0, abs=1.0)
    assert units["u03"]["layout"] == "vertical_latin" and units["u03"]["direction"] == "vertical_rl"

    out = fetch_png(client, job_id, a["output"])
    erased = fetch_png(client, job_id, a["erased"])
    assert (a["output"], a["erased"], a["selected_attempt"], a["verdict"]) == ("A_a1.png", "A_a1_erased.png", 1, "pass")
    assert [t["n"] for t in a["attempts"]] == [1] and a["verification"]["judge"]["fallback_form"] == "mock"  # the judge saw it too
    assert out.shape == original.shape == erased.shape  # original size

    # ---- erased areas are within 12 RGB of the background
    background = np.array(mock.BACKGROUND)
    translated_words = {wid for u in translated for wid in u["word_ids"]}
    word_polys = [w.polygon for w in mock.make_fixture().words if w.id in translated_words]
    erase_zone = polygon_mask(original.shape, word_polys) > 0
    assert erase_zone.sum() > 3000
    assert np.abs(erased[erase_zone].astype(int) - background).max() <= 12
    # ...while a kept price and the untouched page are left exactly as they were
    kept_poly = [w.polygon for w in mock.make_fixture().words if w.id in units["u02"]["word_ids"]]
    kept = polygon_mask(original.shape, kept_poly) > 0
    assert np.array_equal(erased[kept], original[kept]) and np.array_equal(out[kept], original[kept])
    assert np.array_equal(out[400:, :], original[400:, :])

    # ---- rendered ink lies inside its unit frame (no overflow flagged -> no excuse)
    ink = (np.abs(out.astype(int) - erased.astype(int)).max(axis=2) > 8).astype(np.uint8) * 255
    assert ink.any()
    frames = {u["id"]: polygon_mask(original.shape, [u["frame"]], grow=1) for u in translated}
    union = np.zeros(original.shape[:2], np.uint8)
    for m in frames.values():
        union |= m
    assert not (ink & ~union).any(), "ink outside every unit frame"
    for uid, m in frames.items():
        assert (ink & m).sum() > 80, f"{uid} drew no text"
    # the ink bbox reported per unit sits inside the frame bbox (rotated frames: its bbox)
    for u in translated:
        x0, y0, x1, y1 = u["ink_bbox"]
        fx0, fy0, fx1, fy1 = u["bbox"]
        assert fx0 - 2 <= x0 and x1 <= fx1 + 2 and fy0 - 2 <= y0 and y1 <= fy1 + 2, u["id"]

    # ---- Mode B in mock returns the input image unchanged
    assert job["mode_b"]["status"] == "done"
    (b,) = job["mode_b"]["results"]
    assert b["status"] == "done" and b["error"] is None, b.get("error")
    assert b["api_used"] == "mock" and b["model"] == "mock/image-model" and b["cost_usd"] == 0.0
    # the mock output is the source image, so the verification passes and no retry happens
    assert b["verdict"] == "pass" and b["verification"]["failed_checks"] == []
    warnings = b["verification"]["ocr_warnings"]
    assert warnings["digits_ok"] and warnings["leftover_ok"] and warnings["warnings"] == []
    assert warnings["output_text"].startswith("TONKATSU SET MENU") and isinstance(b["verify_ms"], int)
    assert len(b["tries"]) == 1 and b["selected_attempt"] == 1
    # the vision judge has a canned answer in mock mode: fully offline, nothing wrong, 3400 / 420 tokens
    judged = b["verification"]["judge"]
    assert judged["status"] == "ok" and judged["fallback_form"] == "mock" and judged["usability"] == 1.0
    assert (judged["input_tokens"], judged["output_tokens"]) == (3400, 420) and judged["legibility_issues"] == []
    assert b["judge_cost_usd"] == pytest.approx((3400 * 2 + 420 * 10) / 1e6) and b["tries"][0]["judge_status"] == "ok"
    assert b["cost_anthropic_usd"] == b["judge_cost_usd"] and b["cost_openrouter_usd"] == 0.0
    assert b["verification"]["coverage"] == 1.0 and b["verification"]["usability"] == 1.0
    assert b["output"] == "B1_mock_image-model_r1_a1.png"
    assert np.array_equal(fetch_png(client, job_id, b["output"]), original)
    assert b["output_size"] == [640, 480] and b["aspect_drift_pct"] == 0.0

    # ---- the job stays retrievable (also from disk) and the original is served back
    assert fetch_png(client, job_id, "original.png").shape == original.shape
    assert (client.app.state.store.snapshot(job_id) or {}).get("status") == "done"
    fresh = TestClient(create_app())  # a restarted server finds result.json on disk
    assert fresh.get(f"/api/jobs/{job_id}").json()["mode_a"]["translated_count"] == 3


def test_mock_batch_end_to_end_is_offline_and_summarises_the_runs(client, monkeypatch):
    import anthropic
    import httpx2

    from app import publish

    # nothing may leave the box: no Claude client, no HTTP client of any kind
    monkeypatch.setattr(anthropic, "Anthropic", lambda **kw: pytest.fail("mock mode must not build a Claude client"))
    monkeypatch.setattr(httpx2, "Client", lambda *a, **k: pytest.fail("mock mode must not open an HTTP connection"))
    publish.mock_client().objects.clear()
    fx = mock.make_fixture()
    r = client.post(
        "/api/jobs",
        files={"file": ("fixture.png", png_of(fx.image), "image/png")},
        data={"target_lang": "en", "mode_a": "0", "mode_b": "1", "image_models": ["mock/image-model", "other/mock"], "runs": "3"},
    )
    assert r.status_code == 200
    job = wait_published(client, r.json()["job_id"])
    results = job["mode_b"]["results"]
    assert len(results) == 6 and all(x["status"] == "done" and x["verdict"] == "pass" for x in results)
    assert [(x["model"], x["run"]) for x in results] == [(m, k) for m in ("mock/image-model", "other/mock") for k in (1, 2, 3)]
    assert all(x["verification"]["judge"]["fallback_form"] == "mock" for x in results)
    first, second = job["mode_b"]["summary"]
    for row in (first, second):
        assert (row["runs_done"], row["passes"], row["pass_rate"], row["best_run"]) == (3, 3, 1.0, 1)
    # published, with every run in the manifest
    assert job["publish"]["status"] == "done" and job["publish"]["mock"] is True
    manifest = client.get(f"/files/{job['job_id']}/manifest.json").json()
    assert [len(m["runs"]) for m in manifest["mode_b"]] == [3, 3] and manifest["options"]["runs"] == 3
    assert manifest["mode_b"][0]["summary"]["pass_rate"] == 1.0


def test_mock_cjk_target_exercises_vertical_cjk_layout(client):
    fx = mock.make_fixture()
    r = client.post(
        "/api/jobs",
        files={"file": ("fixture.png", png_of(fx.image), "image/png")},
        data={"target_lang": "zh-TW", "mode_a": "1", "mode_b": "0"},
    )
    job = wait_done(client, r.json()["job_id"])
    assert job["mode_b"]["status"] == "skipped"
    a = job["mode_a"]
    assert a["status"] == "done", a.get("error")
    col = next(u for u in a["units"] if u["id"] == "u03")
    assert col["layout"] == "vertical_cjk" and col["fitted"] is True


def test_mode_errors_are_isolated_per_mode(client, monkeypatch):
    from app import openrouter

    def boom(*a, **k):
        raise RuntimeError("image model exploded")

    monkeypatch.setattr(openrouter, "run_image_model", boom)
    fx = mock.make_fixture()
    r = client.post(
        "/api/jobs",
        files={"file": ("fixture.png", png_of(fx.image), "image/png")},
        data={"target_lang": "en", "mode_a": "1", "mode_b": "1"},
    )
    job = wait_done(client, r.json()["job_id"])
    assert job["mode_a"]["status"] == "done"
    (b,) = job["mode_b"]["results"]
    assert b["status"] == "error" and "image model exploded" in b["error"]
    assert job["mode_b"]["status"] == "error" and job["status"] == "done"


def test_errors_never_leak_secrets(client, monkeypatch):
    from app import claude_units

    monkeypatch.setenv("ANTHROPIC_API_KEY", "fake-secret-key-value")

    def boom(*a, **k):
        raise RuntimeError("request failed for key fake-secret-key-value")

    monkeypatch.setattr(claude_units, "request_units", boom)
    fx = mock.make_fixture()
    r = client.post(
        "/api/jobs",
        files={"file": ("fixture.png", png_of(fx.image), "image/png")},
        data={"target_lang": "en", "mode_a": "1", "mode_b": "0"},
    )
    job = wait_done(client, r.json()["job_id"])
    assert "fake-secret-key-value" not in str(job) and "***" in job["mode_a"]["error"]


def test_exif_rotated_upload_is_normalised_before_ocr(client):
    img = Image.new("RGB", (200, 100), (255, 255, 255))
    exif = Image.Exif()
    exif[274] = 6  # rotate 90 degrees clockwise when displayed
    buf = io.BytesIO()
    img.save(buf, "JPEG", exif=exif)
    r = client.post(
        "/api/jobs",
        files={"file": ("rot.jpg", buf.getvalue(), "image/jpeg")},
        data={"target_lang": "en", "mode_a": "0", "mode_b": "1"},
    )
    job = wait_done(client, r.json()["job_id"])
    assert (job["image"]["width"], job["image"]["height"]) == (100, 200)


def fixture_uploads(n=4):
    """n different uploads of the mock fixture (one pixel differs), so every job has its own bytes and the same words."""
    out = []
    for i in range(n):
        image = mock.make_fixture().image
        image.putpixel((0, 0), (i * 7, 1, 2))
        out.append(png_of(image))
    return out


def test_mock_four_image_batch_end_to_end_is_offline_publishes_and_logs_nothing_private(client, monkeypatch, caplog):
    import logging

    import anthropic
    import httpx2

    from app import publish

    # nothing may leave the box: no Claude client, no HTTP client of any kind
    monkeypatch.setattr(anthropic, "Anthropic", lambda **kw: pytest.fail("mock mode must not build a Claude client"))
    monkeypatch.setattr(httpx2, "Client", lambda *a, **k: pytest.fail("mock mode must not open an HTTP connection"))
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-ant-test-0000000000000000")
    monkeypatch.setenv("LAB_TOKEN", "lab-token-secret-value")
    publish.mock_client().objects.clear()
    names = ["receipt-secret-name.png", "menu.png", "poster.png", "card.png"]
    files = [("files", (name, data, "image/png")) for name, data in zip(names, fixture_uploads())]
    with caplog.at_level(logging.INFO):
        r = client.post(
            "/api/batches?token=lab-token-secret-value", files=files,
            data={"target_lang": "en", "mode_a": "1", "mode_b": "1", "image_models": ["mock/image-model", "other/mock"], "verify_details": "1"},
        )  # fmt: skip
        assert r.status_code == 200, r.text
        batch_id = r.json()["batch_id"]
        deadline = time.time() + 120
        while time.time() < deadline:
            batch = client.get(f"/api/batches/{batch_id}?token=lab-token-secret-value").json()
            if batch["status"] == "done" and batch["publish"]["status"] in ("done", "skipped", "error"):
                break
            time.sleep(0.1)
        else:
            raise AssertionError("the batch did not finish")

    assert batch["status"] == "done" and batch["publish"]["status"] == "done" and len(batch["jobs"]) == 4
    assert [j["image"]["name"] for j in batch["jobs"]] == names
    for job in batch["jobs"]:
        assert job["mode_a"]["status"] == "done" and job["mode_a"]["verdict"] == "pass", job["mode_a"].get("error")
        assert [x["verdict"] for x in job["mode_b"]["results"]] == ["pass", "pass"] and job["publish"]["status"] == "done"
    assert len(batch["summary"]) == 12 and all(row["verdict"] == "pass" for row in batch["summary"])
    assert batch["totals"]["rows_passed"] == 12 and batch["totals"]["cost_openrouter_usd"] == 0.0

    # published to the in-memory fake: every job manifest and one batch manifest, the batch one last
    keys = publish.mock_client().keys("line-bot")
    assert f"overlay-lab/batch-{batch_id}/manifest.json" in keys
    assert all(f"overlay-lab/{job_id}/manifest.json" in keys for job_id in batch["job_ids"])
    manifest = json.loads(publish.mock_client().objects[("line-bot", f"overlay-lab/batch-{batch_id}/manifest.json")]["data"])
    assert manifest["schema_version"] == 3 and len(manifest["jobs"]) == 4 and all(j["manifest"] for j in manifest["jobs"])

    # the log: counts and ids only, no OCR text, no translation, no file name, no secret, no image data
    logged = " ".join(rec.getMessage() for rec in caplog.records if rec.name.startswith("lab."))  # the lab's own loggers
    assert "stage=judge" in logged and "stage=publish batch=" in logged
    for private in ("TONKATSU", "NT$120", "紅燒肉飯店", "LUNCH", "SPECIAL", "Pork Cutlet", "Braised", "special lunch",
                    "receipt-secret-name", "sk-ant-test", "lab-token-secret-value", "X-Amz-Signature", "/9j/", "iVBOR"):  # fmt: skip
        assert private not in logged, private


def test_a_failure_message_that_quotes_image_text_reaches_the_page_but_not_the_log(client, monkeypatch, caplog):
    import logging

    from app import claude_units
    from app.models import ModeError

    def boom(*a, **k):
        raise ModeError("Claude's JSON does not match the unit schema: input_value='紅燒肉飯店 NT$120'")

    monkeypatch.setattr(claude_units, "request_units", boom)
    fx = mock.make_fixture()
    with caplog.at_level(logging.INFO):
        r = client.post(
            "/api/jobs",
            files={"file": ("fixture.png", png_of(fx.image), "image/png")},
            data={"target_lang": "en", "mode_a": "1", "mode_b": "0"},
        )
        job = wait_done(client, r.json()["job_id"])
    assert job["mode_a"]["status"] == "error" and "紅燒肉飯店" in job["mode_a"]["error"]  # the owner sees why
    logged = " ".join(rec.getMessage() for rec in caplog.records if rec.name.startswith("lab."))
    assert "mode A failed" in logged and "ModeError" in logged and "紅燒肉飯店" not in logged and "NT$120" not in logged
    assert not any(rec.exc_info for rec in caplog.records if rec.name.startswith("lab."))  # no traceback with the message in it
