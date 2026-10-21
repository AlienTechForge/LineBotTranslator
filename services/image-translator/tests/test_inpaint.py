"""The inpainter: Telea, the LaMa path on crops (with a fake ONNX session), the fallback, the model download."""

import hashlib
import http.server
import sys
import threading
import types
import urllib.request

import cv2
import numpy as np
import pytest

from app import inpaint, modelfetch
from app.config import get_settings

PAPER = (236, 228, 208)
FILL = (200, 10, 30)  # what the fake model paints into every hole


class Meta:
    def __init__(self, name, shape):
        self.name, self.shape = name, shape


class FakeSession:
    """Stands in for an ONNX Runtime session: records what it was fed and answers with a flat colour (0..255)."""

    def __init__(self, fixed=None, fail_on=(), answer_01=False):
        shape = ["batch", 3, *(fixed or ("height", "width"))]
        self.inputs = [Meta("image", shape), Meta("mask", ["batch", 1, shape[2], shape[3]])]
        self.fail_on, self.answer_01, self.runs, self.feeds = set(fail_on), answer_01, [], []

    def get_inputs(self):
        return self.inputs

    def run(self, outputs, feed):
        n = len(self.runs)
        self.runs.append((feed["image"].shape, feed["mask"].shape, float(feed["mask"].sum())))
        self.feeds.append(feed)
        if n in self.fail_on:
            raise RuntimeError("inference failed")
        out = np.zeros(feed["image"].shape, np.float32)
        for c, v in enumerate(FILL):
            out[0, c] = v / 255.0 if self.answer_01 else v
        return [out]


def paper(h=300, w=400):
    img = np.full((h, w, 3), PAPER, np.uint8)
    img += (np.arange(w) % 3).astype(np.uint8)[None, :, None]  # a little structure, so that "unchanged" means something
    return img


def box_mask(shape, *boxes):
    mask = np.zeros(shape[:2], np.uint8)
    for x0, y0, x1, y1 in boxes:
        mask[y0:y1, x0:x1] = 255
    return mask


@pytest.fixture
def lama(monkeypatch):
    """Install a fake session as the LaMa model."""

    def install(**kw):
        session = FakeSession(**kw)
        monkeypatch.setattr(inpaint, "get_session", lambda: session)
        return session

    return install


def distance_to(mask, px):
    return cv2.distanceTransform((mask == 0).astype(np.uint8), cv2.DIST_L2, 3) >= px


# ---------------------------------------------------------------- telea


def test_telea_is_the_call_the_lab_always_made():
    img = paper()
    mask = box_mask(img.shape, (150, 100, 250, 140))
    result = inpaint.inpaint_report(img, mask, model="telea")
    assert np.array_equal(result.image, cv2.inpaint(img, mask, 3, cv2.INPAINT_TELEA))
    assert [(c.engine, c.note, c.label) for c in result.clusters] == [("telea", "", "telea")]
    assert np.array_equal(inpaint.inpaint(img, mask, model="telea"), result.image)
    assert np.array_equal(inpaint.inpaint(img, np.zeros_like(mask), model="lama"), img)  # nothing to erase: a copy
    assert inpaint.inpaint(img, np.zeros_like(mask), model="lama") is not img


def test_the_model_defaults_to_the_setting_and_the_setting_to_telea(monkeypatch):
    img, mask = paper(), box_mask((300, 400), (150, 100, 250, 140))
    assert get_settings().inpaint_model == "telea"
    assert inpaint.inpaint_report(img, mask).model == "telea"
    monkeypatch.setenv("INPAINT_MODEL", "nonsense")
    assert get_settings().inpaint_model == "telea"
    monkeypatch.setenv("INPAINT_MODEL", "LaMa")
    assert get_settings().inpaint_model == "lama"


# ---------------------------------------------------------------- lama on crops


def test_lama_works_on_a_crop_with_a_margin_padded_to_a_multiple_of_eight_and_pastes_under_the_mask_only(lama):
    session = lama()
    img = paper()
    mask = box_mask(img.shape, (150, 100, 250, 140))  # 100 x 40
    result = inpaint.inpaint_report(img, mask, model="lama")
    # the crop: the mask box plus 32 px on every side = 164 x 104, padded to 168 x 104
    assert session.runs == [((1, 3, 104, 168), (1, 1, 104, 168), 100.0 * 40.0)]
    assert [(c.engine, c.box, c.scale, c.input_size) for c in result.clusters] == [("lama", (118, 68, 282, 172), 1.0, (168, 104))]
    out = result.image
    assert (out[100:140, 150:250] == FILL).all()  # under the mask: the model's pixels
    far = distance_to(mask, 6)
    assert np.array_equal(out[far], img[far])  # everywhere beyond the feather: the original, untouched
    ring = ~far & (mask == 0)
    assert ring.any() and not np.array_equal(out[ring], img[ring])  # the feather blends the first few pixels


def test_the_image_goes_in_as_float_zero_to_one_and_the_mask_as_zero_or_one(lama):
    session = lama()
    img = paper()
    inpaint.inpaint(img, box_mask(img.shape, (150, 100, 250, 140)), model="lama")
    feed = session.feeds[0]
    assert feed["image"].dtype == np.float32 and 0.0 <= float(feed["image"].min()) and float(feed["image"].max()) <= 1.0
    assert set(np.unique(feed["mask"])) == {0.0, 1.0}
    assert feed["image"][0, :, 40, 40] == pytest.approx(np.array(img[68 + 40, 118 + 40]) / 255.0)  # the crop starts at (118, 68)


def test_a_model_that_answers_between_zero_and_one_is_understood(lama):
    lama(answer_01=True)
    img = paper()
    out = inpaint.inpaint(img, box_mask(img.shape, (150, 100, 250, 140)), model="lama")
    assert (out[100:140, 150:250] == FILL).all()


def test_a_crop_longer_than_the_maximum_side_is_scaled_down_and_back(lama, monkeypatch):
    monkeypatch.setattr(inpaint, "INPAINT_MAX_SIDE", 64)
    session = lama()
    img = paper()
    mask = box_mask(img.shape, (150, 100, 250, 140))
    result = inpaint.inpaint_report(img, mask, model="lama")
    # 164 x 104 -> scale 64 / 164: 64 x 41, padded to 64 x 48
    assert session.runs[0][0] == (1, 3, 48, 64)
    (cluster,) = result.clusters
    assert cluster.scale == pytest.approx(64 / 164, abs=1e-3) and cluster.input_size == (64, 48)
    out = result.image
    assert np.abs(out[100:140, 150:250].astype(int) - np.array(FILL)).max() <= 1  # scaled back up under the mask
    far = distance_to(mask, 6)
    assert np.array_equal(out[far], img[far])  # ... and nothing else moved


def test_a_crop_is_never_scaled_up(lama):
    session = lama()
    img = paper(60, 90)
    inpaint.inpaint(img, box_mask(img.shape, (30, 20, 50, 30)), model="lama")  # the crop is 82 x 60, cut by the picture's edges
    assert session.runs[0][0] == (1, 3, 64, 88)  # 60 -> 64 and 82 -> 88: padded, not scaled


def test_a_model_with_a_fixed_input_size_is_given_exactly_that_size(lama):
    session = lama(fixed=(256, 256))
    img = paper(300, 400)
    mask = box_mask(img.shape, (150, 100, 250, 140))
    result = inpaint.inpaint_report(img, mask, model="lama")
    assert session.runs[0][:2] == ((1, 3, 256, 256), (1, 1, 256, 256))
    (cluster,) = result.clusters
    assert cluster.box == (118, 68, 282, 172) and cluster.scale == 1.0
    assert cluster.window == (72, 0, 328, 256)  # the cluster is centred in 256 x 256 of the picture itself, not padded
    out = result.image
    assert (out[100:140, 150:250] == FILL).all() and np.array_equal(out[distance_to(mask, 6)], img[distance_to(mask, 6)])


def test_a_cluster_larger_than_a_fixed_input_is_scaled_down_to_fit_and_padded(lama):
    session = lama(fixed=(128, 128))
    img = paper(400, 600)
    mask = box_mask(img.shape, (100, 100, 460, 160))  # 360 x 60 + margins: 424 x 124
    result = inpaint.inpaint_report(img, mask, model="lama")
    assert session.runs[0][0] == (1, 3, 128, 128)
    assert result.clusters[0].scale == pytest.approx(128 / 424, abs=1e-3)
    assert np.abs(result.image[100:160, 100:460].astype(int) - np.array(FILL)).max() <= 1


def test_a_picture_smaller_than_a_fixed_input_is_padded_to_it(lama):
    session = lama(fixed=(128, 128))
    img = paper(70, 100)
    mask = box_mask(img.shape, (30, 20, 60, 40))
    out = inpaint.inpaint(img, mask, model="lama")
    assert session.runs[0][0] == (1, 3, 128, 128) and (out[20:40, 30:60] == FILL).all()


def test_masks_that_touch_once_padded_share_a_cluster_and_far_ones_do_not(lama):
    session = lama()
    img = paper(300, 700)
    mask = box_mask(img.shape, (50, 50, 120, 80), (170, 50, 240, 80), (600, 200, 660, 230))  # 50 px apart (< 2 x 32), then far away
    result = inpaint.inpaint_report(img, mask, model="lama")
    assert len(session.runs) == 2 and [c.engine for c in result.clusters] == ["lama", "lama"]
    near, far = result.clusters
    assert near.box[:2] == (18, 18) and near.box[2] == 272 and far.box[0] == 568
    assert (result.image[50:80, 50:120] == FILL).all() and (result.image[200:230, 600:660] == FILL).all()
    labels, boxes = inpaint.find_clusters(mask)
    assert len(boxes) == 2 and set(np.unique(labels)) == {0, 1, 2}
    assert inpaint.find_clusters(box_mask(img.shape, (50, 50, 120, 80), (400, 50, 470, 80)))[1].__len__() == 2  # 280 px apart


def test_the_model_never_sees_the_ink_of_a_neighbour_that_is_about_to_be_erased(lama):
    session = lama()
    img = paper(300, 700)
    mask = box_mask(img.shape, (50, 50, 120, 80), (170, 50, 240, 80))
    inpaint.inpaint(img, mask, model="lama")
    assert session.runs[0][2] == 2 * 70.0 * 30.0  # both masks are holes in the one crop


# ---------------------------------------------------------------- the fallback


def test_without_the_package_or_the_model_telea_runs_and_the_label_says_why(monkeypatch):
    img = paper()
    mask = box_mask(img.shape, (150, 100, 250, 140))

    def missing():
        raise ModuleNotFoundError("No module named 'onnxruntime'")

    monkeypatch.setattr(inpaint, "get_session", missing)
    result = inpaint.inpaint_report(img, mask, model="lama")
    assert np.array_equal(result.image, cv2.inpaint(img, mask, 3, cv2.INPAINT_TELEA))
    assert [c.label for c in result.clusters] == ["telea (lama unavailable: ModuleNotFoundError)"]
    assert result.label_at((150, 100, 250, 140)) == "telea (lama unavailable: ModuleNotFoundError)"


def test_a_failing_inference_falls_back_for_that_cluster_only(lama):
    session = lama(fail_on={1})  # the second cluster's run fails
    img = paper(300, 700)
    mask = box_mask(img.shape, (50, 50, 120, 80), (600, 200, 660, 230))
    result = inpaint.inpaint_report(img, mask, model="lama")
    assert [c.label for c in result.clusters] == ["lama", "telea (lama unavailable: RuntimeError)"]
    assert (result.image[50:80, 50:120] == FILL).all()  # the first cluster has the model's pixels ...
    telea = cv2.inpaint(img, box_mask(img.shape, (600, 200, 660, 230)), 3, cv2.INPAINT_TELEA)
    assert np.array_equal(result.image[200:230, 600:660], telea[200:230, 600:660])  # ... the second one Telea's
    assert result.label_at((50, 50, 120, 80)) == "lama" and result.label_at((600, 200, 660, 230)).startswith("telea")
    assert len(session.runs) == 2


def test_a_model_that_answers_with_the_wrong_shape_is_a_failure_not_a_crash(lama):
    session = lama()
    session.run = lambda outputs, feed: [np.zeros((1, 3, 8, 8), np.float32)]
    img = paper()
    result = inpaint.inpaint_report(img, box_mask(img.shape, (150, 100, 250, 140)), model="lama")
    assert result.clusters[0].label == "telea (lama unavailable: ValueError)"


# ---------------------------------------------------------------- loading the model


def fake_onnxruntime(monkeypatch, created):
    module = types.ModuleType("onnxruntime")

    class SessionOptions:
        log_severity_level = 2

    class InferenceSession:
        def __init__(self, path, options=None, providers=None):
            created.append((path, providers))

        def get_inputs(self):
            return FakeSession().get_inputs()

    module.SessionOptions, module.InferenceSession = SessionOptions, InferenceSession
    monkeypatch.setitem(sys.modules, "onnxruntime", module)


def test_the_session_is_loaded_once_from_the_downloaded_file_and_kept(monkeypatch, tmp_path):
    created, fetched = [], []
    fake_onnxruntime(monkeypatch, created)
    path = tmp_path / "models" / "lama_fp32.onnx"
    monkeypatch.setenv("INPAINT_MODEL_PATH", str(path))
    monkeypatch.setenv("INPAINT_MODEL_URL", "https://models.example/lama.onnx")
    monkeypatch.setenv("INPAINT_MODEL_SHA256", "ABCDEF")

    def ensure(url, dest, sha256="", **kw):
        fetched.append((url, str(dest), sha256))
        return path

    monkeypatch.setattr(modelfetch, "ensure", ensure)
    first = inpaint.get_session()
    assert inpaint.get_session() is first and len(created) == 1 and len(fetched) == 1
    assert fetched[0] == ("https://models.example/lama.onnx", str(path), "abcdef") and created[0] == (str(path), ["CPUExecutionProvider"])
    inpaint.reset()
    assert inpaint.get_session() is not first and len(created) == 2


def test_the_default_path_is_under_the_data_directory_and_the_default_url_is_the_carve_export(monkeypatch, tmp_path):
    monkeypatch.setenv("LAB_DATA_DIR", str(tmp_path))
    settings = get_settings()
    assert settings.inpaint_model_path == str(tmp_path / "models" / "lama_fp32.onnx")
    assert settings.inpaint_model_url == "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx"
    assert settings.inpaint_model_sha256 == ""


def test_a_missing_package_is_remembered_so_that_no_download_is_tried_for_every_job(monkeypatch, tmp_path):
    monkeypatch.setitem(sys.modules, "onnxruntime", None)  # `import onnxruntime` raises ImportError
    monkeypatch.setenv("INPAINT_MODEL_PATH", str(tmp_path / "m.onnx"))
    clock = [1000.0]
    monkeypatch.setattr(inpaint.time, "monotonic", lambda: clock[0])
    with pytest.raises(ImportError) as first:
        inpaint.get_session()
    with pytest.raises(ImportError) as again:
        inpaint.get_session()
    assert again.value is first.value  # the remembered failure, raised again
    clock[0] += inpaint.RETRY_AFTER_S + 1
    with pytest.raises(ImportError) as later:
        inpaint.get_session()
    assert later.value is not first.value  # tried again after the pause


def test_a_download_that_fails_is_remembered_and_names_its_class(monkeypatch, tmp_path):
    created = []
    fake_onnxruntime(monkeypatch, created)
    monkeypatch.setenv("INPAINT_MODEL_PATH", str(tmp_path / "m.onnx"))
    calls = []

    def ensure(*a, **k):
        calls.append(1)
        raise modelfetch.ModelDownloadError("no network")

    monkeypatch.setattr(modelfetch, "ensure", ensure)
    img = paper()
    mask = box_mask(img.shape, (150, 100, 250, 140))
    first = inpaint.inpaint_report(img, mask, model="lama")
    second = inpaint.inpaint_report(img, mask, model="lama")
    assert first.clusters[0].label == "telea (lama unavailable: ModelDownloadError)" == second.clusters[0].label
    assert len(calls) == 1 and not created


# ---------------------------------------------------------------- the download itself


class Response:
    def __init__(self, data, length=None):
        self.data, self.pos = data, 0
        self.headers = {} if length is False else {"Content-Length": str(len(data) if length is None else length)}

    def read(self, n=-1):
        chunk = self.data[self.pos : self.pos + (n if n > 0 else len(self.data))]
        self.pos += len(chunk)
        return chunk

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


MODEL = (b"onnx-model-bytes" * 70_000)[: 1_100_000]  # more than the 1 MB below which a download is an error page


def test_a_download_is_renamed_into_place_only_when_it_is_complete(tmp_path):
    dest = tmp_path / "models" / "lama.onnx"
    opened = []

    def opener(url, timeout):
        opened.append(url)
        return Response(MODEL)

    path = modelfetch.ensure("https://models.example/lama.onnx", dest, sha256=hashlib.sha256(MODEL).hexdigest().upper(), opener=opener)
    assert path == dest and dest.read_bytes() == MODEL and opened == ["https://models.example/lama.onnx"]
    assert [p.name for p in dest.parent.iterdir()] == ["lama.onnx"]  # no .part file left behind
    modelfetch.ensure("https://models.example/lama.onnx", dest, opener=lambda *a: pytest.fail("the file is there: no download"))


@pytest.mark.parametrize(
    "response, error",
    [
        (lambda: Response(MODEL, length=len(MODEL) + 5), "stopped after"),
        (lambda: Response(b"<html>error</html>"), "not a model file"),
        (lambda: Response(MODEL), "INPAINT_MODEL_SHA256"),
    ],
)
def test_a_broken_download_leaves_no_file_behind(tmp_path, response, error):
    dest = tmp_path / "lama.onnx"
    with pytest.raises(modelfetch.ModelDownloadError, match=error):
        modelfetch.ensure("https://models.example/m", dest, sha256="0" * 64, opener=lambda url, timeout: response())
    assert list(tmp_path.iterdir()) == []


def test_a_checksum_that_does_not_match_is_its_own_error(tmp_path):
    with pytest.raises(modelfetch.ChecksumMismatch):
        modelfetch.ensure("https://models.example/m", tmp_path / "m", sha256="1" * 64, opener=lambda u, t: Response(MODEL))
    modelfetch.ensure("https://models.example/m", tmp_path / "m", sha256="", opener=lambda u, t: Response(MODEL))  # none set: not checked


def test_a_network_error_becomes_a_download_error_and_other_schemes_are_refused(tmp_path):
    def boom(url, timeout):
        raise TimeoutError("slow")

    with pytest.raises(modelfetch.ModelDownloadError, match="TimeoutError"):
        modelfetch.ensure("https://models.example/m", tmp_path / "m", opener=boom)
    with pytest.raises(modelfetch.ModelDownloadError, match="https://"):
        modelfetch.ensure("file:///etc/passwd", tmp_path / "m", opener=lambda u, t: pytest.fail("never opened"))
    assert list(tmp_path.iterdir()) == []


def test_two_threads_asking_at_once_download_the_file_once(tmp_path):
    dest, count = tmp_path / "m.onnx", []

    def opener(url, timeout):
        count.append(1)
        return Response(MODEL)

    threads = [threading.Thread(target=modelfetch.ensure, args=("https://models.example/m", dest), kwargs={"opener": opener}) for _ in range(4)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    assert len(count) == 1 and dest.read_bytes() == MODEL


def test_the_default_opener_streams_from_a_real_http_server(tmp_path):
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Length", str(len(MODEL)))
            self.end_headers()
            self.wfile.write(MODEL)

        def log_message(self, *args):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        direct = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # a sandbox proxy must not see 127.0.0.1
        url = f"http://127.0.0.1:{server.server_port}/lama.onnx"
        path = modelfetch.ensure(url, tmp_path / "lama.onnx", hashlib.sha256(MODEL).hexdigest(), opener=lambda u, t: direct.open(u, timeout=t))
    finally:
        server.shutdown()
    assert path.read_bytes() == MODEL


def test_the_command_line_is_what_the_dockerfile_runs(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(modelfetch, "_urlopen", lambda url, timeout: Response(MODEL))
    dest = tmp_path / "lama.onnx"
    assert modelfetch.main(["modelfetch.py", "https://models.example/m", str(dest), hashlib.sha256(MODEL).hexdigest()]) == 0
    assert "model ready: lama.onnx" in capsys.readouterr().out
    assert modelfetch.main(["modelfetch.py"]) == 2
    dest.unlink()
    assert modelfetch.main(["modelfetch.py", "https://models.example/m", str(dest), "2" * 64]) == 1
    assert "failed" in capsys.readouterr().err and not dest.exists()


def test_mock_mode_is_always_telea_so_that_it_stays_offline_even_when_lama_is_the_default(monkeypatch):
    monkeypatch.setenv("LAB_MOCK", "1")
    monkeypatch.setenv("INPAINT_MODEL", "lama")
    monkeypatch.setattr(inpaint, "get_session", lambda: pytest.fail("mock mode must not load, let alone download, a model"))
    assert inpaint.effective_model() == "telea" and inpaint.effective_model("lama") == "lama"  # an explicit choice is honoured
    img = paper()
    mask = box_mask(img.shape, (150, 100, 250, 140))
    result = inpaint.inpaint_report(img, mask)
    assert result.model == "telea" and [c.label for c in result.clusters] == ["telea"]
    monkeypatch.delenv("LAB_MOCK")
    assert inpaint.effective_model() == "lama"
