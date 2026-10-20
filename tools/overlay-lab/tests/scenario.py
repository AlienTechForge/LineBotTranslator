"""Scripted Mode B runs for the jobs / publish tests (no network, no Vision, no OpenRouter)."""

from __future__ import annotations

import io
import threading
import time

from PIL import Image

from app.models import OcrResult, Word

from helpers import rect


def png_of_size(size=(80, 60), color=(250, 245, 230)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", size, color).save(buf, "PNG")
    return buf.getvalue()


def ocr(*words, text: str | None = None, size=(80, 60)) -> OcrResult:
    """A scripted Vision result: words are 'text' or ('text', 'lang'); `text` defaults to the words joined."""
    items = []
    for i, w in enumerate(words):
        t, lang = (w, None) if isinstance(w, str) else w
        items.append(Word(id=f"w{i + 1:04d}", text=t, polygon=rect(0, 0, 10, 10), confidence=0.9, para=0, lang=lang))
    joined = text if text is not None else " ".join(i.text for i in items)
    return OcrResult(width=size[0], height=size[1], words=items, text=joined)


class Scenario:
    """Stand-ins for openrouter.run_image_model and vision.ocr_words, scripted per model.

    `plan` maps a model id to its steps, one per generation call: a dict with
    `ocr` (the OcrResult of that output, or an Exception that Vision raises),
    `fail` (an Exception that the generation raises), `cost`, `ms`, `size`, `api_used`.
    """

    def __init__(self, source_bytes: bytes, source_ocr: OcrResult, plan: dict[str, list[dict]], barrier=None):
        self.source_bytes = source_bytes
        self.source_ocr = source_ocr
        self.plan = {m: list(steps) for m, steps in plan.items()}
        self.outputs: dict[bytes, object] = {}
        self.calls: list[dict] = []  # one per generation call
        self.ocr_calls: list[bytes] = []
        self.source_ocr_calls = 0
        self.barrier = barrier
        self._lock = threading.Lock()
        self._counter = 0
        self._started: set[str] = set()

    def run_image_model(self, image, lang, model, *, prompt=None, resolution=""):
        if self.barrier is not None and model not in self._started:
            self._started.add(model)
            self.barrier.wait(timeout=10)  # every model must be in flight at the same time
        with self._lock:
            step = self.plan[model].pop(0)
            self._counter += 1
            n = self._counter
            self.calls.append({"model": model, "prompt": prompt, "resolution": resolution, "lang": lang})
        if step.get("fail") is not None:
            raise step["fail"]
        size = tuple(step.get("size", (image.width, image.height)))
        png = png_of_size(size, (n * 7 % 256, n * 13 % 256, n * 29 % 256))  # unique bytes per call
        with self._lock:
            self.outputs[png] = step["ocr"]
        cost = step.get("cost")
        usage = {"cost": cost, "prompt_tokens": 100, "completion_tokens": 200} if cost is not None else {}
        return {
            "png": png,
            "size": list(size),
            "usage": usage,
            "api_used": step.get("api_used", "images"),
            "attempts": [],
            "model_text": "",
            "elapsed_ms": step.get("ms", 1000),
            "route_preference": "images",
            "resolution_requested": resolution,
            "resolution_applied": bool(resolution),
            "prompt": prompt or f"default prompt for {lang}",
        }

    def ocr_words(self, image_bytes, width, height):
        with self._lock:
            if image_bytes == self.source_bytes:
                self.source_ocr_calls += 1
                return self.source_ocr
            self.ocr_calls.append(image_bytes)
            result = self.outputs[image_bytes]
        if isinstance(result, Exception):
            raise result
        return result

    def install(self, monkeypatch):
        from app import openrouter, vision

        monkeypatch.setattr(openrouter, "run_image_model", self.run_image_model)
        monkeypatch.setattr(vision, "ocr_words", self.ocr_words)
        return self


def wait_for(client, job_id: str, *, published: bool = False, timeout: float = 30.0) -> dict:
    """Poll /api/jobs/<id> until the job is done (and, with published=True, the upload has settled)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        job = client.get(f"/api/jobs/{job_id}").json()
        if job["status"] == "done" and (not published or job["publish"]["status"] in ("done", "skipped", "error")):
            return job
        time.sleep(0.03)
    raise AssertionError("job did not finish: " + str(client.get(f"/api/jobs/{job_id}").json())[:400])
