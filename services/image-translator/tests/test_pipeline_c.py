"""Mode C end to end: run_mode_c, the lab API with `mode_c`, the batch summary and the schema 4 manifest (all offline)."""

import io
import json
import time
from pathlib import Path

import numpy as np
import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app import batch, claude_text, mock, pipeline_a, pipeline_c, publish
from app.imaging import load_image
from app.main import create_app
from app.models import OcrResult


def png_of(img: Image.Image) -> bytes:
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return buf.getvalue()


def fetch_png(client, job_id, name):
    r = client.get(f"/lab/files/{job_id}/{name}")
    assert r.status_code == 200 and r.headers["content-type"] == "image/png", name
    return np.array(Image.open(io.BytesIO(r.content)).convert("RGB"))


def wait_done(client, job_id, timeout=60.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        job = client.get(f"/lab/api/jobs/{job_id}").json()
        if job["status"] == "done" and job["publish"]["status"] in ("done", "skipped", "error"):
            return job
        time.sleep(0.05)
    raise AssertionError("job did not finish")


def wait_batch(client, batch_id, timeout=90.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        doc = client.get(f"/lab/api/batches/{batch_id}").json()
        if doc["status"] == "done" and doc["publish"]["status"] in ("done", "skipped", "error"):
            return doc
        time.sleep(0.05)
    raise AssertionError("batch did not finish")


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setenv("LAB_MOCK", "1")
    monkeypatch.setenv("LAB_DATA_DIR", str(tmp_path / "data"))
    return TestClient(create_app())


def fixture_form(**kw):
    data = {"target_lang": "zh-TW", "mode_a": "0", "mode_b": "0", "mode_c": "1", "verify": "1", "verify_details": "1"}
    data.update(kw)
    return data


def post_job(client, **kw):
    fx = mock.make_fixture()
    r = client.post("/lab/api/jobs", files={"file": ("f.png", png_of(fx.image), "image/png")}, data=fixture_form(**kw))
    assert r.status_code == 200, r.text
    return r.json()["job_id"]


# ---------------------------------------------------------------- run_mode_c


class MockSource:
    """What jobs.SourceOcr is to run_mode_c: `get()` and the milliseconds the call took."""

    def __init__(self, ocr):
        self._ocr, self.ms = ocr, 7

    def get(self):
        return self._ocr


def mock_run(tmp_path, monkeypatch, **kw):
    monkeypatch.setenv("LAB_MOCK", "1")
    fx = mock.make_fixture()
    image = load_image(png_of(fx.image))
    ocr = mock.vision_words(image.width, image.height)
    return image, ocr, pipeline_c.run_mode_c(image, "zh-TW", tmp_path, MockSource(ocr), **kw)


def test_run_mode_c_returns_the_shape_of_run_mode_a_plus_the_segmentation(tmp_path, monkeypatch):
    image, ocr, c = mock_run(tmp_path, monkeypatch)
    a = pipeline_a.run_mode_a(image, "zh-TW", tmp_path / "a", MockSource(ocr))
    assert set(a) <= set(c)  # every key Mode A has, Mode C has too
    assert {"segment_ms", "segment", "claude_calls", "chunk_sizes"} <= set(c) - set(a)
    assert (c["word_count"], c["unit_count"], c["translated_count"]) == (7, 4, 3)
    assert c["tokens"]["input"] > 0 and c["cost_usd"] > 0 and c["model"] == "claude-opus-5-5" and c["fallback_form"] == "mock"
    assert c["cost_openrouter_usd"] == 0.0 and c["total_cost_usd"] == c["cost_anthropic_usd"] == c["cost_usd"]  # no judge here
    assert c["vision_ms"] == 7 and all(isinstance(c[k], int) and c[k] >= 0 for k in ("elapsed_ms", "segment_ms", "claude_ms", "erase_ms", "render_ms"))
    assert (c["output"], c["erased"], c["units_file"], c["selected_attempt"]) == ("C_a1.png", "C_a1_erased.png", "C_a1_units.json", 1)
    assert c["retry"] is False and c["retry_error"] is None and c["verdict"] is None and c["verification"] is None  # no check given
    assert len(c["attempts"]) == 1 and c["attempts"][0]["n"] == 1 and c["attempts"][0]["selected"] is True
    assert c["attempts"][0]["units"] is c["units"] and c["claude_calls"] == 1 and c["chunk_sizes"] == [3]
    assert [u["action"] for u in c["units"]] == ["translated", "kept", "translated", "translated"]
    assert c["segment"]["units"] == 4 and c["segment"]["kept"] == 1 and len(c["segment"]["reasons"]) == 4
    assert all(u["polarity"] and "keep_kind" in u for u in c["units"]) and c["units"][1]["keep_kind"] == "price"


def test_the_files_of_mode_c_and_the_units_json(tmp_path, monkeypatch):
    image, ocr, c = mock_run(tmp_path, monkeypatch, meta={"job_id": "abc", "target_lang": "zh-TW"})
    assert sorted(p.name for p in tmp_path.iterdir()) == ["C_a1.png", "C_a1_erased.png", "C_a1_units.json"]
    out = np.array(Image.open(tmp_path / "C_a1.png").convert("RGB"))
    erased = np.array(Image.open(tmp_path / "C_a1_erased.png").convert("RGB"))
    original = image.rgb()
    assert out.shape == erased.shape == original.shape and not np.array_equal(out, erased)  # the text was drawn on the erased image
    doc = json.loads((tmp_path / "C_a1_units.json").read_text(encoding="utf-8"))
    assert doc["mode"] == "C" and doc["job_id"] == "abc" and doc["attempt"] == 1 and doc["page"] == [640, 480]
    assert doc["segment"]["constants"]["COLUMN_GAP_RATIO"] == 2.0 and len(doc["units"]) == 4
    kept = [w.polygon for w in ocr.words if w.id in c["units"][1]["word_ids"]]
    assert kept and np.array_equal(erased[int(kept[0][0][1]) : int(kept[0][2][1]), int(kept[0][0][0]) : int(kept[0][2][0])], original[int(kept[0][0][1]) : int(kept[0][2][1]), int(kept[0][0][0]) : int(kept[0][2][0])])


def test_a_kept_price_is_not_erased_or_touched_while_the_rest_is(tmp_path, monkeypatch):
    image, ocr, c = mock_run(tmp_path, monkeypatch)
    out = np.array(Image.open(tmp_path / "C_a1.png").convert("RGB"))
    original = image.rgb()
    price = next(w for w in ocr.words if w.text == "NT$120")
    xs = [p[0] for p in price.polygon]
    ys = [p[1] for p in price.polygon]
    box = (slice(int(min(ys)), int(max(ys))), slice(int(min(xs)), int(max(xs))))
    assert np.array_equal(out[box], original[box])  # the words of a kept unit keep their pixels
    title = next(w for w in ocr.words if w.text == "TONKATSU")
    tx, ty = [p[0] for p in title.polygon], [p[1] for p in title.polygon]
    assert not np.array_equal(out[int(min(ty)) : int(max(ty)), int(min(tx)) : int(max(tx))], original[int(min(ty)) : int(max(ty)), int(min(tx)) : int(max(tx))])


def test_the_translation_is_handed_over_before_anything_is_drawn(tmp_path, monkeypatch):
    seen = []

    def boom(*args, **kwargs):
        raise RuntimeError("the renderer failed")

    monkeypatch.setattr(pipeline_c, "render_units", boom)
    monkeypatch.setenv("LAB_MOCK", "1")
    image = load_image(png_of(mock.make_fixture().image))
    ocr = mock.vision_words(image.width, image.height)
    with pytest.raises(RuntimeError, match="renderer failed"):
        pipeline_c.run_mode_c(image, "en", tmp_path, MockSource(ocr), on_translate=seen.append)
    assert len(seen) == 1 and isinstance(seen[0], claude_text.TextCall) and seen[0].cost_usd > 0  # the cost survives the failure


def test_a_picture_without_text_is_an_error_not_an_empty_result(tmp_path, monkeypatch):
    monkeypatch.setenv("LAB_MOCK", "1")
    image = load_image(png_of(Image.new("RGB", (200, 100), (250, 250, 250))))
    with pytest.raises(RuntimeError, match="no text"):
        pipeline_c.run_mode_c(image, "en", tmp_path, MockSource(OcrResult(width=200, height=100, words=[])))


def test_the_check_runs_on_the_output_and_its_fields_land_in_the_attempt(tmp_path, monkeypatch):
    seen = []

    def check(png, size):
        seen.append((png[:8], size))
        return {"verdict": "pass", "verification": {"failed_checks": [], "usability": 0.9, "coverage": 0.8}, "judge_ms": 5, "judge_cost_usd": 0.001, "verify_ms": 9}

    image, ocr, c = mock_run(tmp_path, monkeypatch, check=check)
    assert seen == [(b"\x89PNG\r\n\x1a\n", (640, 480))]
    assert (c["verdict"], c["usability"], c["coverage"], c["judge_ms"], c["verify_ms"]) == ("pass", 0.9, 0.8, 5, 9)
    assert c["judge_cost_usd"] == 0.001 and c["total_cost_usd"] == pytest.approx(c["cost_usd"] + 0.001)
    assert c["verification_file"] == "C_a1_verify.json" and (tmp_path / "C_a1_verify.json").is_file()


# ---------------------------------------------------------------- the lab API


def test_a_job_with_only_mode_c_runs_and_the_page_data_has_everything(client):
    job = wait_done(client, post_job(client))
    assert job["params"]["mode_c"] is True and job["params"]["mode_a"] is False
    assert job["mode_a"]["status"] == "skipped" and job["mode_b"]["status"] == "skipped"
    c = job["mode_c"]
    assert c["status"] == "done" and c["error"] is None, c.get("error")
    assert (c["output"], c["erased"], c["verdict"], c["selected_attempt"]) == ("C_a1.png", "C_a1_erased.png", "pass", 1)
    assert c["verification"]["structure_checks"] is False and c["verification"]["checks"]["rows"]["ok"] is True  # drawn in place
    assert c["verification"]["judge"]["fallback_form"] == "mock" and c["total_cost_usd"] > c["cost_usd"] > 0
    assert job["params"]["translate_model"] == "claude-opus-5-5" and job["params"]["inpaint_model"] == "telea"
    assert job["mode_c"]["units"][0]["inpaint"] is None  # flat fills: no inpainter involved
    assert fetch_png(client, job["job_id"], c["output"]).shape == (480, 640, 3)
    assert client.get(f"/lab/files/{job['job_id']}/C_a1_units.json").status_code == 200
    assert (client.app.state.store.snapshot(job["job_id"]) or {}).get("status") == "done"
    fresh = TestClient(create_app())  # a restarted server finds result.json on disk
    assert fresh.get(f"/lab/api/jobs/{job['job_id']}").json()["mode_c"]["translated_count"] == 3


def test_the_job_without_mode_c_has_it_skipped_and_nothing_else_changes(client):
    job = wait_done(client, post_job(client, mode_a="1", mode_c="0"))
    assert job["mode_c"] == {"status": "skipped", "error": None} and job["params"]["mode_c"] is False
    assert job["mode_a"]["status"] == "done"


def test_all_three_modes_in_one_job_and_the_job_waits_for_every_one(client):
    job = wait_done(client, post_job(client, mode_a="1", mode_b="1", image_models="mock/image-model"))
    assert [job[k]["status"] for k in ("mode_a", "mode_c")] == ["done", "done"] and job["mode_b"]["status"] == "done"
    assert job["status"] == "done"
    assert job["mode_a"]["output"] == "A_a1.png" and job["mode_c"]["output"] == "C_a1.png"


@pytest.mark.parametrize("modes, status", [({"mode_a": "0", "mode_b": "0", "mode_c": "0"}, 400), ({"mode_c": "1", "mode_a": "0", "mode_b": "0"}, 200),
                                           ({"mode_a": "0", "mode_b": "1", "mode_c": "0"}, 200), ({"mode_a": "1", "mode_b": "0", "mode_c": "0"}, 200)])
def test_at_least_one_mode_is_required(client, modes, status):
    fx = mock.make_fixture()
    r = client.post("/lab/api/jobs", files={"file": ("f.png", png_of(fx.image), "image/png")}, data=fixture_form(**modes))
    assert r.status_code == status, r.text
    if status == 400:
        assert "Mode C" in r.json()["detail"]
    b = client.post("/lab/api/batches", files=[("files", ("f.png", png_of(fx.image), "image/png"))], data=fixture_form(**modes))
    assert b.status_code == status


def test_the_mode_c_field_may_be_left_out_like_before(client):
    fx = mock.make_fixture()
    r = client.post("/lab/api/jobs", files={"file": ("f.png", png_of(fx.image), "image/png")}, data={"target_lang": "en", "mode_a": "1", "mode_b": "0"})
    assert r.status_code == 200
    assert wait_done(client, r.json()["job_id"])["mode_c"]["status"] == "skipped"


def test_a_batch_with_mode_c_has_a_summary_row_for_it_and_a_schema_4_manifest(client):
    publish.mock_client().objects.clear()
    fx = mock.make_fixture()
    files = [("files", (f"menu{i}.png", png_of(fx.image), "image/png")) for i in (1, 2)]
    r = client.post("/lab/api/batches", files=files, data=fixture_form(mode_a="1", mode_b="1", image_models="mock/image-model"))
    assert r.status_code == 200, r.text
    doc = wait_batch(client, r.json()["batch_id"])
    rows = doc["summary"]
    assert [(row["image_index"], row["mode"]) for row in rows] == [(1, "A"), (1, "C"), (1, "B"), (2, "A"), (2, "C"), (2, "B")]
    c_row = rows[1]
    assert c_row["label"] == "Mode C · claude-opus-5-5" and c_row["status"] == "done" and c_row["verdict"] == "pass"
    assert c_row["usability"] == 1.0 and c_row["output"] == "C_a1.png" and c_row["cost_openrouter_usd"] == 0.0 and c_row["cost_anthropic_usd"] > 0
    assert c_row["runs_done"] == 1 and c_row["passes"] == 1 and isinstance(c_row["elapsed_ms"], int)
    assert doc["totals"]["rows"] == 6 and doc["totals"]["rows_passed"] == 6
    manifest = json.loads((client.app.state.store.batch_dir(r.json()["batch_id"]) / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["schema_version"] == 4 == publish.SCHEMA_VERSION and manifest["options"]["mode_c"] is True
    assert {(row["image_index"], row["mode"]) for row in manifest["summary"]} >= {(1, "C"), (2, "C")}
    assert manifest["options"]["translate_model"] == "claude-opus-5-5" and manifest["options"]["inpaint_model"] == "telea"
    job_id = r.json()["job_ids"][0]
    job_manifest = json.loads((client.app.state.store.batch_dir(r.json()["batch_id"]).parent / job_id / "manifest.json").read_text(encoding="utf-8"))
    mc = job_manifest["mode_c"]
    assert job_manifest["schema_version"] == 4 and job_manifest["options"]["mode_c"] is True
    assert mc["status"] == "done" and mc["image"]["name"] == "C_a1.png" and mc["units"]["name"] == "C_a1_units.json"
    assert mc["verdict"] == "pass" and mc["translated_count"] == 3 and mc["segment"]["units"] == 4 and "reasons" not in mc["segment"]
    assert mc["segment_ms"] is not None and mc["claude_calls"] == 1 and mc["attempts"][0]["erased"]["name"] == "C_a1_erased.png"
    assert {"C_a1.png", "C_a1_erased.png", "C_a1_units.json", "C_a1_verify.json"} <= {o["name"] for o in job_manifest["objects"]}
    assert job_manifest["mode_a"]["status"] == "done" and len(job_manifest["mode_b"]) == 1


def test_a_job_that_was_stored_before_round_8_has_no_mode_c_and_still_summarises():
    old = {"job_id": "x", "index": 1, "params": {"target_lang": "en", "claude_model": "claude-opus-5-5"}, "image": {"name": "a.png"},
           "mode_a": {"status": "done", "verdict": "pass", "usability": 1.0}, "mode_b": {"results": []}}  # fmt: skip
    assert [r["mode"] for r in batch.summarise_batch([old])] == ["A"]


def test_the_manifest_of_a_job_without_mode_c_has_mode_c_null(client):
    job = wait_done(client, post_job(client, mode_a="1", mode_c="0"))
    manifest = json.loads((client.app.state.store._dirs[job["job_id"]] / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["mode_c"] is None and manifest["options"]["mode_c"] is False and manifest["mode_a"]["status"] == "done"


def test_the_page_has_the_checkbox_the_column_and_the_form_field(client):
    html = client.get("/lab/").text
    assert 'id="mode-c"' in html and "Mode C（幾何版面 + 純文字翻譯" in html
    assert "fd.append('mode_c'" in html and "renderOverlay(job, cols('c-c'), 'mode_c')" in html and "col('c-c')" in html
    assert "claude-opus-5-5" in html  # the translate model is shown next to the checkbox
    assert "r.mode !== 'B' ? '—'" in html  # the summary table: only Mode B costs OpenRouter money


def test_the_mode_c_units_table_shows_the_layout_reasons_and_the_eraser():
    html = (Path(pipeline_a.__file__).parent / "templates" / "index.html").read_text(encoding="utf-8")
    for needle in ("版面（極性、行、理由）", "擦字器 / 殘留", "殘留而還原", "segment（版面，不呼叫模型）", "Claude 翻譯（純文字）", "segment.reasons"):
        assert needle in html, needle


def test_the_mock_words_have_plausible_paragraphs_blocks_and_breaks():
    words = mock.vision_words(640, 480).words
    assert [(w.text, w.para, w.block, w.brk) for w in words] == [
        ("TONKATSU", 0, 0, "SPACE"), ("SET", 0, 0, "SPACE"), ("MENU", 0, 0, "LINE_BREAK"), ("NT$120", 1, 1, "LINE_BREAK"),
        ("紅燒肉飯店", 2, 2, "LINE_BREAK"), ("LUNCH", 3, 3, "SPACE"), ("SPECIAL", 3, 3, "LINE_BREAK"),
    ]  # fmt: skip
    scaled = mock.vision_words(1280, 960).words  # the canned words are scaled to the upload, the hierarchy stays
    assert [(w.para, w.block, w.brk) for w in scaled] == [(w.para, w.block, w.brk) for w in words]


def test_the_geometry_of_the_mock_page_gives_the_units_the_canned_planner_gave_mode_a(tmp_path, monkeypatch):
    image, ocr, c = mock_run(tmp_path, monkeypatch)
    by = {u["source_text"]: u for u in c["units"]}
    assert set(by) == {"TONKATSU SET MENU", "NT$120", "LUNCH SPECIAL", "紅燒肉飯店"}  # the four units of the mock planner, in other words
    assert (by["NT$120"]["action"], by["NT$120"]["keep_kind"]) == ("kept", "price")
    assert by["紅燒肉飯店"]["direction"] == "vertical_rl" and by["LUNCH SPECIAL"]["rotated"] is True
    assert by["TONKATSU SET MENU"]["direction"] == "horizontal" and by["TONKATSU SET MENU"]["rotated"] is False
