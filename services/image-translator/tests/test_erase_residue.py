"""Round 8: the residue check after every erase, and the inpainter the erase calls (Telea by default, LaMa on request)."""

import cv2
import numpy as np
import pytest
from helpers import rect, word

from app import erase, inpaint, pipeline_a
from app.erase import EraseUnit, build_unit_mask, erase_units, measure_background
from app.models import ClaudeUnit, OcrResult

WHITE = (255, 255, 255)


def gradient_page(h=60, w=200):
    """A gentle left-to-right gradient (one level per pixel): not flat, so the unit takes the inpaint path."""
    ramp = np.linspace(60, 230, w)[None, :, None]
    return np.repeat(np.repeat(ramp, h, axis=0), 3, axis=2).astype(np.uint8)


def speckle(img, yx, color, share=0.4, seed=0):
    y0, y1, x0, x1 = yx
    img[y0:y1, x0:x1][np.random.default_rng(seed).random((y1 - y0, x1 - x0)) < share] = color


def inpaint_unit(img, box=(82, 20, 108, 40), uid="u01", glyph_h=14.0):
    um = build_unit_mask(img.shape[:2], [word(1, "x", rect(*box))], glyph_h)
    return EraseUnit(uid, um, measure_background(img, um))


def scene():
    img = gradient_page()
    speckle(img, (22, 38, 84, 106), (10, 10, 10), seed=3)
    unit = inpaint_unit(img)
    assert unit.info.method == "inpaint"
    return img, unit


def recorder(monkeypatch, behave):
    """Replace the inpainter of erase.py; `behave(n, rgb, mask)` returns the picture for call number n (1-based)."""
    calls = []

    def report(rgb, mask, *, model=None):
        calls.append((int((mask > 0).sum()), model))
        image = behave(len(calls), rgb, mask)
        return inpaint.InpaintResult(image, model or "telea", [inpaint.ClusterReport((0, 0, rgb.shape[1], rgb.shape[0]), "telea")])

    monkeypatch.setattr(erase.inpainting, "inpaint_report", report)
    return calls


def erases_nothing(n, rgb, mask):
    return rgb.copy()


def real_telea(n, rgb, mask):
    return inpaint.telea(rgb, mask)


def smudges(n, rgb, mask):
    """An eraser that paints a dark grey patch over the glyphs instead of the paper (the grey rectangles of Telea on glare)."""
    out = rgb.copy()
    out[mask > 0] = (30, 30, 30)
    return out


# ---------------------------------------------------------------- the check


def test_a_clean_erase_has_no_residue_and_one_pass():
    img, unit = scene()
    result = erase_units(img, [unit])
    assert result.residue["u01"] == 0.0 and result.passes == {"u01": 1} and result.restored == []
    assert result.engines == {"u01": "telea"} and result.methods == {"u01": "inpaint"}
    assert result.erased[24:36, 86:104].min() > 100  # the ink is gone


def test_a_smudge_left_on_purpose_is_erased_again_with_a_larger_mask_and_then_restored(monkeypatch):
    img, unit = scene()
    calls = recorder(monkeypatch, smudges)
    result = erase_units(img, [unit])
    assert result.restored == ["u01"] and result.passes == {"u01": 2}
    assert result.residue["u01"] > erase.RESIDUE_MAX_SHARE  # the whole patch is unlike the paper
    assert len(calls) == 2 and calls[1][0] > calls[0][0]  # the second pass: a mask grown by RESIDUE_DILATE_PX
    assert np.array_equal(result.erased, img)  # the smudge is gone, the original pixels are back: a visible word beats a smudge


def test_a_unit_whose_eraser_did_nothing_is_dirty_and_restored_too(monkeypatch):
    img, unit = scene()
    recorder(monkeypatch, erases_nothing)
    result = erase_units(img, [unit])
    assert result.restored == ["u01"] and np.array_equal(result.erased, img) and result.residue["u01"] > erase.RESIDUE_MAX_SHARE


def test_the_second_pass_can_rescue_a_unit(monkeypatch):
    img, unit = scene()
    calls = recorder(monkeypatch, lambda n, rgb, mask: rgb.copy() if n == 1 else inpaint.telea(rgb, mask))
    result = erase_units(img, [unit])
    assert result.restored == [] and result.passes == {"u01": 2} and result.residue["u01"] <= erase.RESIDUE_MAX_SHARE
    assert len(calls) == 2 and calls[1][0] > calls[0][0]
    assert result.erased[24:36, 86:104].min() > 100


def test_the_mask_of_the_second_pass_is_the_first_one_grown_by_residue_dilate_px(monkeypatch):
    img, unit = scene()
    seen = []
    recorder(monkeypatch, lambda n, rgb, mask: (seen.append(mask.copy()), rgb.copy() if n == 1 else inpaint.telea(rgb, mask))[1])
    erase_units(img, [unit])
    first, second = (m > 0 for m in seen)
    assert erase.RESIDUE_DILATE_PX == 2 and (first & ~second).sum() == 0
    grown = cv2.dilate(first.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))) > 0
    assert np.array_equal(second, grown)


def test_a_restored_unit_does_not_touch_its_neighbours_erase(monkeypatch):
    img = gradient_page(60, 400)
    speckle(img, (22, 38, 84, 106), (10, 10, 10), seed=3)
    speckle(img, (22, 38, 284, 306), (10, 10, 10), seed=4)
    bad, good = inpaint_unit(img, (82, 20, 108, 40), "u01"), inpaint_unit(img, (282, 20, 308, 40), "u02")
    # the fake erases the right unit properly and smudges the left one
    def half(n, rgb, mask):
        right = (np.arange(rgb.shape[1]) >= 200)[None, :, None] & (mask > 0)[..., None]
        return np.where(right, inpaint.telea(rgb, mask), smudges(n, rgb, mask))

    recorder(monkeypatch, half)
    result = erase_units(img, [bad, good])
    assert result.restored == ["u01"] and result.residue["u02"] <= erase.RESIDUE_MAX_SHARE
    assert np.array_equal(result.erased[:, :200], img[:, :200])  # the dirty unit is back to its pixels
    assert result.erased[24:36, 286:304].min() > 100  # the clean one stays erased


def test_flat_fills_pass_the_check_with_no_residue():
    img = np.full((60, 200, 3), WHITE, np.uint8)
    speckle(img, (22, 38, 84, 106), (0, 0, 0), seed=1)
    unit = inpaint_unit(img)
    assert unit.info.flat
    result = erase_units(img, [unit])
    assert result.residue["u01"] == 0.0 and result.restored == [] and result.engines == {} and (result.erased == 255).all()


def test_a_gentle_gradient_is_not_mistaken_for_residue_and_noisy_paper_gets_a_wider_threshold():
    img, unit = scene()
    plane = erase.ring_plane(img, unit.mask)
    assert plane is not None and plane.threshold == erase.INK_DISTANCE and plane.coef[0, 1] == pytest.approx(0.85, abs=0.05)
    rng = np.random.default_rng(11)
    noisy = np.clip(np.linspace(40, 230, 200)[None, :, None] + rng.normal(0, 22, (60, 200, 1)), 0, 255)
    noisy = np.repeat(np.repeat(noisy, 1, axis=0), 3, axis=2).astype(np.uint8)
    noisy_plane = erase.ring_plane(noisy, inpaint_unit(noisy).mask)
    assert noisy_plane.threshold == pytest.approx(erase.RESIDUE_NOISE_K * noisy_plane.noise) and noisy_plane.threshold > 1.5 * erase.INK_DISTANCE


def test_a_ring_with_no_usable_pixels_leaves_the_check_to_the_colour_of_the_box():
    img = np.full((60, 200, 3), WHITE, np.uint8)
    speckle(img, (22, 38, 84, 106), (0, 0, 0), seed=1)
    unit = inpaint_unit(img)
    assert erase.ring_plane(img, unit.mask, np.ones(img.shape[:2], bool)) is None  # every pixel around belongs to some word
    erase.guard_dilation(img, unit.mask, unit.info)
    clean = img.copy()
    clean[unit.mask.guarded.nonzero()[0] + unit.mask.y0, unit.mask.guarded.nonzero()[1] + unit.mask.x0] = 255
    blocked = np.zeros(img.shape[:2], bool)
    assert erase.residue_share(clean, unit, blocked, None) == 0.0  # white where the ink was: the box colour agrees
    assert erase.residue_share(img, unit, blocked, None) > 0.15  # the ink is still there


def test_the_constants_have_the_values_the_brief_names():
    assert erase.RESIDUE_MAX_SHARE == 0.03 and erase.RESIDUE_DILATE_PX == 2 and erase.INK_DISTANCE == 40.0


# ---------------------------------------------------------------- through render_units (Mode A and Mode C)


def ocr_of(*boxes, size=(60, 200)):
    words = [word(i + 1, f"w{i + 1}", rect(*box)) for i, box in enumerate(boxes)]
    return OcrResult(width=size[1], height=size[0], words=words, text=" ".join(w.text for w in words))


def a_unit(word_ids, translation="Hello", **kw):
    return ClaudeUnit(word_ids=word_ids, source_text="src", translation=translation, **kw)


def test_a_unit_whose_erase_stays_dirty_is_skipped_with_the_residue_reason_and_its_text_is_not_drawn(monkeypatch):
    img = gradient_page()
    speckle(img, (22, 38, 84, 106), (10, 10, 10), seed=3)
    recorder(monkeypatch, erases_nothing)
    result = pipeline_a.render_units(img, ocr_of((82, 20, 108, 40)), [a_unit(["w0001"])], "en")
    u = result.units[0]
    assert u["action"] == "skipped" and u["skip_reason"] == "erase residue" == erase.SKIP_ERASE_RESIDUE
    assert u["erase_method"] == "inpaint" and u["erase_passes"] == 2 and u["erase_residue"] > erase.RESIDUE_MAX_SHARE
    assert any("erase left residue" in w and "original text stays" in w for w in result.warnings)
    assert np.array_equal(result.output, img) and np.array_equal(result.erased, img)  # nothing was drawn, nothing is erased
    notes = pipeline_a.render_notes(result, ocr_of((82, 20, 108, 40)), "none")
    assert "1 unit(s) kept their original text because the erase left residue (a visible original word beats a smudge)" in notes


def test_a_clean_unit_reports_how_it_was_erased(monkeypatch):
    img = gradient_page()
    speckle(img, (22, 38, 84, 106), (10, 10, 10), seed=3)
    result = pipeline_a.render_units(img, ocr_of((82, 20, 108, 40)), [a_unit(["w0001"], "Hi")], "en")
    u = result.units[0]
    assert u["action"] == "translated" and u["inpaint"] == "telea" and u["erase_residue"] == 0.0 and u["erase_passes"] == 1


def test_lama_unavailable_falls_back_to_telea_and_the_unit_report_and_the_warning_say_so(monkeypatch):
    img = gradient_page()
    speckle(img, (22, 38, 84, 106), (10, 10, 10), seed=3)
    monkeypatch.setenv("INPAINT_MODEL", "lama")

    def missing():
        raise ModuleNotFoundError("onnxruntime")

    monkeypatch.setattr(inpaint, "get_session", missing)
    ocr = ocr_of((82, 20, 108, 40))
    result = pipeline_a.render_units(img, ocr, [a_unit(["w0001"], "Hi")], "en")
    u = result.units[0]
    assert u["action"] == "translated" and u["erase_method"] == "inpaint"
    assert u["inpaint"] == "telea (lama unavailable: ModuleNotFoundError)"
    assert pipeline_a.render_notes(result, ocr, "none") == ["1 unit(s) were erased by Telea although lama was asked for (lama unavailable: ModuleNotFoundError)"]


class TeleaSession:
    """A stand-in LaMa that really erases (Telea inside), so that the whole path can be followed."""

    def get_inputs(self):
        return [inpaint_meta("image"), inpaint_meta("mask")]

    def run(self, outputs, feed):
        image = np.rint(feed["image"][0].transpose(1, 2, 0) * 255).astype(np.uint8)
        mask = (feed["mask"][0, 0] > 0).astype(np.uint8) * 255
        out = cv2.inpaint(image, mask, 3, cv2.INPAINT_TELEA).astype(np.float32).transpose(2, 0, 1)[None]
        return [out]


def inpaint_meta(name):
    from test_inpaint import Meta

    return Meta(name, ["batch", 3 if name == "image" else 1, "height", "width"])


def test_with_the_lama_model_the_unit_is_erased_through_the_crop_path(monkeypatch):
    img = gradient_page()
    speckle(img, (22, 38, 84, 106), (10, 10, 10), seed=3)
    monkeypatch.setenv("INPAINT_MODEL", "lama")
    monkeypatch.setattr(inpaint, "get_session", lambda: TeleaSession())
    result = pipeline_a.render_units(img, ocr_of((82, 20, 108, 40)), [a_unit(["w0001"], "Hi")], "en")
    u = result.units[0]
    assert u["action"] == "translated" and u["inpaint"] == "lama" and u["erase_residue"] == 0.0
    assert result.erased[24:36, 86:104].min() > 100


def test_the_default_model_is_telea_so_that_nothing_changes_for_anyone_who_does_not_opt_in(monkeypatch):
    monkeypatch.setattr(inpaint, "get_session", lambda: pytest.fail("INPAINT_MODEL is not set: LaMa must not even be loaded"))
    img = gradient_page()
    speckle(img, (22, 38, 84, 106), (10, 10, 10), seed=3)
    reference = inpaint_unit(img)
    erase.guard_dilation(img, reference.mask, reference.info)
    old = erase.inpaint_all(img, erase.build_global_mask(img.shape[:2], [reference.mask], which="guarded"))
    result = erase_units(img, [inpaint_unit(img)])
    assert np.array_equal(result.erased, old) and result.engines == {"u01": "telea"}
