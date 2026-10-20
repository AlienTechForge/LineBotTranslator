"""Mode A: the erase / fit decisions inside render_units, and the one retry with the judge's findings."""

import io
import json
import math

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


# ---------------------------------------------------------------- round 6: one size and one weight for the rows of a group


def hairlines(img, y0, y1, x0, x1):
    for y in range(y0 + 3, y1 - 2, 6):
        img[y : y + 1, x0:x1] = BLACK


def thick_strokes(img, y0, y1, x0, x1):
    for x in range(x0 + 2, x1 - 10, 14):
        img[y0 + 3 : y1 - 3, x : x + 9] = BLACK


def rows_scene(texts, *, shorts=None, weights=None, groups=None, heights=None, widths=None, ink=hairlines):
    """A white page with one row of ink per text: a word box each, 240 px wide and 34 px tall unless told otherwise."""
    n = len(texts)
    heights = heights or [34] * n
    widths = widths or [240] * n
    img = np.full((70 + sum(heights) + 18 * n, 500, 3), WHITE, np.uint8)
    words, units, y = [], [], 30
    for i, text in enumerate(texts):
        ink(img, y, y + heights[i], 42, 40 + widths[i] - 2)
        words.append(word(i + 1, f"w{i}", rect(40, y, 40 + widths[i], y + heights[i])))
        units.append(
            ClaudeUnit(
                word_ids=[f"w{i + 1:04d}"], source_text=f"s{i}", translation=text, role="label",
                short=(shorts or [""] * n)[i], weight=(weights or ["regular"] * n)[i], group=(groups or [""] * n)[i],
            )  # fmt: skip
        )
        y += heights[i] + 18
    return img, OcrResult(width=500, height=img.shape[0], words=words), units


def render_rows(*args, **kw):
    img, ocr_, units = rows_scene(*args, **kw)
    return pipeline_a.render_units(img, ocr_, units, "en").units


MENU = ["Tea", "Braised pork rice with ginger", "Beef noodle soup"]


def test_three_rows_of_one_group_come_out_at_one_size_and_one_weight():
    alone = render_rows(MENU)
    assert len({u["font_size"] for u in alone}) == 3  # without a group every row is fitted on its own
    rows = render_rows(MENU, groups=["menu-names"] * 3)
    assert len({u["font_size"] for u in rows}) == 1 and len({u["bold"] for u in rows}) == 1
    size = rows[0]["font_size"]
    assert size == min(u["font_size"] for u in alone)  # the smallest size a row managed with its full text
    assert all(u["group"] == "menu-names" and u["group_size"] == size and u["group_weight"] == "regular" for u in rows)
    assert all(u["text_used"] == "full" and u["group_size_broken"] is False for u in rows)
    assert all(u["readable"] for u in rows)


def test_a_unit_outside_the_group_is_not_touched_by_it():
    alone = render_rows(["Tea"])[0]
    mixed = render_rows([*MENU, "Tea"], groups=["menu-names"] * 3 + [""])
    outsider = mixed[3]
    assert outsider["font_size"] == alone["font_size"] > mixed[0]["font_size"]  # the group is smaller, the stranger keeps its own size
    assert outsider["group"] == "" and outsider["group_size"] is None and outsider["group_weight"] is None
    assert outsider["group_size_broken"] is False
    assert len({u["font_size"] for u in mixed[:3]}) == 1


def test_two_groups_are_settled_independently():
    rows = render_rows(["Tea", "Braised pork rice with ginger", "Soup", "Rice"], groups=["a", "a", "b", "b"])
    assert rows[0]["font_size"] == rows[1]["font_size"] and rows[2]["font_size"] == rows[3]["font_size"]
    assert rows[2]["font_size"] > rows[0]["font_size"] and rows[0]["group_size"] != rows[2]["group_size"]


def test_a_group_of_one_keeps_its_size_and_records_it():
    alone = render_rows(["Tea"])[0]
    single = render_rows(["Tea"], groups=["lonely"])[0]
    assert single["font_size"] == alone["font_size"] and single["group"] == "lonely" and single["group_size"] == alone["font_size"]


def test_weight_the_planner_wins_when_the_units_of_a_group_agree():
    rows = render_rows(["Tea", "Pork rice", "Beef noodle"], weights=["bold"] * 3, groups=["g"] * 3)  # hairlines: the strokes say regular
    assert [u["bold"] for u in rows] == [True] * 3 and {u["group_weight"] for u in rows} == {"bold"}
    rows = render_rows(["Tea", "Pork rice", "Beef noodle"], weights=["regular"] * 3, groups=["g"] * 3, ink=thick_strokes)
    assert [u["bold"] for u in rows] == [False] * 3 and {u["group_weight"] for u in rows} == {"regular"}  # strokes say bold, planner wins


def test_weight_a_group_that_disagrees_goes_by_the_majority_of_planner_or_stroke_bold():
    names = ["Tea", "Pork rice", "Beef noodle"]
    two_bold = render_rows(names, weights=["bold", "bold", "regular"], groups=["g"] * 3)
    assert [u["bold"] for u in two_bold] == [True] * 3 and {u["group_weight"] for u in two_bold} == {"bold"}
    one_bold = render_rows(names, weights=["regular", "bold", "regular"], groups=["g"] * 3)
    assert [u["bold"] for u in one_bold] == [False] * 3 and {u["group_weight"] for u in one_bold} == {"regular"}
    by_strokes = render_rows(names, weights=["regular", "regular", "bold"], groups=["g"] * 3, ink=thick_strokes)
    assert [u["bold"] for u in by_strokes] == [True] * 3  # the planner disagrees with itself; the strokes of all three look bold
    tie = render_rows(names[:2], weights=["bold", "regular"], groups=["g"] * 2)
    assert [u["bold"] for u in tie] == [False, False]  # a tie is regular


def test_a_row_whose_full_text_does_not_fit_at_the_group_size_uses_its_short_text():
    texts = ["Tea", "Braised pork rice with ginger and a very long extra description", "Beef noodle soup"]
    rows = render_rows(texts, shorts=["", "Pork rice", ""], groups=["menu"] * 3)
    assert [u["text_used"] for u in rows] == ["full", "short", "full"]
    assert len({u["font_size"] for u in rows}) == 1 and all(u["group_size"] == rows[0]["font_size"] for u in rows)
    assert all(u["group_size_broken"] is False for u in rows)


def test_a_row_that_cannot_reach_the_group_size_keeps_its_own_and_is_flagged():
    texts = ["Tea", "Braised pork rice with ginger and a very long extra description that goes on and on and on", "Beef noodle soup"]
    rows = render_rows(texts, groups=["menu"] * 3)
    broken = rows[1]
    assert broken["group_size_broken"] is True and broken["font_size"] < broken["group_size"]
    assert rows[0]["font_size"] == rows[2]["font_size"] == broken["group_size"] and broken["readable"] is False
    assert [u["group_size_broken"] for u in (rows[0], rows[2])] == [False, False]


def test_the_group_size_is_not_below_a_readable_share_of_the_median_glyph_height():
    # three rows of 34 px glyphs and one of 20 px in a box too narrow for more than 15 px: the group does not shrink to 15
    texts = ["Tea", "Pork rice with ginger", "Beef noodle soup", "Beef noodle soup"]
    rows = render_rows(texts, groups=["menu"] * 4, heights=[34, 34, 34, 20], widths=[240, 240, 240, 150])
    floor = math.ceil(0.7 * 34 - 1e-9)  # MIN_FIT_RATIO of the median glyph height
    assert rows[0]["group_size"] >= floor and rows[0]["font_size"] == rows[1]["font_size"] == rows[2]["font_size"] >= floor
    assert rows[3]["group_size_broken"] is True and rows[3]["font_size"] < rows[3]["group_size"]


def test_the_group_fields_reach_the_units_file_of_a_mode_a_run(monkeypatch, tmp_path):
    ocr_ = mock.vision_words(640, 480)
    ids = [w.id for w in ocr_.words]
    units = [
        ClaudeUnit(word_ids=ids[0:3], source_text="TONKATSU SET MENU", translation="Pork Cutlet Set Menu", role="title", group="titles"),
        ClaudeUnit(word_ids=[ids[3]], source_text="NT$120", translation="NT$120", keep=True, role="price"),
        ClaudeUnit(word_ids=ids[5:7], source_text="LUNCH SPECIAL", translation="Today's special lunch", rotated=True, group="titles"),
    ]  # fmt: skip
    h = Harness(monkeypatch, [judge_record(usability=0.95)], calls=[planner_call(units)])
    result = h.run(tmp_path)
    doc = json.loads((tmp_path / "A_a1_units.json").read_text(encoding="utf-8"))
    assert [u["group"] for u in doc["units"]] == ["titles", "", "titles"] == [u["group"] for u in result["units"]]
    assert all(set(u) >= {"group", "group_size", "group_weight", "group_size_broken"} for u in doc["units"])
    titles = [u for u in doc["units"] if u["group"]]
    assert titles[0]["group_size"] == titles[1]["group_size"] and titles[0]["group_weight"] == titles[1]["group_weight"]


# ---------------------------------------------------------------- round 6: the report of the ring decision, the lane test, the coverage fields


def test_the_unit_report_records_the_ring_the_fill_and_the_reason():
    img = np.full((200, 400, 3), WHITE, np.uint8)
    img[:, 280:] = BLACK
    speckle(img, (60, 90, 40, 200), BLACK)
    u = by_id(pipeline_a.render_units(img, ocr_of((38, 58, 202, 92)), [unit(["w0001"], translation="Braised pork")], "en"))["u01"]
    assert u["erase_method"] == "flat" and u["erase_reason"] == "ring agrees with the box"
    assert u["erase_ring_color"] == "#ffffff" and u["erase_fill"] == "#ffffff" and u["erase_ring_spread"] == 0.0
    assert u["erase_ring_gap"] == 0.0 and u["erase_extension"] in (None, "filled")
    rng = np.random.default_rng(2)
    ramp = np.linspace(40, 230, 400)[None, :, None] + rng.normal(0, 22, (200, 400, 1))
    textured = np.clip(np.repeat(ramp, 200, axis=0).repeat(3, axis=2), 0, 255).astype(np.uint8)
    speckle(textured, (60, 90, 40, 140), (5, 5, 5))
    t = by_id(pipeline_a.render_units(textured, ocr_of((38, 58, 142, 92)), [unit(["w0001"], translation="Tea")], "en"))["u01"]
    assert t["erase_method"] == "inpaint" and t["erase_reason"] in ("box not flat", "ring not flat")
    assert t["erase_fill"] == t["erase_bg"]  # nothing is filled: the colour reported is the box's


def test_the_fill_in_the_report_is_the_paper_not_the_box_colour():
    img = np.full((120, 300, 3), (240, 240, 240), np.uint8)
    img[40:80, 40:200] = (236, 236, 236)  # the box is a little darker than the paper
    speckle(img, (44, 76, 44, 196), (10, 10, 10), seed=3)
    u = by_id(pipeline_a.render_units(img, ocr_of((40, 40, 200, 80), size=(120, 300)), [unit(["w0001"], translation="Tea")], "en"))["u01"]
    assert u["erase_bg"] == "#ececec" and u["erase_ring_color"] == "#f0f0f0" and u["erase_fill"] == "#f0f0f0"


def test_a_lane_is_cut_where_the_paper_stops_being_the_fill_colour_instead_of_running_to_the_limit():
    def grown(step_color):
        img = np.full((200, 600, 3), WHITE, np.uint8)
        speckle(img, (60, 90, 40, 140), BLACK)
        img[:, 170:] = step_color  # paper that changes 28 px right of the word
        ocr_ = ocr_of((38, 58, 142, 92), size=(200, 600))
        text = "Complimentary hot beverage"
        return by_id(pipeline_a.render_units(img, ocr_, [unit(["w0001"], translation=text, align="left")], "en"))["u01"]

    cut = grown((216, 216, 216))  # inside the colour guard, but not the fill colour
    same = grown((252, 252, 252))  # three levels off: the same paper as far as the ring rule is concerned
    assert cut["erase_method"] == "flat" and 0 < cut["lane"][1] <= 45
    assert same["lane"][1] > cut["lane"][1] + 20  # the lane of the same paper runs on to the growth limit


def test_the_coverage_fields_of_the_planner_reach_the_attempt_the_result_and_the_units_file(monkeypatch, tmp_path):
    info = {"pass": True, "words": 7, "unlisted_before": 3, "unlisted_after": 0, "added_units": 1, "added_words": 3, "error": None}
    call = planner_call(canned_units())
    call.coverage = info
    result = Harness(monkeypatch, [judge_record(usability=0.95)], calls=[call]).run(tmp_path)
    assert result["coverage_pass"] is True and result["planner_coverage"] == info
    assert result["attempts"][0]["coverage_pass"] is True and result["attempts"][0]["planner_coverage"] == info
    assert result["coverage"] == 0.95  # `coverage` is still the judge's number
    doc = json.loads((tmp_path / "A_a1_units.json").read_text(encoding="utf-8"))
    assert doc["coverage_pass"] is True and doc["planner_coverage"] == info
    plain = Harness(monkeypatch, [judge_record(usability=0.95)]).run(tmp_path)
    assert plain["coverage_pass"] is False and plain["planner_coverage"] is None


def test_a_retry_that_runs_into_max_tokens_keeps_the_first_attempt_and_shows_the_reason(monkeypatch, tmp_path):
    from app.models import ModeError

    spent = ModeError("Claude hit max_tokens=32000 before finishing the JSON (the call produced 32000 output tokens); the image has too many units for one call")
    h = Harness(monkeypatch, [judge_record(usability=0.4)], calls=[planner_call(canned_units()), spent])
    result = h.run(tmp_path)
    assert len(result["attempts"]) == 1 and result["selected_attempt"] == 1 and result["verdict"] == "score"
    assert "max_tokens=32000" in result["retry_error"] and "32000 output tokens" in result["retry_error"]
    assert (tmp_path / "A_a1.png").is_file() and not (tmp_path / "A_a2.png").exists()
