"""Fit / wrap, vertical column layout and rotated paste."""

import numpy as np
import pytest
from helpers import needs_cjk_font
from PIL import Image

from app.fonts import load_font, resolve_font
from app.geometry import UnitGeometry
from app.layout import (
    LINE_RATIO,
    VLayout,
    columns_available,
    fit_horizontal,
    fit_vertical,
    tokenize,
    vertical_glyphs,
    wrap_text,
)
from app.render import draw_vertical_mask, paste_unit, render_unit

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


def test_centered_roles_center_single_line_text_in_the_frame():
    geom = make_geom((200, 100), (300, 40), 0.0, glyph_h=24.0)
    left = render_unit("Hi", "horizontal", "en", "body", geom, bold=False)
    mid = render_unit("Hi", "horizontal", "en", "title", geom, bold=True)
    lx = np.nonzero(np.array(left.mask).sum(axis=0))[0]
    mx = np.nonzero(np.array(mid.mask).sum(axis=0))[0]
    assert lx.min() < 10
    assert abs((mx.min() + mx.max()) / 2 - 150) < 10
