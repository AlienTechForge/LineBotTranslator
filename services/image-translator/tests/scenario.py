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
    usability: float = 1.0,
    coverage: float | None = None,
    *,
    altered=(),
    rows=(),
    garbled=(),
    untranslated=(),
    mistranslated=(),
    status: str = "ok",
    error: str | None = None,
    cost: float | None = 0.01,
) -> dict:
    """A judge.judge_output record.

    altered: (source, output) pairs; rows: (type, detail) pairs; garbled: (where, output_text, major) triples
    (a bare string is a major issue with that output_text); untranslated: (text, major) pairs (a bare string is
    major); mistranslated: (output_text, source_text) pairs. `coverage` defaults to the usability.
    """
    if status != "ok":
        return {"status": status, "model": "claude-sonnet-5-5", "error": error, "elapsed_ms": 5}

    def glyph(item):
        where, text, major = (f"region of {item}", item, True) if isinstance(item, str) else item
        return {"where": where, "output_text": text, "problem": "not real text", "major": major}

    def untr(item):
        text, major = (item, True) if isinstance(item, str) else item
        return {"text": text, "where": f"where {text} is", "major": major}

    return {
        "status": "ok",
        "model": "claude-sonnet-5-5",
        "served_model": "claude-sonnet-5-5",
        "error": None,
        "usability": usability,
        "coverage": usability if coverage is None else coverage,
        "legibility_issues": [glyph(g) for g in garbled],
        "structure_issues": [{"type": t, "detail": d} for t, d in rows],
        "altered_numbers": [{"source": a, "output": b, "where": "somewhere"} for a, b in altered],
        "untranslated": [untr(u) for u in untranslated],
        "mistranslated": [{"output_text": o, "source_text": s, "problem": "wrong"} for o, s in mistranslated],
        "summary": "scripted judge",
        "input_tokens": 3000,
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
    A step may carry `judge`: the record the vision judge returns for THAT generation's output (a
    record from `judge_record`, or an Exception the judge "raises" - judge_output itself never does,
    so that is only for the failure-isolation tests); an output without one gets a clean judge.
    """

    def __init__(self, source_bytes: bytes, source_ocr: OcrResult, plan: dict, barrier=None):
        self.source_bytes = source_bytes
        self.source_ocr = source_ocr
        self.sources = {source_bytes: source_ocr}  # more images of a batch: add_source
        self.source_calls: dict[bytes, int] = {}
        # keys: a model id, or (model id, source image bytes) for a step that belongs to one image of a batch
        self.plan = {m: list(steps) for m, steps in plan.items()}
        self.judge_by_png: dict[bytes, object] = {}
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

    def add_source(self, source_bytes: bytes, source_ocr: OcrResult) -> "Scenario":
        self.sources[source_bytes] = source_ocr
        return self

    def run_image_model(self, image, lang, model, *, prompt=None, resolution=""):
        if self.barrier is not None and model not in self._started:
            self._started.add(model)
            self.barrier.wait(timeout=10)  # every model must be in flight at the same time
        with self._lock:
            steps = self.plan.get((model, image.data))
            step = (steps if steps else self.plan[model]).pop(0)
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
                if step.get("judge") is not None:
                    self.judge_by_png[png] = step["judge"]
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
            if image_bytes in self.sources:
                self.source_ocr_calls += 1
                self.source_calls[image_bytes] = self.source_calls.get(image_bytes, 0) + 1
                return self.sources[image_bytes]
            self.ocr_calls.append(image_bytes)
            result = self.outputs[image_bytes]
        if isinstance(result, Exception):
            raise result
        return result

    def judge_output(self, source_ocr, source_image, output_png, target_lang):
        with self._lock:
            self.judge_calls.append(
                {
                    "source": " ".join(w.text for w in source_ocr.words) if source_ocr else None,
                    "source_size": tuple(source_image.size),
                    "output_png": output_png,
                    "lang": target_lang,
                }
            )
            scripted = self.judge_by_png.get(output_png)
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
