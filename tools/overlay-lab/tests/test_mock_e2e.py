"""LAB_MOCK=1 end to end through the HTTP API: erase + render without any credentials."""

import io
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
        data={"target_lang": "en", "mode_a": "1", "mode_b": "1", "image_model": "mock/image-model", "verify": "1"},
    )
    assert r.status_code == 200
    job = wait_done(client, r.json()["job_id"])
    job_id = job["job_id"]

    # ---- Mode A
    a = job["mode_a"]
    assert a["status"] == "done" and a["error"] is None, a.get("error")
    assert a["tokens"] == {"input": 2345, "output": 612}
    assert a["cost_usd"] == pytest.approx((2345 * 4 + 612 * 20) / 1e6)
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
        assert u["fitted"] is True and u["overflowed"] is False, u
    assert units["u04"]["angle"] == pytest.approx(15.0, abs=1.0)
    assert units["u03"]["layout"] == "vertical_latin" and units["u03"]["direction"] == "vertical_rl"

    out = fetch_png(client, job_id, "A.png")
    erased = fetch_png(client, job_id, "A_erased.png")
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
    b = job["mode_b"]
    assert b["status"] == "done" and b["error"] is None, b.get("error")
    assert b["api_used"] == "mock" and b["model"] == "mock/image-model" and b["cost_usd"] == 0.0
    assert b["verify_text"].startswith("MOCK OCR TEXT") and isinstance(b["verify_ms"], int)
    assert np.array_equal(fetch_png(client, job_id, "B.png"), original)
    assert b["output_size"] == [640, 480] and b["aspect_drift_pct"] == 0.0

    # ---- the job stays retrievable (also from disk) and the original is served back
    assert fetch_png(client, job_id, "original.png").shape == original.shape
    assert (client.app.state.store.snapshot(job_id) or {}).get("status") == "done"
    fresh = TestClient(create_app())  # a restarted server finds result.json on disk
    assert fresh.get(f"/api/jobs/{job_id}").json()["mode_a"]["translated_count"] == 3


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
    assert job["mode_b"]["status"] == "error" and "image model exploded" in job["mode_b"]["error"]


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
