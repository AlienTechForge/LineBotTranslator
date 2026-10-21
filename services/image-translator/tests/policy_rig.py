"""Scripted stand-ins for everything the production policy talks to (no network, no Vision, no OpenRouter, no Claude).

`Rig(..., glyph_px=N)` sets the box height of its words (the "tiny text" rule of the policy reads it).
`Rig` replaces `vision.ocr_words` (the source OCR), `judge.judge_output` (one record per picture), `pipeline_a.run_mode_a`
(Mode A, scripted per call), `openrouter.run_image_model` (Mode B, scripted per call) and the policy's clock. Mode A's
fake calls the `check` and `on_plan` the policy hands it, exactly like the real one, so the policy's own bookkeeping
(budget, structure_checks, costs, translated text) is what the tests look at.
"""

from __future__ import annotations

from types import SimpleNamespace

from scenario import judge_record, ocr, png_of_size

from app import budget, judge, openrouter, pipeline_a, policy, vision
from app.imaging import load_image
from app.models import ClaudeUnit

SIZE = (80, 60)


def units(*items) -> list[ClaudeUnit]:
    """('source', 'translation') or ('source', 'translation', 'keep') triples as the planner's units."""
    out = []
    for i, item in enumerate(items):
        source, translation, *rest = item
        out.append(ClaudeUnit(word_ids=[f"w{i + 1:04d}"], source_text=source, translation=translation, keep=bool(rest and rest[0])))
    return out


def judged(usability: float = 0.95, **issues) -> dict:
    """A judge record: with `usability` >= 0.80 and no issues the verdict is `pass`."""
    return judge_record(usability, **issues)


class Rig:
    def __init__(
        self, monkeypatch, tmp_path, *, a=(), b=(), words=("TONKATSU", "SET"), ocr_error=None, now: float = 1000.0,
        glyph_px: float = 24.0,
    ):  # fmt: skip
        monkeypatch.setenv("LAB_DATA_DIR", str(tmp_path / "data"))
        self.tmp = tmp_path
        self.now = now
        self.a_steps, self.b_steps = list(a), list(b)
        self.calls_a: list[dict] = []
        self.calls_b: list[dict] = []
        self.judge_calls: list[bytes] = []
        self.ocr_calls = 0
        self.judge_by_png: dict[bytes, dict] = {}
        self._counter = 0
        # a bare string is a word whose box is `glyph_px` tall (24 px by default: above MODE_A_MIN_GLYPH_PX, so Mode A goes first)
        boxed = [(w, None, 0.9, glyph_px) if isinstance(w, str) else w for w in words]
        self._ocr = ocr(*boxed, size=SIZE) if boxed else ocr(size=SIZE)
        self._ocr_error = ocr_error
        monkeypatch.setattr(vision, "ocr_words", self._vision)
        monkeypatch.setattr(judge, "judge_output", self._judge)
        monkeypatch.setattr(pipeline_a, "run_mode_a", self._mode_a)
        monkeypatch.setattr(openrouter, "run_image_model", self._mode_b)
        monkeypatch.setattr(policy, "_clock", lambda: self.now)
        self.image = load_image(png_of_size(SIZE))

    # ---- the stand-ins
    def _vision(self, image_bytes, width, height):
        self.ocr_calls += 1
        if self._ocr_error is not None:
            raise self._ocr_error
        return self._ocr

    def _judge(self, source_ocr, source_image, output_png, target_lang):
        self.judge_calls.append(output_png)
        return dict(self.judge_by_png[output_png])

    def _picture(self, step: dict) -> bytes:
        self._counter += 1
        png = png_of_size(SIZE, (self._counter * 11 % 256, self._counter * 37 % 256, 99))
        self.judge_by_png[png] = step["judge"] if step.get("judge") is not None else judged(step.get("usability", 0.95))
        return png

    def _mode_a(self, image, lang, out_dir, source_ocr=None, *, check=None, retry=False, meta=None, on_plan=None):
        self.calls_a.append({"lang": lang, "retry": retry, "has_check": check is not None, "meta": meta})
        step = self.a_steps.pop(0)
        self.now += step.get("takes", 0)
        if step.get("fail") is not None and not step.get("after_plan"):
            raise step["fail"]
        if on_plan is not None and step.get("units") is not None:
            on_plan(SimpleNamespace(units=step["units"], cost_usd=step.get("plan_cost", 0.02)))
        if step.get("fail") is not None:
            raise step["fail"]
        png = self._picture(step)
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "A_a1.png").write_bytes(png)
        fields = check(png, SIZE) if check is not None else {"verification": None}
        return {"output": "A_a1.png", "verification": fields["verification"], "warnings": list(step.get("warnings", []))}

    def _mode_b(self, image, lang, model, *, prompt=None, resolution=""):
        self.calls_b.append({"lang": lang, "model": model, "prompt": prompt, "resolution": resolution})
        step = self.b_steps.pop(0)
        self.now += step.get("takes", 0)
        if step.get("fail") is not None:
            raise step["fail"]
        png = self._picture(step)
        cost = step.get("cost", 0.04)
        return {
            "png": png, "size": list(SIZE), "usage": {} if cost is None else {"cost": cost}, "api_used": "images",
            "attempts": [], "model_text": "", "elapsed_ms": 5, "prompt": prompt or "p",
        }  # fmt: skip

    # ---- running it
    def run(self, policy_name: str = "a_then_b", *, lang: str = "zh-TW", deadline_s: float = 210.0, model_b=None, warnings=None):
        return policy.run(
            self.image, lang, policy_name, "req-1", self.now + deadline_s,
            work_dir=self.tmp / "work", mode_b_model=model_b, warnings=warnings,
        )  # fmt: skip

    def spent(self) -> dict:
        return budget.state()
