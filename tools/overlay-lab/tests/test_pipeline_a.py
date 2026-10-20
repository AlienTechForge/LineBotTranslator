"""Mode A: the erase / fit decisions inside render_units, and the one retry with the judge's findings."""

import io
import json

import numpy as np
import pytest
from helpers import rect, word
from PIL import Image
from scenario import judge_record

from app import claude_units, mock, pipeline_a, verify
from app.claude_units import ClaudeCall
from app.imaging import load_image
from app.models import ClaudeUnit, ClaudeUnits, OcrResult

WHITE, BLACK = (255, 255, 255), (0, 0, 0)


def speckle(img, yx, color, share=0.4, seed=0):
    y0, y1, x0, x1 = yx
    img[y0:y1, x0:x1][np.random.default_rng(seed).random((y1 - y0, x1 - x0)) < share] = color


def unit(word_ids, source="src", translation="Hello", **kw) -> ClaudeUnit:
    return ClaudeUnit(word_ids=word_ids, source_text=source, translation=translation, **kw)


def ocr_of(*boxes, size=(200, 400)) -> OcrResult:
    words = [word(i + 1, f"w{i + 1}", rect(*box)) for i, box in enumerate(boxes)]
    return OcrResult(width=size[1], height=size[0], words=words, text=" ".join(w.text for w in words))


def by_id(result):
    return {u["id"]: u for u in result.units}


# ---------------------------------------------------------------- erase method and what the report says


def test_the_unit_report_records_how_it_was_erased_and_fitted():
    img = np.full((200, 400, 3), WHITE, np.uint8)
    img[:, 280:] = BLACK  # a black price cell
    speckle(img, (60, 90, 40, 200), BLACK)
    ocr_ = ocr_of((38, 58, 202, 92))
    result = pipeline_a.render_units(img, ocr_, [unit(["w0001"], translation="Braised pork", align="left", weight="bold", importance="major")], "en")
    u = by_id(result)["u01"]
    assert u["action"] == "translated" and u["erase_method"] == "flat" and u["erase_bg"] == "#ffffff"
    assert u["erase_spread"] == 0.0 and 0.2 < u["erase_ink_share"] < 0.6 and u["erase_px"] > 0
    assert (u["text_used"], u["align"], u["weight"], u["importance"], u["short"]) == ("full", "left", "bold", "major", "")
    assert u["readable"] is True and u["fitted"] is True and u["fit_ratio"] > 0.7 and u["min_fit_px"] >= 1
    assert u["bold"] is True  # the planner said bold
    assert u["grow"] == [0.0, 0.0] or u["grow"][1] >= 0.0
    assert (result.erased[:, 280:] == 0).all() and (result.erased[40:110, :270] >= 250).all()


def test_a_textured_background_is_inpainted_and_never_grows():
    rng = np.random.default_rng(2)
    ramp = np.linspace(40, 230, 400)[None, :, None] + rng.normal(0, 22, (200, 400, 1))
    img = np.clip(np.repeat(ramp, 200, axis=0).repeat(3, axis=2), 0, 255).astype(np.uint8)
    speckle(img, (60, 90, 40, 140), (5, 5, 5))
    ocr_ = ocr_of((38, 58, 142, 92))
    long_text = "Complimentary hot beverage with every set meal"
    result = pipeline_a.render_units(img, ocr_, [unit(["w0001"], translation=long_text, align="left")], "en")
    u = by_id(result)["u01"]
    assert u["erase_method"] == "inpaint" and u["lane"] == [0.0, 0.0] and u["grow"] == [0.0, 0.0]


def test_a_unit_grows_into_the_free_flat_lane_and_stops_before_the_next_unit_and_a_different_colour():
    img = np.full((200, 600, 3), WHITE, np.uint8)
    speckle(img, (60, 90, 40, 140), BLACK)
    img[:, 175:205] = (30, 60, 200)  # a blue block 30 px right of the word: the lane is flat only up to it
    ocr_ = ocr_of((38, 58, 142, 92), (420, 58, 520, 92), size=(200, 600))
    units = [unit(["w0001"], translation="Complimentary hot beverage", align="left"), unit(["w0002"], translation="Other", align="left")]
    result = pipeline_a.render_units(img, ocr_, units, "en")
    first = by_id(result)["u01"]
    assert first["erase_method"] == "flat" and first["lane"][0] == 0.0 and 10 < first["lane"][1] < 35  # up to the blue block
    assert 0 < first["grow"][1] <= first["lane"][1] + 1e-6 and first["grow"][0] == 0.0
    assert first["ink_bbox"][2] < 175  # drawn text never reaches the blue block
    assert (result.output[:, 175:205] == img[:, 175:205]).all()
    free = pipeline_a.render_units(img[:, :170].copy(), ocr_of((38, 58, 142, 92), size=(200, 170)), units[:1], "en")
    assert by_id(free)["u01"]["lane"][1] > 0  # sanity: without the block the lane is the image edge or the cap


def test_two_units_facing_each_other_share_the_gap_instead_of_overlapping():
    img = np.full((120, 400, 3), WHITE, np.uint8)
    speckle(img, (40, 70, 20, 120), BLACK, seed=1)
    speckle(img, (40, 70, 280, 380), BLACK, seed=2)
    ocr_ = ocr_of((18, 38, 122, 72), (278, 38, 382, 72), size=(120, 400))
    text = "Complimentary hot beverage"
    units = [unit(["w0001"], translation=text, align="left"), unit(["w0002"], translation=text, align="right")]
    result = pipeline_a.render_units(img, ocr_, units, "en")
    a, b = by_id(result)["u01"], by_id(result)["u02"]
    assert a["grow"][1] > 0 and b["grow"][0] > 0
    assert a["ink_bbox"][2] < b["ink_bbox"][0]  # the grown texts do not meet


def test_a_minor_unit_that_cannot_be_drawn_readably_is_left_untouched_and_reported():
    img = np.full((200, 400, 3), WHITE, np.uint8)
    speckle(img, (60, 80, 40, 100), BLACK)
    speckle(img, (120, 140, 40, 200), (200, 30, 30), seed=3)
    ocr_ = ocr_of((38, 58, 102, 82), (38, 118, 202, 142))
    long_text = "Complimentary hot beverage with every set meal and a long sentence besides"
    units = [unit(["w0001"], translation=long_text, importance="minor"), unit(["w0002"], translation="Fine", importance="major")]
    result = pipeline_a.render_units(img, ocr_, units, "en")
    a, b = by_id(result)["u01"], by_id(result)["u02"]
    assert a["action"] == "skipped" and a["skip_reason"] == "too small" and "erase_method" not in a
    assert np.array_equal(result.output[55:85, 35:105], img[55:85, 35:105])  # the source text is still there
    assert np.array_equal(result.erased[55:85, 35:105], img[55:85, 35:105])  # ... in the erased image too
    assert b["action"] == "translated" and b["erase_method"] == "flat"
    assert pipeline_a.problem_units(result.units) == [a]  # named in the retry paragraph by its source text


def test_a_major_unit_in_the_same_spot_is_drawn_small_and_flagged_unreadable():
    img = np.full((200, 400, 3), WHITE, np.uint8)
    speckle(img, (60, 80, 40, 100), BLACK)
    ocr_ = ocr_of((38, 58, 102, 82))
    long_text = "Complimentary hot beverage with every set meal and a long sentence besides"
    result = pipeline_a.render_units(img, ocr_, [unit(["w0001"], translation=long_text)], "en")
    u = by_id(result)["u01"]
    assert u["action"] == "translated" and u["readable"] is False and u["font_size"] >= 10
    assert pipeline_a.problem_units(result.units) == [u]


def test_the_short_alternative_is_used_when_the_full_translation_would_be_unreadable():
    img = np.full((200, 400, 3), WHITE, np.uint8)
    speckle(img, (60, 80, 40, 100), BLACK)
    ocr_ = ocr_of((38, 58, 102, 82))
    long_text = "Complimentary hot beverage with every set meal"
    result = pipeline_a.render_units(img, ocr_, [unit(["w0001"], translation=long_text, short="Free drink")], "en")
    u = by_id(result)["u01"]
    assert u["text_used"] == "short" and u["readable"] is True and u["short"] == "Free drink"
    assert pipeline_a.problem_units(result.units) == []


def test_the_stroke_estimate_still_makes_heavy_ink_bold_without_the_planner_saying_so():
    img = np.full((120, 400, 3), WHITE, np.uint8)
    for x in range(40, 200, 14):  # thick vertical strokes: a bold face
        img[50:90, x : x + 9] = BLACK
    ocr_ = ocr_of((38, 48, 210, 92), size=(120, 400))
    bold_est = by_id(pipeline_a.render_units(img, ocr_, [unit(["w0001"], translation="Heavy")], "en"))["u01"]
    assert bold_est["bold"] is True and bold_est["weight"] == "regular"


def test_no_font_role_forces_bold_any_more_the_planner_decides():
    img = np.full((120, 400, 3), WHITE, np.uint8)
    for y in range(52, 90, 9):  # hairlines
        img[y : y + 1, 40:200] = BLACK
    ocr_ = ocr_of((38, 48, 202, 92), size=(120, 400))
    for role in ("title", "heading"):
        u = by_id(pipeline_a.render_units(img, ocr_, [unit(["w0001"], translation="Heading", role=role)], "en"))["u01"]
        assert u["bold"] is False


def test_the_mock_fixture_renders_with_flat_fills_and_a_rotated_column_in_english():
    fx = mock.make_fixture()
    ocr_ = mock.vision_words(640, 480)
    text, _, _ = mock.claude_response(ocr_)
    units = ClaudeUnits.model_validate(json.loads(text)).units
    result = pipeline_a.render_units(np.array(fx.image), ocr_, units, "en")
    u = by_id(result)
    assert [u[i]["action"] for i in ("u01", "u02", "u03", "u04")] == ["translated", "kept", "translated", "translated"]
    assert {u[i]["erase_method"] for i in ("u01", "u03", "u04")} == {"flat"}
    assert u["u03"]["layout"] == "vertical_latin" and u["u03"]["readable"] and u["u04"]["angle"] == pytest.approx(15.0, abs=1.0)
    background = np.array(mock.BACKGROUND)
    assert np.abs(result.erased[:200, :400].astype(int) - background).max() <= 3  # the title area is plain cream again


# ---------------------------------------------------------------- the one retry with the judge's findings


class Source:
    ms = 12

    def __init__(self, ocr_):
        self.ocr_ = ocr_

    def get(self):
        return self.ocr_


def png_image():
    fx = mock.make_fixture()
    buf = io.BytesIO()
    fx.image.save(buf, "PNG")
    return load_image(buf.getvalue())


def planner_call(units, cost=0.05, tokens=(1000, 200)):
    return ClaudeCall(
        units=units, input_tokens=tokens[0], output_tokens=tokens[1], cost_usd=cost, model="claude-opus-5-5",
        served_model="claude-opus-5-5", stop_reason="end_turn", fallback_form="default", elapsed_ms=40,
    )  # fmt: skip


def canned_units():
    ocr_ = mock.vision_words(640, 480)
    text, _, _ = mock.claude_response(ocr_)
    return ClaudeUnits.model_validate(json.loads(text)).units


class Harness:
    """A scripted planner (one answer per call) and a scripted check (one verdict per output)."""

    def __init__(self, monkeypatch, verdicts, calls=None):
        self.requests = []
        self.checks = []
        self.verdicts = list(verdicts)
        self.calls = list(calls or [planner_call(canned_units()), planner_call(canned_units(), cost=0.07, tokens=(1100, 250))])

        def request_units(image, ocr_, lang, feedback=None):
            self.requests.append(feedback)
            step = self.calls.pop(0)
            if isinstance(step, Exception):
                raise step
            return step

        monkeypatch.setattr(claude_units, "request_units", request_units)

    def check(self, png, size):
        verdict = self.verdicts.pop(0)
        self.checks.append((len(png), size))
        if isinstance(verdict, Exception):
            raise verdict
        judge = verdict if isinstance(verdict, dict) else judge_record()
        report = verify.verify_output(None, None, "en", judge=judge, ocr_error="not run in this test")
        return {
            "verification": report, "verdict": report["verdict"], "verify_error": None, "verify_ms": 5, "judge_ms": 4,
            "judge_status": judge["status"], "judge_cost_usd": judge.get("cost_usd"),
        }  # fmt: skip

    def run(self, tmp_path, retry=True, check=True):
        ocr_ = mock.vision_words(640, 480)
        return pipeline_a.run_mode_a(
            png_image(), "en", tmp_path, Source(ocr_), check=self.check if check else None, retry=retry,
            meta={"job_id": "j" * 16, "target_lang": "en"},
        )  # fmt: skip


def test_a_passing_first_attempt_is_not_retried(monkeypatch, tmp_path):
    h = Harness(monkeypatch, [judge_record(usability=0.95)])
    result = h.run(tmp_path)
    assert len(result["attempts"]) == 1 and h.requests == [None] and result["verdict"] == "pass"
    assert result["selected_attempt"] == 1 and result["output"] == "A_a1.png" and result["retry_error"] is None
    assert result["tokens"] == {"input": 1000, "output": 200} and result["cost_usd"] == pytest.approx(0.05)
    assert result["judge_cost_usd"] == pytest.approx(0.01) and result["total_cost_usd"] == pytest.approx(0.06)
    assert result["cost_anthropic_usd"] == pytest.approx(0.06) and result["cost_openrouter_usd"] == 0.0  # Claude only
    assert result["usability"] == 0.95 and result["translated_count"] == 3 and result["unit_count"] == 4
    for name in ("A_a1.png", "A_a1_erased.png", "A_a1_units.json", "A_a1_verify.json"):
        assert (tmp_path / name).is_file(), name
    assert not (tmp_path / "A_a2.png").exists() and not (tmp_path / "A.png").exists()
    units_doc = json.loads((tmp_path / "A_a1_units.json").read_text(encoding="utf-8"))
    assert units_doc["job_id"] == "j" * 16 and units_doc["attempt"] == 1 and len(units_doc["units"]) == 4
    assert result["units_file"] == "A_a1_units.json" and result["verification_file"] == "A_a1_verify.json"


def test_a_failed_verdict_calls_the_planner_once_more_with_what_the_judge_found(monkeypatch, tmp_path):
    first = judge_record(usability=0.55, garbled=[("title banner", "Pork Cutlet Set Menu", True)], untranslated=["紅燒肉飯店"])
    h = Harness(monkeypatch, [first, judge_record(usability=0.93, cost=0.02)])
    result = h.run(tmp_path)
    assert len(h.requests) == 2 and h.requests[0] is None
    feedback = h.requests[1]
    assert feedback.startswith(claude_units.FEEDBACK_HEAD) and '"Pork Cutlet Set Menu" (title banner)' in feedback
    assert '"紅燒肉飯店"' in feedback  # the major untranslated text, by its text
    a1, a2 = result["attempts"]
    assert (a1["verdict"], a2["verdict"]) == ("garbled+partial+score", "pass")
    assert (a1["selected"], a2["selected"]) == (False, True) and result["selected_attempt"] == 2
    assert result["output"] == "A_a2.png" and result["verdict"] == "pass" and result["usability"] == 0.93
    assert a2["retry_feedback"] == feedback and a1["retry_feedback"] is None
    for name in ("A_a1.png", "A_a2.png", "A_a1_units.json", "A_a2_units.json", "A_a1_verify.json", "A_a2_verify.json"):
        assert (tmp_path / name).is_file(), name  # both attempts are kept
    assert result["tokens"] == {"input": 2100, "output": 450}  # both planner calls
    assert result["cost_usd"] == pytest.approx(0.12) and result["judge_cost_usd"] == pytest.approx(0.03)
    assert result["total_cost_usd"] == pytest.approx(0.15) and len(h.checks) == 2


def test_the_renderers_own_problems_are_named_in_the_retry_paragraph(monkeypatch, tmp_path):
    ocr_ = mock.vision_words(640, 480)
    ids = [w.id for w in ocr_.words]
    too_long = "Complimentary hot beverage with every set meal and a very long sentence besides, more and more"
    crowded = [
        ClaudeUnit(word_ids=ids[0:3], source_text="TONKATSU SET MENU", translation=too_long, role="title", short="", importance="major"),
        ClaudeUnit(word_ids=[ids[3]], source_text="NT$120", translation="NT$120", keep=True),
    ]  # fmt: skip
    h = Harness(monkeypatch, [judge_record(usability=0.6), judge_record(usability=0.9)], calls=[planner_call(crowded), planner_call(canned_units())])
    result = h.run(tmp_path)
    assert result["verdict"] == "pass" and "TONKATSU SET MENU" in h.requests[1]
    assert "had to be drawn too small" in h.requests[1] or "did not fit its box" in h.requests[1] or "was cut off" in h.requests[1]


def test_the_retry_can_be_switched_off_and_an_unverified_attempt_is_never_retried(monkeypatch, tmp_path):
    h = Harness(monkeypatch, [judge_record(usability=0.3)])
    result = h.run(tmp_path, retry=False)
    assert len(result["attempts"]) == 1 and result["verdict"] == "score" and result["retry"] is False
    h = Harness(monkeypatch, [judge_record(status="unavailable", error="no key", cost=None)])
    result = h.run(tmp_path)
    assert len(result["attempts"]) == 1 and result["verdict"] == "unverified" and h.requests == [None]
    h = Harness(monkeypatch, [])
    result = h.run(tmp_path, check=False)  # no check at all: no verdict, no retry
    assert len(result["attempts"]) == 1 and result["verdict"] is None and result["verification"] is None


def test_a_failing_second_attempt_keeps_the_first_and_says_why(monkeypatch, tmp_path):
    h = Harness(monkeypatch, [judge_record(usability=0.4)], calls=[planner_call(canned_units()), RuntimeError("Claude API error 500")])
    result = h.run(tmp_path)
    assert len(result["attempts"]) == 1 and result["selected_attempt"] == 1
    assert "Claude API error 500" in result["retry_error"] and result["verdict"] == "score"


def test_a_worse_second_attempt_does_not_replace_the_first(monkeypatch, tmp_path):
    first = judge_record(usability=0.7, untranslated=["a title"])  # partial + score
    second = judge_record(usability=0.9, altered=[("120", "12")])  # numbers
    result = Harness(monkeypatch, [first, second]).run(tmp_path)
    assert [a["verdict"] for a in result["attempts"]] == ["partial+score", "numbers"]
    assert result["selected_attempt"] == 1 and result["output"] == "A_a1.png"


def test_a_first_attempt_that_fails_outright_is_mode_a_s_error(monkeypatch, tmp_path):
    h = Harness(monkeypatch, [], calls=[RuntimeError("Vision credentials missing")])
    with pytest.raises(RuntimeError, match="Vision credentials"):
        h.run(tmp_path)


def test_the_planner_is_asked_again_even_when_only_the_score_failed(monkeypatch, tmp_path):
    first = judge_record(usability=0.5)
    h = Harness(monkeypatch, [first, judge_record(usability=0.85)])
    result = h.run(tmp_path)
    assert len(h.requests) == 2 and "The reviewer's summary" in h.requests[1] and result["verdict"] == "pass"


def test_the_checks_get_the_png_bytes_and_size_of_each_attempt(monkeypatch, tmp_path):
    h = Harness(monkeypatch, [judge_record(usability=0.5), judge_record(usability=0.9)])
    h.run(tmp_path)
    assert [size for _, size in h.checks] == [(640, 480), (640, 480)]
    first = Image.open(tmp_path / "A_a1.png")
    assert first.size == (640, 480) and (tmp_path / "A_a1.png").stat().st_size == h.checks[0][0]


def test_no_units_means_no_erase_and_an_unchanged_image():
    img = np.full((120, 300, 3), WHITE, np.uint8)
    speckle(img, (40, 70, 20, 120), BLACK)
    ocr_ = ocr_of((18, 38, 122, 72), size=(120, 300))
    result = pipeline_a.render_units(img, ocr_, [], "en")
    assert result.units == [] and np.array_equal(result.output, img) and np.array_equal(result.erased, img)
    listed_but_kept = pipeline_a.render_units(img, ocr_, [unit(["w0001"], translation="x", keep=True)], "en")
    assert np.array_equal(listed_but_kept.output, img) and by_id(listed_but_kept)["u01"]["action"] == "kept"
