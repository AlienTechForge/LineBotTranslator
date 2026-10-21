"""Erasing the glyphs: OpenCV Telea, or a trained inpainter (LaMa, ONNX Runtime) with Telea as the fallback.

    inpaint(rgb, mask, *, model) -> rgb           # model: "telea" | "lama"
    inpaint_report(rgb, mask, *, model) -> InpaintResult   # the same, plus what each cluster used

`telea` is the call the lab always made (one `cv2.inpaint` over the whole mask). `lama` works on crops,
not on the whole picture:

* the mask is split into clusters: the connected pieces of the mask, each padded by INPAINT_MARGIN_PX,
  merge when their padded boxes touch (a menu of 100 lines becomes a handful of clusters, a lone price
  tag in a corner is one of its own);
* a cluster is cropped with that margin, so LaMa sees the paper around the text. The model decides the
  size it is fed: an export with a fixed input (the recommended Carve export is 512 x 512) gets its exact
  size, taken from the picture around the cluster (a smaller cluster is given more of its surroundings, a
  larger one is scaled down to fit); an export with a dynamic input gets the crop padded to a multiple of 8
  and scaled down when its long side exceeds INPAINT_MAX_SIDE. The result is scaled back;
* only the pixels under the (feathered) mask of the cluster are taken from the model, everything else
  keeps the original pixels, so a scaled round trip never softens the rest of the picture.

When `onnxruntime` is not installed, the model file is missing and cannot be downloaded, or one inference
fails, the affected clusters are erased by Telea and the result says so (`ClusterReport.note`,
`lama unavailable: <class name>`), which `erase.py` copies into the unit report. The model is never part
of the repository: it is downloaded on first use (modelfetch.py) to INPAINT_MODEL_PATH, or baked into the
Docker image at build time. Nothing here logs pixels or text.
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass, field

import cv2
import numpy as np

from . import modelfetch
from .config import get_settings

log = logging.getLogger("lab.inpaint")

TELEA_RADIUS = 3  # OpenCV's inpainting radius
MODEL_TELEA = "telea"
MODEL_LAMA = "lama"

INPAINT_MARGIN_PX = 32  # the surroundings a cluster is cropped with; padded boxes that touch form one cluster
INPAINT_MAX_SIDE = 1024  # a crop whose long side exceeds this is scaled down before the model (dynamic exports)
PAD_MULTIPLE = 8  # a dynamic crop is padded to a multiple of this
FEATHER_SIGMA = 1.5  # px; the model's pixels fade into the original over about three pixels
FEATHER_CUTOFF = 0.01  # a feathered alpha below this is 0: pixels farther than ~4 px from the mask are never touched
RETRY_AFTER_S = 300.0  # a model that could not be loaded is not tried again for this long (no download per job)
MAX_CONCURRENT_INFERENCES = 2  # LaMa runs in flight at once, process wide (every run already uses all cores)

_INFERENCE_SLOTS = threading.BoundedSemaphore(MAX_CONCURRENT_INFERENCES)


@dataclass
class ClusterReport:
    box: tuple[int, int, int, int]  # x0, y0, x1, y1 of the cluster's crop in the picture (margin included)
    engine: str  # "telea" | "lama"
    note: str = ""  # why Telea ran although lama was asked for: "lama unavailable: ModelDownloadError"
    scale: float = 1.0  # how much the crop was scaled before the model (1.0 = not at all)
    input_size: tuple[int, int] | None = None  # (width, height) the model was fed
    window: tuple[int, int, int, int] | None = None  # the picture area the model saw (a fixed-size export gets more than the crop)
    ms: int = 0

    @property
    def label(self) -> str:
        """What the unit report says: `lama`, `telea`, or `telea (lama unavailable: <class name>)`."""
        return f"{self.engine} ({self.note})" if self.note else self.engine


@dataclass
class InpaintResult:
    image: np.ndarray
    model: str  # what was asked for
    clusters: list[ClusterReport] = field(default_factory=list)

    def label_at(self, box: tuple[int, int, int, int]) -> str:
        """The label of the first cluster whose crop contains the centre of `box` (x0, y0, x1, y1)."""
        cx, cy = (box[0] + box[2]) / 2.0, (box[1] + box[3]) / 2.0
        for cluster in self.clusters:
            x0, y0, x1, y1 = cluster.box
            if x0 <= cx < x1 and y0 <= cy < y1:
                return cluster.label
        return self.clusters[0].label if self.clusters else self.model


# ---------------------------------------------------------------- Telea


def telea(rgb: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """One Telea inpaint over the whole picture for the given mask (0 / 255 or 0 / 1, uint8)."""
    if not mask.any():
        return rgb.copy()
    return cv2.inpaint(rgb, (mask > 0).astype(np.uint8) * 255, TELEA_RADIUS, cv2.INPAINT_TELEA)


# ---------------------------------------------------------------- the model (loaded once, lazily)


@dataclass
class _Failure:
    key: tuple
    error: BaseException
    at: float


_LOCK = threading.Lock()
_SESSION = None
_SESSION_KEY: tuple | None = None
_FAILURE: _Failure | None = None


def reset() -> None:
    """Forget the loaded model and any remembered failure (tests, and a changed INPAINT_MODEL_PATH)."""
    global _SESSION, _SESSION_KEY, _FAILURE
    with _LOCK:
        _SESSION, _SESSION_KEY, _FAILURE = None, None, None


def get_session():
    """The ONNX Runtime session of the LaMa model; downloads the file first when it is not there.

    Imports `onnxruntime` lazily (the package is optional: requirements-inpaint.txt). Raises whatever went
    wrong (ModuleNotFoundError, modelfetch.ModelDownloadError, an ONNX Runtime error); a failure is remembered
    for RETRY_AFTER_S so that a missing model does not cost a download attempt per job.
    """
    global _SESSION, _SESSION_KEY, _FAILURE
    settings = get_settings()
    key = (settings.inpaint_model_path, settings.inpaint_model_url, settings.inpaint_model_sha256)
    with _LOCK:
        if _SESSION is not None and _SESSION_KEY == key:
            return _SESSION
        if _FAILURE is not None and _FAILURE.key == key and time.monotonic() - _FAILURE.at < RETRY_AFTER_S:
            raise _FAILURE.error
        try:
            import onnxruntime as ort  # noqa: PLC0415 - optional dependency

            path = modelfetch.ensure(settings.inpaint_model_url, settings.inpaint_model_path, settings.inpaint_model_sha256)
            options = ort.SessionOptions()
            options.log_severity_level = 3
            session = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
        except Exception as exc:  # noqa: BLE001 - the caller falls back to Telea and names the class
            _FAILURE = _Failure(key, exc, time.monotonic())
            log.warning("stage=inpaint lama unavailable: %s", type(exc).__name__)
            raise
        _SESSION, _SESSION_KEY, _FAILURE = session, key, None
        return session


def _input_spec(session) -> tuple[str, str, tuple[int, int] | None]:
    """(image input name, mask input name, (height, width) when the model has a fixed input size else None)."""
    inputs = list(session.get_inputs())
    if len(inputs) < 2:
        raise ValueError("the inpainting model needs an image input and a mask input")
    by_name = {item.name.lower(): item for item in inputs}
    mask_item = next((item for name, item in by_name.items() if "mask" in name), inputs[1])
    image_item = next((item for item in inputs if item is not mask_item), inputs[0])
    shape = list(getattr(image_item, "shape", None) or [])
    fixed = None
    if len(shape) == 4 and all(isinstance(v, int) and v > 0 for v in shape[2:]):
        fixed = (int(shape[2]), int(shape[3]))
    return image_item.name, mask_item.name, fixed


# ---------------------------------------------------------------- clusters


def find_clusters(mask: np.ndarray, margin: int = INPAINT_MARGIN_PX) -> tuple[np.ndarray, list[tuple[int, int, int, int]]]:
    """(labels, boxes): the clusters of the mask.

    `labels` is an int array of the mask's size (0 = outside every cluster, k = cluster k, 1-based) over the
    pixels of the mask padded by `margin` on every side; `boxes[k - 1]` is the crop (x0, y0, x1, y1) of cluster
    k, margin included and clipped to the picture. Padded boxes that touch are one cluster.
    """
    solid = (mask > 0).astype(np.uint8)
    grown = cv2.dilate(solid, np.ones((2 * margin + 1, 2 * margin + 1), np.uint8))
    count, labels, stats, _ = cv2.connectedComponentsWithStats(grown, connectivity=8)
    boxes = [(int(x), int(y), int(x + w), int(y + h)) for x, y, w, h, _ in stats[1:count]]
    return labels, boxes


def _feather(own: np.ndarray) -> np.ndarray:
    """Alpha for pasting the model's pixels: 1 on the mask, fading to 0 over the next few pixels."""
    solid = own.astype(np.float32)
    soft = cv2.GaussianBlur(solid, (0, 0), FEATHER_SIGMA)
    alpha = np.maximum(solid, soft)
    alpha[alpha < FEATHER_CUTOFF] = 0.0
    return alpha


def _grow_window(box, size: tuple[int, int], image_wh: tuple[int, int]) -> tuple[int, int, int, int]:
    """`box` grown to `size` = (height, width) around its centre, kept inside the picture (smaller when the picture is)."""
    img_w, img_h = image_wh
    out = []
    for lo, hi, want, limit in ((box[0], box[2], size[1], img_w), (box[1], box[3], size[0], img_h)):
        want = min(want, limit)
        start = int(round((lo + hi) / 2.0 - want / 2.0))
        start = max(0, min(start, limit - want))
        out.extend([start, start + want])
    return out[0], out[2], out[1], out[3]


def _run_model(session, names: tuple[str, str], image: np.ndarray, hole: np.ndarray) -> np.ndarray:
    """One inference on an already sized crop: (h, w, 3) uint8 and (h, w) bool in, (h, w, 3) uint8 out."""
    x = np.ascontiguousarray(image.astype(np.float32).transpose(2, 0, 1)[None] / 255.0)
    m = np.ascontiguousarray(hole.astype(np.float32)[None, None])
    with _INFERENCE_SLOTS:
        out = session.run(None, {names[0]: x, names[1]: m})[0]
    out = np.asarray(out)
    if out.ndim != 4 or out.shape[0] != 1 or out.shape[1] != 3 or out.shape[2:] != image.shape[:2]:
        raise ValueError("the inpainting model returned an unexpected shape")
    pixels = out[0].transpose(1, 2, 0)
    if float(pixels.max()) <= 1.5:  # an export that answers in 0..1 instead of 0..255
        pixels = pixels * 255.0
    return np.clip(np.rint(pixels), 0, 255).astype(np.uint8)


def _pad_to(arr: np.ndarray, height: int, width: int, reflect: bool) -> np.ndarray:
    pad_h, pad_w = height - arr.shape[0], width - arr.shape[1]
    if pad_h <= 0 and pad_w <= 0:
        return arr
    border = cv2.BORDER_REFLECT_101 if reflect else cv2.BORDER_CONSTANT
    return cv2.copyMakeBorder(arr, 0, max(0, pad_h), 0, max(0, pad_w), border, value=0)


def _lama_cluster(session, spec, rgb: np.ndarray, mask: np.ndarray, labels: np.ndarray, k: int, box) -> tuple:
    """Run the model on cluster k; returns (window box, generated pixels at window size, alpha, ClusterReport)."""
    t0 = time.perf_counter()
    img_h, img_w = rgb.shape[:2]
    image_name, mask_name, fixed = spec
    window = tuple(int(v) for v in box)
    if fixed is not None and (window[2] - window[0]) <= fixed[1] and (window[3] - window[1]) <= fixed[0]:
        window = _grow_window(window, fixed, (img_w, img_h))  # the real picture around a small cluster, not padding
    x0, y0, x1, y1 = window
    crop = rgb[y0:y1, x0:x1]
    hole = mask[y0:y1, x0:x1] > 0  # everything masked in view: the model never sees ink that is about to go
    own = hole & (labels[y0:y1, x0:x1] == k)
    h, w = crop.shape[:2]
    if fixed is not None:
        limit_h, limit_w = fixed
        scale = min(1.0, limit_h / h, limit_w / w)
    else:
        scale = min(1.0, INPAINT_MAX_SIDE / max(h, w))
    small, small_hole = crop, hole
    if scale < 1.0:
        nw, nh = max(1, int(round(w * scale))), max(1, int(round(h * scale)))
        small = cv2.resize(crop, (nw, nh), interpolation=cv2.INTER_AREA)
        small_hole = cv2.resize(hole.astype(np.uint8) * 255, (nw, nh), interpolation=cv2.INTER_AREA) > 0
    sh, sw = small.shape[:2]
    if fixed is not None:
        target_h, target_w = fixed
    else:
        target_h, target_w = -(-sh // PAD_MULTIPLE) * PAD_MULTIPLE, -(-sw // PAD_MULTIPLE) * PAD_MULTIPLE
    padded = _pad_to(small, target_h, target_w, reflect=True)
    padded_hole = _pad_to(small_hole.astype(np.uint8), target_h, target_w, reflect=False) > 0
    generated = _run_model(session, (image_name, mask_name), padded, padded_hole)[:sh, :sw]
    if scale < 1.0:
        generated = cv2.resize(generated, (w, h), interpolation=cv2.INTER_CUBIC)
    report = ClusterReport(
        tuple(int(v) for v in box), MODEL_LAMA, scale=round(scale, 4), input_size=(target_w, target_h), window=window,
        ms=int((time.perf_counter() - t0) * 1000),
    )  # fmt: skip
    return window, generated, _feather(own), report


def effective_model(model: str | None = None) -> str:
    """The inpainter that will run: `model` when given, else INPAINT_MODEL, except that LAB_MOCK=1 is always Telea.

    Mock mode is fully offline (nothing is imported, downloaded or run beyond OpenCV), so a lab compose that
    defaults to INPAINT_MODEL=lama does not make a mock run download a 200 MB model.
    """
    if model:
        return model.lower()
    settings = get_settings()
    return MODEL_TELEA if settings.mock else (settings.inpaint_model or MODEL_TELEA).lower()


def inpaint_report(rgb: np.ndarray, mask: np.ndarray, *, model: str | None = None) -> InpaintResult:
    """Erase the masked pixels of `rgb` (uint8 RGB) with `model` (default: `effective_model()`) and say what ran."""
    model = effective_model(model)
    if not (mask > 0).any():
        return InpaintResult(rgb.copy(), model, [])
    full_box = _bbox(mask)
    if model != MODEL_LAMA:
        return InpaintResult(telea(rgb, mask), model, [ClusterReport(full_box, MODEL_TELEA)])

    t0 = time.perf_counter()
    labels, boxes = find_clusters(mask)
    out = rgb.copy()
    reports: list[ClusterReport] = []
    failed: list[int] = []
    session = spec = None
    why = ""
    try:
        session = get_session()
        spec = _input_spec(session)
    except Exception as exc:  # noqa: BLE001 - every cluster falls back to Telea
        why = f"lama unavailable: {type(exc).__name__}"
    for k, box in enumerate(boxes, start=1):
        if session is not None:
            try:
                window, generated, alpha, report = _lama_cluster(session, spec, rgb, mask, labels, k, box)
                wx0, wy0, wx1, wy1 = window
                base = out[wy0:wy1, wx0:wx1].astype(np.float32)
                blended = base * (1.0 - alpha[..., None]) + generated.astype(np.float32) * alpha[..., None]
                out[wy0:wy1, wx0:wx1] = np.clip(np.rint(blended), 0, 255).astype(np.uint8)
                reports.append(report)
                continue
            except Exception as exc:  # noqa: BLE001 - this cluster only
                note = f"lama unavailable: {type(exc).__name__}"
                log.warning("stage=inpaint cluster failed: %s", type(exc).__name__)
        else:
            note = why
        failed.append(k)
        reports.append(ClusterReport(tuple(int(v) for v in box), MODEL_TELEA, note=note))
    if failed:
        own = np.isin(labels, failed) & (mask > 0)
        fallback = telea(rgb, own.astype(np.uint8) * 255)
        out[own] = fallback[own]
    log.info(
        "stage=inpaint model=lama clusters=%d lama=%d telea=%d ms=%d",
        len(boxes), len(boxes) - len(failed), len(failed), int((time.perf_counter() - t0) * 1000),
    )  # fmt: skip
    return InpaintResult(out, model, reports)


def inpaint(rgb: np.ndarray, mask: np.ndarray, *, model: str | None = None) -> np.ndarray:
    """`rgb` with the masked pixels erased; `model` is "telea" or "lama" (default: `effective_model()`)."""
    return inpaint_report(rgb, mask, model=model).image


def _bbox(mask: np.ndarray) -> tuple[int, int, int, int]:
    ys, xs = np.nonzero(mask > 0)
    return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1
