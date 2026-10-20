"""Scripted Mode B runs for the jobs / publish tests (no network, no Vision, no OpenRouter, no Claude)."""

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
    """A scripted Vision result.

    Each word is 'text', ('text', 'lang'), ('text', 'lang', confidence) or
    ('text', 'lang', confidence, height); the confidence defaults to 0.9 (a hard digit run) and
    the box to 10 px tall (every word the same height, so no word is "big"). `text` defaults to
    the words joined.
    """
    items = []
    for i, w in enumerate(words):
        spec = [w] if isinstance(w, str) else list(w)
        t = spec[0]
        lang = spec[1] if len(spec) > 1 else None
        conf = spec[2] if len(spec) > 2 else 0.9
        height = spec[3] if len(spec) > 3 else 10.0
        # a wide box, so that its SHORT side (the height Vision-based checks use) is `height`
        polygon = rect(0, 0, 10, 10) if height == 10.0 else rect(0, 0, 500, height)
        items.append(Word(id=f"w{i + 1:04d}", text=t, polygon=polygon, confidence=conf, para=0, lang=lang))
    joined = text if text is not None else " ".join(i.text for i in items)
    return OcrResult(width=size[0], height=size[1], words=items, text=joined)


def judge_record(
    coverage: float = 1.0,
    garbled=(),
    untranslated=(),
    mistranslated=(),
    altered=(),
    *,
    status: str = "ok",
    error: str | None = None,
    cost: float | None = 0.01,
    reference=("reference line",),
) -> dict:
    """A judge.judge_output record. garbled items are (output_text, source_text) pairs."""
    if status != "ok":
        return {"status": status, "model": "claude-sonnet-5-5", "error": error, "elapsed_ms": 5}
    return {
        "status": "ok",
        "model": "claude-sonnet-5-5",
        "served_model": "claude-sonnet-5-5",
        "error": None,
        "coverage": coverage,
        "reference_translation": list(reference),
        "garbled_segments": [{"output_text": o, "source_text": s} for o, s in garbled],
        "untranslated_segments": list(untranslated),
        "mistranslated_segments": [{"output_text": o, "source_text": s, "problem": "wrong"} for o, s in mistranslated],
        "altered_numbers": [{"source": a, "output": b} for a, b in altered],
        "summary": "scripted judge",
        "input_tokens": 1000,
        "output_tokens": 300,
        "cost_usd": cost,
        "elapsed_ms": 7,
        "fallback_form": "default",
    }


class Scenario:
    """Stand-ins for openrouter.run_image_model, vision.ocr_words and judge.judge_output, scripted per model.

    `plan` maps a model id to its steps, one per generation call: a dict with
    `ocr` (the OcrResult of that output, or an Exception that Vision raises),
    `fail` (an Exception that the generation raises), `cost`, `ms`, `size`, `api_used`,
    `sleep` (seconds the generation really takes, to make concurrency measurable).
    `judge` maps the joined text of an output OCR to the judge record for it (a record from
    `judge_record`, or an Exception the judge "raises" - judge_output itself never does, so that
    is only for the failure-isolation tests); an output without an entry gets a clean judge.
    """

    def __init__(self, source_bytes: bytes, source_ocr: OcrResult, plan: dict[str, list[dict]], barrier=None, judge=None):
        self.source_bytes = source_bytes
        self.source_ocr = source_ocr
        self.plan = {m: list(steps) for m, steps in plan.items()}
        self.judge_plan = dict(judge or {})
        self.outputs: dict[bytes, object] = {}
        self.calls: list[dict] = []  # one per generation call
        self.ocr_calls: list[bytes] = []
        self.judge_calls: list[dict] = []
        self.source_ocr_calls = 0
        self.barrier = barrier
        self.active = 0  # generations in flight right now
        self.peak = 0  # the most generations that were ever in flight at once
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
            self.active += 1
            self.peak = max(self.peak, self.active)
        try:
            if step.get("sleep"):
                time.sleep(step["sleep"])
            if step.get("fail") is not None:
                raise step["fail"]
            size = tuple(step.get("size", (image.width, image.height)))
            png = png_of_size(size, (n * 7 % 256, n * 13 % 256, n * 29 % 256))  # unique bytes per call
            with self._lock:
                self.outputs[png] = step["ocr"]
        finally:
            with self._lock:
                self.active -= 1
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

    def judge_output(self, source, output, target_lang):
        text = " ".join(w.text for w in output.words)
        with self._lock:
            self.judge_calls.append({"source": " ".join(w.text for w in source.words), "output": text, "lang": target_lang})
        scripted = self.judge_plan.get(text)
        if isinstance(scripted, Exception):
            raise scripted
        return dict(scripted) if scripted is not None else judge_record()

    def install(self, monkeypatch):
        from app import judge, openrouter, vision

        monkeypatch.setattr(openrouter, "run_image_model", self.run_image_model)
        monkeypatch.setattr(vision, "ocr_words", self.ocr_words)
        monkeypatch.setattr(judge, "judge_output", self.judge_output)
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
