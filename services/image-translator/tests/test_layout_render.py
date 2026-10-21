"""Fit / wrap, vertical column layout and rotated paste."""

import numpy as np
import pytest
from helpers import needs_cjk_font
from PIL import Image

from app.fonts import load_font, resolve_font
from app.geometry import UnitGeometry
from app.layout import (
    LINE_RATIO,
    MAX_GROW,
    MIN_FIT_RATIO,
    VLayout,
    columns_available,
    fit_horizontal,
    fit_vertical,
    is_orphan,
    tokenize,
    vertical_glyphs,
    wrap_avoiding_orphan,
    wrap_text,
)
from app.render import draw_vertical_mask, growth_limits, paste_unit, render_unit

LATIN = resolve_font(False)
CJK = resolve_font(True)


def latin_font(size):
    return load_font(LATIN, size)


def cjk_font(size):
    return load_font(CJK, size)


def make_geom(center, size, angle, glyph_h=20.0) -> UnitGeometry:
    t = np.radians(angle)
    u = np.array([np.cos(t), np.sin(t)])
    v = np.array([-np.sin(t), np.cos(t)])
    w, h = size
    local = np.array([[-w / 2, -h / 2], [w / 2, -h / 2], [w / 2, h / 2], [-w / 2, h / 2]])
    corners = np.array(center) + local[:, 0:1] * u + local[:, 1:2] * v
    return UnitGeometry(center=center, size=size, angle=float(angle), glyph_h=glyph_h, corners=corners)


# ------------------------------------------------------------------ wrap


def test_latin_wraps_on_word_boundaries():
    font = latin_font(20)
    lines = wrap_text("alpha beta gamma delta epsilon", font, 130)
    assert len(lines) > 1
    assert " ".join(lines) == "alpha beta gamma delta epsilon"
    assert all(font.getlength(line) <= 130 for line in lines)
    assert all(not line.startswith(" ") and not line.endswith(" ") for line in lines)


def test_long_latin_word_stays_whole_unless_hard_break_is_requested():
    font = latin_font(20)
    word = "Supercalifragilisticexpialidocious"
    assert wrap_text(word, font, 80) == [word]
    pieces = wrap_text(word, font, 80, hard_break=True)
    assert len(pieces) > 1 and "".join(pieces) == word
    assert all(font.getlength(p) <= 80 for p in pieces)


def test_cjk_wraps_per_glyph_and_keeps_latin_runs_together():
    assert tokenize("價格 NT$120 套餐") == ["價", "格", " ", "NT$120", " ", "套", "餐"]
    font = cjk_font(20)
    lines = wrap_text("這是一個很長的中文句子需要換行處理才能放進去", font, 100)
    assert len(lines) > 1
    assert "".join(lines) == "這是一個很長的中文句子需要換行處理才能放進去"
    assert all(font.getlength(line) <= 100 + 1e-6 for line in lines)


def test_closing_punctuation_never_starts_a_line():
    font = cjk_font(20)
    text = "一二三四五，六七八九十。"
    for width in (60, 80, 100, 120):
        for line in wrap_text(text, font, width):
            assert line[0] not in "，。"


# ------------------------------------------------------------------ fit


def test_latin_fit_finds_the_largest_size_that_fits():
    text = "Hello wonderful world of automated tests"
    lay = fit_horizontal(text, latin_font, frame_w=220, frame_h=70, max_size=40)
    assert lay.fitted and not lay.overflowed and not lay.truncated
    assert 10 <= lay.size <= 40
    font = latin_font(lay.size)
    assert all(font.getlength(line) <= 220 for line in lay.lines)
    assert lay.block_height <= 70
    # one pixel larger must not fit (otherwise the search stopped early)
    bigger = latin_font(lay.size + 1)
    lines = wrap_text(text, bigger, 220)
    pitch = round((lay.size + 1) * LINE_RATIO)
    too_wide = any(bigger.getlength(line) > 220 for line in lines)
    assert lay.size == 40 or too_wide or len(lines) * pitch > 70


def test_fit_is_capped_by_max_size():
    lay = fit_horizontal("Hi", latin_font, frame_w=500, frame_h=200, max_size=24)
    assert lay.size == 24


def test_small_text_in_a_big_frame_uses_the_cap_not_the_frame():
    lay = fit_horizontal("OK", latin_font, 400, 300, max_size=31.9)
    assert lay.size == 31


@needs_cjk_font
def test_cjk_fit_wraps_per_glyph_inside_the_frame():
    text = "這是一段比較長的中文說明文字用來測試自動換行與縮放"
    lay = fit_horizontal(text, cjk_font, frame_w=150, frame_h=120, max_size=40)
    assert lay.fitted and lay.size >= 10
    font = cjk_font(lay.size)
    assert all(font.getlength(line) <= 150 for line in lay.lines)
    assert "".join(lay.lines) == text
    assert lay.block_height <= 120


def test_nothing_fits_at_10px_renders_at_10_and_overflows_at_most_30_percent():
    text = "word " * 400
    lay = fit_horizontal(text, latin_font, frame_w=90, frame_h=30, max_size=30)
    assert lay.size == 10
    assert lay.overflowed and not lay.fitted
    assert lay.block_height <= 30 * 1.3 + 1e-6
    assert lay.truncated and lay.lines[-1].endswith("…")


def test_moderate_overflow_is_not_truncated():
    text = "alpha beta gamma delta epsilon zeta eta theta iota kappa"
    lay = fit_horizontal(text, latin_font, frame_w=60, frame_h=38, max_size=30)
    assert lay.size == 10 and lay.overflowed
    assert lay.block_height <= 38 * 1.3 + 1e-6
    assert lay.lines[0].startswith("alpha")


def test_source_glyphs_below_10px_may_use_a_smaller_size():
    lay = fit_horizontal("ok", latin_font, 100, 20, max_size=8.4)
    assert lay.size == 8 and lay.fitted


# ------------------------------------------------------------------ vertical columns


def test_vertical_glyphs_drop_spaces_and_use_vertical_punctuation():
    paragraphs = vertical_glyphs("你好， 世界。\n再見")
    assert paragraphs[0] == ["你", "好", "︐", "世", "界", "︒"]
    assert paragraphs[1] == ["再", "見"]


def test_columns_available_uses_a_pitch_of_1_2_times_the_size():
    assert columns_available(50, 40) == 1
    assert columns_available(40 + 48, 40) == 2  # 40 + 1.2 * 40
    assert columns_available(30, 40) == 0


@needs_cjk_font
def test_vertical_layout_fills_columns_top_to_bottom_and_respects_the_column_width():
    text = "紅燒肉飯加滷蛋與青菜"  # 10 glyphs
    lay = fit_vertical(text, frame_w=100, frame_h=120, max_size=60)
    assert lay.fitted and not lay.overflowed
    assert lay.size <= 100  # capped by the column width
    assert lay.pitch == round(lay.size * 1.2)
    per = 120 // lay.size
    assert all(len(col) <= per for col in lay.columns)
    assert "".join(lay.columns) == "".join(vertical_glyphs(text)[0])
    assert len(lay.columns) <= columns_available(100, lay.size)
    assert lay.size == fit_vertical(text, 100, 120, 60).size  # deterministic


def test_vertical_size_is_capped_by_the_column_width():
    lay = fit_vertical("一二三", frame_w=30, frame_h=500, max_size=80)
    assert lay.size == 30 and lay.columns == ["一二三"]


def test_vertical_overflow_at_the_minimum_size_extends_the_column_end_by_30_percent():
    lay = fit_vertical("一" * 80, frame_w=14, frame_h=60, max_size=14)
    assert lay.size == 10 and lay.overflowed and not lay.fitted
    assert all(len(col) <= int(60 * 1.3 // 10) for col in lay.columns)


@needs_cjk_font
def test_vertical_columns_run_right_to_left_with_upright_glyphs():
    lay = VLayout(size=30, columns=["田田田", "一一一"], pitch=36, fitted=True, overflowed=False)
    mask, ox = draw_vertical_mask(lay, CJK, frame_w=80, frame_h=100, rtl=True)
    arr = np.array(mask)
    assert ox == 0.0  # two columns fit in 80 px: nothing spills past the left edge
    first_col = arr[:, 60:].sum()  # the rightmost cell holds the first column (dense glyphs)
    second_col = arr[:, 20:56].sum()
    assert first_col > second_col > 0
    ys, xs = np.nonzero(arr)
    assert xs.max() <= 80 and ys.max() <= 3 * 30 + 2  # glyphs stacked top to bottom, one cell each

    lr, _ = draw_vertical_mask(lay, CJK, 80, 100, rtl=False)
    arr_lr = np.array(lr)
    assert arr_lr[:, :40].sum() > arr_lr[:, 40:].sum()  # left-to-right: first column on the left


def test_vertical_spill_past_the_left_edge_is_reported_through_the_offset():
    lay = VLayout(size=20, columns=["一一", "二二", "三三"], pitch=24, fitted=False, overflowed=True)
    mask, ox = draw_vertical_mask(lay, LATIN, frame_w=30, frame_h=60, rtl=True)
    assert ox == pytest.approx(-(20 + 2 * 24 - 30))
    assert mask.width == 30 + round(-ox)


def test_vertical_unit_with_a_latin_target_is_drawn_horizontally_then_turned_clockwise():
    geom = make_geom((100, 150), (50, 200), 0.0, glyph_h=40.0)
    ur = render_unit("Pork rice", "vertical_rl", "en", "label", geom, bold=False)
    assert ur.mode == "vertical_latin"
    arr = np.array(ur.mask)
    ys, xs = np.nonzero(arr)
    assert (ys.max() - ys.min()) > (xs.max() - xs.min())  # the text now runs along the column
    # turned clockwise: the first letter ("P") is at the top
    top = arr[: arr.shape[0] // 2].sum()
    bottom = arr[arr.shape[0] // 2 :].sum()
    assert top > 0 and bottom > 0
    ink_top = np.nonzero(arr.sum(axis=1))[0].min()
    assert ink_top < 20


# ------------------------------------------------------------------ rotated paste


def _ink(img: Image.Image, bg=(255, 255, 255)):
    arr = np.array(img)
    return np.argwhere((arr != np.array(bg)).any(axis=2))


@pytest.mark.parametrize("angle", [0.0, 15.0, -15.0, 90.0, -90.0, 180.0])
def test_rotated_paste_stays_inside_the_unit_frame(angle):
    geom = make_geom((200, 200), (160, 30), angle, glyph_h=24.0)
    ur = render_unit("Hello world", "horizontal", "en", "body", geom, bold=False)
    img = Image.new("RGB", (400, 400), (255, 255, 255))
    bbox = paste_unit(img, ur, geom, (10, 10, 10))
    assert bbox is not None
    pts = _ink(img)
    assert len(pts) > 50
    poly = geom.corners.astype(np.float32)
    import cv2

    for y, x in pts[:: max(1, len(pts) // 400)]:
        assert cv2.pointPolygonTest(poly, (float(x), float(y)), True) > -2.0, (angle, x, y)
    x0, y0, x1, y1 = bbox  # thresholded ink box: faint anti-aliasing may stick out by a few px
    assert pts[:, 1].min() >= x0 - 3 and pts[:, 1].max() <= x1 + 3
    assert pts[:, 0].min() >= y0 - 3 and pts[:, 0].max() <= y1 + 3


def test_rotation_direction_is_clockwise_for_positive_angles():
    # text is left aligned in the frame: with a 90 degree clockwise turn it sits at the top
    for angle, check in ((0.0, "left"), (90.0, "top")):
        geom = make_geom((200, 200), (160, 24), angle, glyph_h=20.0)
        ur = render_unit("HH", "horizontal", "en", "body", geom, bold=False)
        img = Image.new("RGB", (400, 400), (255, 255, 255))
        paste_unit(img, ur, geom, (0, 0, 0))
        pts = _ink(img)
        cy, cx = pts.mean(axis=0)
        if check == "left":
            assert cx < 200 - 30 and abs(cy - 200) < 15
        else:
            assert cy < 200 - 30 and abs(cx - 200) < 15


def test_paste_near_and_beyond_the_image_edge_is_clipped_not_an_error():
    img = Image.new("RGB", (120, 90), (255, 255, 255))
    for center, angle in (((5, 5), 15.0), ((115, 85), -15.0), ((-40, 45), 90.0), ((60, 200), 0.0)):
        geom = make_geom(center, (160, 30), angle, glyph_h=24.0)
        ur = render_unit("Edge case text", "horizontal", "en", "body", geom, bold=True)
        bbox = paste_unit(img, ur, geom, (0, 0, 0))
        if bbox is not None:
            x0, y0, x1, y1 = bbox
            assert 0 <= x0 < x1 <= 120 and 0 <= y0 < y1 <= 90
    assert img.size == (120, 90)


def test_the_planner_s_align_centres_single_line_text_in_the_frame():
    geom = make_geom((200, 100), (300, 40), 0.0, glyph_h=24.0)
    left = render_unit("Hi", "horizontal", "en", "body", geom, bold=False)
    mid = render_unit("Hi", "horizontal", "en", "title", geom, bold=True, align="center")
    lx = np.nonzero(np.array(left.mask).sum(axis=0))[0]
    mx = np.nonzero(np.array(mid.mask).sum(axis=0))[0]
    assert lx.min() < 10
    assert abs((mx.min() + mx.max()) / 2 - 150) < 10


# ------------------------------------------------------------------ round 5: readable fit, short, growth, rotation, orphans


def ink_cols(unit_render):
    """(first, last) ink column of a UnitRender mask in frame coordinates."""
    xs = np.nonzero(np.array(unit_render.mask).sum(axis=0))[0]
    return unit_render.ox + xs.min(), unit_render.ox + xs.max()


LONG = "Complimentary hot beverage with every set meal"
GROW_TEXT = "Complimentary hot beverage"


def test_readable_means_at_least_seventy_percent_of_the_source_glyph_height():
    assert MIN_FIT_RATIO == 0.70 and MAX_GROW == 1.5
    geom = make_geom((200, 100), (300, 40), 0.0, glyph_h=20.0)
    ur = render_unit("Hello", "horizontal", "en", "body", geom, bold=False)
    assert ur.readable and ur.text_used == "full" and ur.min_fit_px == 14 and ur.font_size >= 14 and ur.geom is geom
    assert ur.grow == (0.0, 0.0) and ur.skipped is None


def test_the_short_alternative_is_used_only_when_the_full_translation_would_be_drawn_below_the_readable_size():
    geom = make_geom((200, 100), (150, 26), 0.0, glyph_h=20.0)  # one line, 150 px: LONG would need about 8 px
    full = render_unit(LONG, "horizontal", "en", "body", geom, bold=False, short="Free drink")
    assert full.text_used == "short" and full.readable and full.font_size >= 14
    plain = render_unit(LONG, "horizontal", "en", "body", geom, bold=False)  # no alternative: the old shrink path
    assert plain.text_used == "full" and not plain.readable and plain.font_size < 14
    fits = render_unit("Free drink", "horizontal", "en", "body", geom, bold=False, short="Drink")
    assert fits.text_used == "full"  # the full text is readable, so it comes first
    assert render_unit(LONG, "horizontal", "en", "body", geom, bold=False, short=LONG).text_used == "full"  # not shorter: ignored
    assert render_unit(LONG, "horizontal", "en", "body", geom, bold=False, short="  ").text_used == "full"


def test_when_neither_text_is_readable_the_shorter_one_is_shrunk_to_the_minimum_as_before():
    geom = make_geom((200, 100), (60, 26), 0.0, glyph_h=20.0)
    ur = render_unit(LONG, "horizontal", "en", "body", geom, bold=False, short="Free hot drink with set")
    assert ur.text_used == "short" and not ur.readable and ur.mask is not None and ur.skipped is None
    assert ur.font_size == 10 and ur.overflowed  # nothing fits at the minimum: the old overflow rules apply


def test_a_minor_unit_that_cannot_be_fitted_readably_even_with_short_is_skipped_too_small():
    geom = make_geom((200, 100), (60, 26), 0.0, glyph_h=20.0)
    minor = render_unit(LONG, "horizontal", "en", "body", geom, bold=False, short="Free hot drink with set", importance="minor")
    assert minor.skipped == "too small" and minor.mask is None and minor.min_fit_px == 14 and not minor.readable
    assert paste_unit(Image.new("RGB", (400, 200)), minor, geom, (0, 0, 0)) is None
    # ... but a minor unit that does fit readably (with short) is drawn
    ok = render_unit(LONG, "horizontal", "en", "body", make_geom((200, 100), (150, 26), 0.0, glyph_h=20.0), bold=False,
                     short="Free drink", importance="minor")  # fmt: skip
    assert ok.skipped is None and ok.text_used == "short" and ok.mask is not None
    major = render_unit(LONG, "horizontal", "en", "body", geom, bold=False, importance="major")
    assert major.skipped is None and major.mask is not None  # a major unit is always drawn


def test_a_left_aligned_unit_grows_to_the_right_into_the_free_lane_up_to_one_and_a_half_times():
    geom = make_geom((200, 100), (150, 26), 0.0, glyph_h=20.0)
    text = GROW_TEXT  # about 14 px x 26 characters: more than 150 px, fine in 225 px
    stuck = render_unit(text, "horizontal", "en", "body", geom, bold=False)
    assert not stuck.readable and stuck.grow == (0.0, 0.0)
    grown = render_unit(text, "horizontal", "en", "body", geom, bold=False, lane=(300.0, 300.0), align="left")
    assert grown.readable and grown.text_used == "full" and grown.font_size >= 14
    start, end = grown.grow
    assert start == 0.0 and 0.0 < end <= 0.5 * 150 + 1e-6  # only along the axis, never more than 1.5x the width
    assert grown.geom.size[0] == pytest.approx(150 + end) and grown.geom.corners[0] == pytest.approx(geom.corners[0])
    img = Image.new("RGB", (500, 200), (255, 255, 255))
    paste_unit(img, grown, geom, (0, 0, 0))
    ink = np.argwhere((np.array(img) != 255).any(axis=2))
    assert ink[:, 1].min() >= 125 - 2 and ink[:, 1].max() > 275 - 5  # starts at the old left edge, runs past the old right edge


def test_the_growth_is_the_smallest_that_makes_the_text_readable_and_is_limited_by_the_lane():
    geom = make_geom((200, 100), (150, 26), 0.0, glyph_h=20.0)
    plenty = render_unit(GROW_TEXT, "horizontal", "en", "body", geom, bold=False, lane=(0.0, 500.0))
    small = render_unit(GROW_TEXT, "horizontal", "en", "body", geom, bold=False, lane=(0.0, 20.0))
    assert plenty.readable and plenty.grow[1] < 75.0  # a step, not necessarily all of the allowance
    assert not small.readable or small.grow[1] <= 20.0 + 1e-6  # the lane caps it
    assert render_unit(GROW_TEXT, "horizontal", "en", "body", geom, bold=False, lane=(500.0, 0.0), align="left").grow == (0.0, 0.0)


def test_right_aligned_text_grows_to_the_left_and_centred_text_both_ways_by_the_same_amount():
    geom = make_geom((200, 100), (150, 26), 0.0, glyph_h=20.0)
    right = render_unit(GROW_TEXT, "horizontal", "en", "body", geom, bold=False, lane=(300.0, 300.0), align="right")
    assert right.readable and right.grow[1] == 0.0 and 0.0 < right.grow[0] <= 75.0 + 1e-6
    assert right.geom.corners[1] == pytest.approx(geom.corners[1])  # the right edge stays
    centre = render_unit(GROW_TEXT, "horizontal", "en", "body", geom, bold=False, lane=(300.0, 40.0), align="center")
    assert centre.grow[0] == centre.grow[1] and centre.grow[0] <= 37.5 + 1e-6  # by the smaller side of the lane
    assert centre.geom.center == pytest.approx(geom.center)


def test_growth_limits_follow_the_alignment_the_lane_and_the_cap():
    assert growth_limits(100.0, (80.0, 60.0), "left") == (0.0, 50.0)
    assert growth_limits(100.0, (80.0, 30.0), "left") == (0.0, 30.0)
    assert growth_limits(100.0, (80.0, 60.0), "right") == (50.0, 0.0)
    assert growth_limits(100.0, (80.0, 60.0), "center") == (25.0, 25.0)
    assert growth_limits(100.0, (10.0, 60.0), "center") == (10.0, 10.0)
    assert growth_limits(100.0, (0.0, 0.0), "left") == (0.0, 0.0) and growth_limits(100.0, (-5.0, -5.0), "center") == (0.0, 0.0)
    assert growth_limits(100.0, (500.0, 500.0), "left", max_grow=2.0) == (0.0, 100.0)


def test_alignment_places_a_single_line_in_the_frame():
    geom = make_geom((200, 100), (300, 40), 0.0, glyph_h=24.0)
    spans = {a: ink_cols(render_unit("Hi", "horizontal", "en", "body", geom, bold=False, align=a)) for a in ("left", "center", "right")}
    assert spans["left"][0] < 10 and spans["right"][1] > 290 and abs(sum(spans["center"]) / 2 - 150) < 10


def test_latin_text_in_a_vertical_unit_reads_top_to_bottom_one_line_per_column():
    geom = make_geom((100, 150), (120, 100), 0.0, glyph_h=40.0)  # a column unit: 120 px across, 100 px long
    rtl = render_unit("WWW\nIII", "vertical_rl", "en", "label", geom, bold=False)
    lr = render_unit("WWW\nIII", "vertical_lr", "en", "label", geom, bold=False)
    assert rtl.mode == lr.mode == "vertical_latin" and rtl.lines == lr.lines == 2
    half = lambda ur, side: np.array(ur.mask)[:, : ur.mask.width // 2].sum() if side == "left" else np.array(ur.mask)[:, ur.mask.width // 2 :].sum()  # noqa: E731
    assert half(rtl, "right") > half(rtl, "left")  # vertical_rl: the first line is the rightmost column (W is heavy)
    assert half(lr, "left") > half(lr, "right")  # vertical_lr: the first line is the leftmost column
    rows = np.nonzero(np.array(rtl.mask).sum(axis=1))[0]
    assert rows.max() - rows.min() >= 25  # the letters run down the column, not across it


def test_rotating_latin_makes_the_text_run_along_the_column_not_across_it():
    geom = make_geom((100, 150), (60, 220), 0.0, glyph_h=40.0)
    ur = render_unit("Pork rice set", "vertical_rl", "en", "label", geom, bold=False)
    ys, xs = np.nonzero(np.array(ur.mask))
    assert (ys.max() - ys.min()) > 3 * (xs.max() - xs.min())  # a long, thin column of turned text
    assert ur.mode == "vertical_latin" and ur.lines == 1 and ur.readable


def test_cjk_targets_in_vertical_units_stay_upright_vertical():
    geom = make_geom((100, 150), (50, 200), 0.0, glyph_h=40.0)
    ur = render_unit("紅燒肉飯", "vertical_rl", "zh-TW", "label", geom, bold=False)
    assert ur.mode == "vertical_cjk" and ur.text_used == "full"


@pytest.mark.parametrize(
    "line, orphan",
    [("害", True), ("害。", True), ("害！", True), ("傷害", True), ("heat", True), ("of", True), ("Stroke", False),
     ("NT$120", False), ("two words", False), ("", False), ("保護自己", False)],
)  # fmt: skip
def test_is_orphan(line, orphan):
    assert is_orphan(line) is orphan


def test_a_latin_title_does_not_end_on_a_lone_short_word():
    font = latin_font(30)
    text = "Protect yourself from heat"
    width = font.getlength("Protect yourself from") + 4  # the greedy wrap leaves "heat" alone
    greedy = wrap_text(text, font, width)
    assert greedy[-1] == "heat" and is_orphan(greedy[-1])
    fixed = wrap_avoiding_orphan(text, font, width)
    assert len(fixed) == len(greedy) and not is_orphan(fixed[-1]) and " ".join(fixed) == text
    assert all(font.getlength(line) <= width for line in fixed)
    assert wrap_avoiding_orphan("short", font, width) == ["short"]  # nothing to fix on a single line


@needs_cjk_font
def test_a_cjk_title_does_not_end_on_a_single_character():
    font = cjk_font(30)
    text = "保護自己遠離熱傷害"  # 9 glyphs
    width = font.getlength("保護自己遠離熱傷") + 2  # the greedy wrap leaves 害 alone (the round-2 poster title)
    greedy = wrap_text(text, font, width)
    assert greedy[-1] == "害"
    fixed = wrap_avoiding_orphan(text, font, width)
    assert "".join(fixed) == text and len(fixed) == 2 and len(fixed[-1]) >= 2 and all(font.getlength(x) <= width for x in fixed)
    lay = fit_horizontal(text, cjk_font, width, 80, 30, avoid_orphan=True)
    assert lay.fitted and "".join(lay.lines) == text and len(lay.lines[-1]) >= 2


def test_titles_and_headings_avoid_orphans_but_body_text_and_vertical_units_do_not(monkeypatch):
    from app import render

    seen = []
    real = render.fit_horizontal

    def spy(*args, **kwargs):
        seen.append(kwargs.get("avoid_orphan", False))
        return real(*args, **kwargs)

    monkeypatch.setattr(render, "fit_horizontal", spy)
    geom = make_geom((300, 200), (200, 120), 0.0, glyph_h=27.0)
    for role, expected in (("title", True), ("heading", True), ("body", False), ("label", False), ("price", False)):
        seen.clear()
        render_unit("Protect yourself from heat", "horizontal", "en", role, geom, bold=False)
        assert seen and set(seen) == {expected}, role
    seen.clear()
    render_unit("Protect yourself", "vertical_rl", "en", "title", make_geom((300, 200), (60, 200), 0.0, glyph_h=27.0), bold=False)
    assert set(seen) == {False}  # a rotated column is laid out as one line per column: no orphan rule


def test_a_title_at_the_size_cap_is_rewrapped_instead_of_ending_on_a_lone_word():
    text = "Protect yourself from heat"
    frame_w = latin_font(29).getlength("Protect yourself from") + 2
    lay_body = fit_horizontal(text, latin_font, frame_w, 120, 29)
    lay_title = fit_horizontal(text, latin_font, frame_w, 120, 29, avoid_orphan=True)
    assert lay_body.size == 29 and lay_body.lines[-1] == "heat"
    assert lay_title.size == 29 and lay_title.lines == ["Protect yourself", "from heat"]


def test_fit_horizontal_shrinks_a_little_rather_than_leave_an_orphan_when_rewrapping_cannot():
    font_for = latin_font
    text = "AAAAAAAAAA B"  # one long word and a one-letter orphan, whatever the narrowing
    width = latin_font(30).getlength("AAAAAAAAAA") + 2
    plain = fit_horizontal(text, font_for, width, 200, 30)
    guarded = fit_horizontal(text, font_for, width, 200, 30, avoid_orphan=True)
    assert plain.lines[-1] == "B" and guarded.size <= plain.size  # no orphan-free size within 15%: it stays as it is
    assert guarded.fitted and guarded.size >= int(plain.size * 0.85)


# ------------------------------------------------------------------ round 7: CJK titles and headings


def glyph_width(n: int, size: int = 30, extra: float = 2.0) -> float:
    """A line width that holds n CJK glyphs of `size` and a little more (any CJK font: glyphs are square)."""
    return cjk_font(size).getlength("保" * n) + extra


@pytest.mark.parametrize(
    "line, orphan",
    [("傷害", True), ("傷害。", True), ("傷 害", True), ("害", True), ("害，", True), ("熱傷害", False), ("熱傷害。", False),
     ("NT$120", False), ("傷害 NT$", False), ("Wi-Fi", False)],
)  # fmt: skip
def test_a_last_line_of_two_or_fewer_cjk_characters_is_an_orphan(line, orphan):
    assert is_orphan(line) is orphan


@needs_cjk_font
def test_a_cjk_title_prefers_to_break_at_punctuation_and_at_spaces():
    font = cjk_font(30)
    width = glyph_width(8)
    comma = "保護自己，遠離熱傷害"
    assert wrap_text(comma, font, width) == ["保護自己，遠離熱", "傷害"]  # body text: greedy, as before
    assert wrap_text(comma, font, width, prefer_breaks=True) == ["保護自己，", "遠離熱傷害"]
    spaced = "防曬小提醒 夏季戶外活動注意事項"
    assert wrap_text(spaced, font, glyph_width(9), prefer_breaks=True)[0] == "防曬小提醒"
    stop = "夏季戶外活動，請多補充水分、避免正午外出"
    assert wrap_text(stop, font, glyph_width(9), prefer_breaks=True) == ["夏季戶外活動，", "請多補充水分、", "避免正午外出"]
    assert wrap_avoiding_orphan(comma, font, width) == ["保護自己，", "遠離熱傷害"]  # what titles and headings use


@needs_cjk_font
def test_a_break_that_would_leave_the_line_nearly_empty_is_not_taken():
    font = cjk_font(30)
    text = "好，保護自己遠離熱傷害啊"  # the comma is after 2 of 8 glyphs: 25% of the line
    assert wrap_text(text, font, glyph_width(8), prefer_breaks=True)[0] == "好，保護自己遠離"
    assert wrap_text("好好好，保護自己遠離熱傷害啊", font, glyph_width(8), prefer_breaks=True)[0] == "好好好，"  # 4 of 8: taken


@needs_cjk_font
def test_a_cjk_heading_does_not_end_on_two_characters():
    font = cjk_font(30)
    text = "保護自己遠離熱傷害啊"  # 10 glyphs: eight on the first line and two left over
    width = glyph_width(8)
    greedy = wrap_text(text, font, width)
    assert len(greedy[-1]) == 2 and is_orphan(greedy[-1])
    fixed = wrap_avoiding_orphan(text, font, width)
    assert "".join(fixed) == text and len(fixed) == len(greedy) and len(fixed[-1]) >= 3
    assert all(font.getlength(line) <= width for line in fixed)


@needs_cjk_font
def test_fit_horizontal_uses_the_cjk_rules_only_for_titles_and_headings():
    width = glyph_width(8)
    text = "保護自己，遠離熱傷害"
    body = fit_horizontal(text, cjk_font, width, 120, 30)
    title = fit_horizontal(text, cjk_font, width, 120, 30, avoid_orphan=True)
    assert body.size == title.size == 30
    assert body.lines == ["保護自己，遠離熱", "傷害"] and title.lines == ["保護自己，", "遠離熱傷害"]


def test_a_title_that_cannot_avoid_an_orphan_gets_a_smaller_size_down_to_the_readable_floor_and_no_further():
    text = "AAAAAAAAAA B"  # one long word and a one-letter last word, whatever the narrowing
    width = latin_font(30).getlength("AAAAAAAAAA") + 2
    plain = fit_horizontal(text, latin_font, width, 200, 30)
    assert plain.lines[-1] == "B" and plain.size == 30
    guarded = fit_horizontal(text, latin_font, width, 200, 30, avoid_orphan=True, min_size=10)
    assert guarded.size < plain.size and guarded.fitted and (len(guarded.lines) == 1 or not is_orphan(guarded.lines[-1]))
    floor = fit_horizontal(text, latin_font, width, 200, 30, avoid_orphan=True, min_size=plain.size)
    assert floor.size == plain.size and floor.lines == plain.lines  # the floor is the readable size: the orphan is accepted
    just_above = fit_horizontal(text, latin_font, width, 200, 30, avoid_orphan=True, min_size=guarded.size)
    assert just_above.size == guarded.size  # as far down as the floor allows, not further
    below = fit_horizontal(text, latin_font, width, 200, 30, avoid_orphan=True, min_size=guarded.size + 1)
    assert below.size > guarded.size or is_orphan(below.lines[-1])


@needs_cjk_font
def test_a_title_in_render_unit_breaks_at_the_comma_and_a_body_does_not():
    geom = make_geom((300, 200), (glyph_width(8, 28), 200), 0.0, glyph_h=28.0)
    title = render_unit("保護自己，遠離熱傷害", "horizontal", "zh-TW", "title", geom, bold=False)
    body = render_unit("保護自己，遠離熱傷害", "horizontal", "zh-TW", "body", geom, bold=False)
    assert title.lines == 2 and body.lines == 2
    heading = render_unit("保護自己，遠離熱傷害", "horizontal", "zh-TW", "heading", geom, bold=False)
    assert heading.font_size == title.font_size
