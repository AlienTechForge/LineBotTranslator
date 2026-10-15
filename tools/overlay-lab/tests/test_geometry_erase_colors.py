"""Unit geometry, inpaint-mask building and colour picking."""

import cv2
import numpy as np
import pytest
from helpers import rect, rotate_poly, word

from app.colors import (
    estimate_stroke_ratio,
    fallback_text_color,
    pick_colors,
)
from app.erase import build_global_mask, build_unit_mask, dilation_px, inpaint_all
from app.geometry import compute_geometry

# ------------------------------------------------------------------ geometry


def test_horizontal_unit_frame():
    words = [word(1, "AAA", rect(100, 50, 180, 74)), word(2, "BBB", rect(190, 50, 260, 74))]
    g = compute_geometry(words, "horizontal")
    assert g.angle == 0.0
    assert g.glyph_h == pytest.approx(24.0)
    pad = 0.10 * 24
    assert g.size[0] == pytest.approx(160 + 2 * pad)
    assert g.size[1] == pytest.approx(24 + 2 * pad)
    assert g.center == pytest.approx((180.0, 62.0))
    assert g.bbox == (97, 47, 263, 77)


def test_vertical_column_of_upright_glyphs():
    words = [word(1, "縱", rect(300, 100, 340, 300))]
    g = compute_geometry(words, "vertical_rl")
    assert g.angle == 0.0
    assert g.size[1] > g.size[0]  # height >= width for a vertical unit
    assert g.glyph_h == pytest.approx(40.0)
    assert g.size[0] == pytest.approx(40 + 2 * 4.0)
    assert g.size[1] == pytest.approx(200 + 2 * 4.0)


def test_vertical_column_when_vision_orders_vertices_along_the_column():
    # p0 -> p1 runs down the column: the frame is turned back so that x is across the column
    poly = [(340, 100), (340, 300), (300, 300), (300, 100)]
    g = compute_geometry([word(1, "縱", poly)], "vertical_rl")
    assert g.angle == 0.0
    assert g.size[1] > g.size[0]
    assert g.size == pytest.approx((48.0, 208.0))


def test_unit_rotated_a_quarter_turn_keeps_its_own_frame():
    # a 100 x 20 line turned 90 degrees clockwise around (200, 200): reads downwards
    base = rect(150, 190, 250, 210)
    poly = rotate_poly(base, (200, 200), 90)
    g = compute_geometry([word(1, "HELLO", poly)], "horizontal", rotated=True)
    assert g.angle == pytest.approx(90.0)
    assert g.size[0] > g.size[1]  # width runs along the text
    pad = 0.10 * 20
    assert g.size == pytest.approx((100 + 2 * pad, 20 + 2 * pad))
    assert g.center == pytest.approx((200.0, 200.0), abs=0.6)
    x0, y0, x1, y1 = g.bbox
    assert (x1 - x0) < (y1 - y0)  # tall on the page


@pytest.mark.parametrize("deg", [15.0, -15.0, 30.0])
def test_unit_rotated_by_a_small_angle(deg):
    base = rect(100, 100, 300, 130)
    words = [
        word(1, "ONE", rotate_poly(rect(100, 100, 190, 130), (200, 115), deg)),
        word(2, "TWO", rotate_poly(rect(200, 100, 300, 130), (200, 115), deg)),
    ]
    g = compute_geometry(words, "horizontal", rotated=True)
    assert g.angle == pytest.approx(deg, abs=1.0)
    pad = 0.10 * 30
    assert g.size[0] == pytest.approx(200 + 2 * pad, abs=1.5)
    assert g.size[1] == pytest.approx(30 + 2 * pad, abs=1.5)
    assert g.center == pytest.approx((200.0, 115.0), abs=1.0)
    # all four word corners fall inside the frame polygon
    poly = g.corners.astype(np.float32)
    for w in words:
        for p in w.polygon:
            assert cv2.pointPolygonTest(poly, (float(p[0]), float(p[1])), True) > -0.5
    assert base  # (keeps the unrotated rectangle documented next to the rotated cases)


def test_frame_corners_follow_the_frame_axes():
    g = compute_geometry([word(1, "A", rotate_poly(rect(100, 100, 200, 130), (150, 115), 90))], "horizontal", True)
    tl, tr = g.corners[0], g.corners[1]
    # frame x axis points down the page for a 90 degree clockwise unit
    assert tr[1] > tl[1] and abs(tr[0] - tl[0]) < 1e-6


# ------------------------------------------------------------------ erase masks


def test_dilation_is_15_percent_of_glyph_height_with_a_2px_floor():
    assert dilation_px(10) == 2
    assert dilation_px(20) == 3
    assert dilation_px(100) == 15


def test_unit_mask_is_the_filled_polygon_dilated():
    w = word(1, "x", rect(20, 20, 60, 40))
    um = build_unit_mask((100, 100), [w], glyph_h=20.0)  # r = 3
    full_raw = np.zeros((100, 100), np.uint8)
    full_dil = np.zeros((100, 100), np.uint8)
    um.paste_into(full_raw, "raw")
    um.paste_into(full_dil, "dilated")
    assert full_raw[30, 40] == 255 and full_raw[30, 10] == 0
    assert full_dil[30, 17] == 255  # exactly r = 3 px left of the polygon edge (x = 20)
    assert full_dil[30, 16] == 0  # one pixel further: untouched
    assert full_dil[17, 40] == 255 and full_dil[16, 40] == 0  # same above the polygon
    assert np.count_nonzero(full_dil) > np.count_nonzero(full_raw)
    assert (full_dil[full_raw > 0] == 255).all()  # dilation contains the raw mask


def test_unit_mask_unions_all_member_words_and_clips_to_the_image():
    ws = [word(1, "a", rect(-5, 2, 20, 12)), word(2, "b", rect(40, 2, 70, 12))]
    um = build_unit_mask((30, 60), ws, glyph_h=10.0)
    canvas = np.zeros((30, 60), np.uint8)
    um.paste_into(canvas, "raw")
    assert canvas[5, 0] == 255 and canvas[5, 10] == 255 and canvas[5, 50] == 255
    assert canvas[5, 30] == 0
    assert canvas.shape == (30, 60)


def test_global_mask_is_one_union_and_protects_words_that_stay():
    shape = (60, 200)
    a = build_unit_mask(shape, [word(1, "a", rect(10, 20, 50, 40))], 20.0)
    b = build_unit_mask(shape, [word(2, "b", rect(100, 20, 140, 40))], 20.0)
    keep = build_unit_mask(shape, [word(3, "price", rect(52, 20, 90, 40))], 20.0, dilate=0)  # touches a's dilation
    mask = build_global_mask(shape, [a, b], [keep])
    assert mask[30, 30] == 255 and mask[30, 120] == 255  # both translated units
    assert mask[30, 51] == 255  # a's dilation outside the kept polygon survives
    assert mask[30, 70] == 0  # the kept word's own pixels are protected
    assert mask[5, 5] == 0


def test_protection_never_leaves_pixels_of_a_translated_polygon():
    shape = (40, 100)
    a = build_unit_mask(shape, [word(1, "a", rect(10, 10, 60, 30))], 20.0)
    overlap = build_unit_mask(shape, [word(2, "k", rect(50, 10, 90, 30))], 20.0, dilate=0)
    mask = build_global_mask(shape, [a], [overlap])
    assert mask[20, 55] == 255  # inside both polygons: translated wins
    assert mask[20, 80] == 0


def test_one_inpaint_call_erases_ink_to_the_background():
    bg = (245, 239, 224)
    img = np.full((80, 200, 3), bg, np.uint8)
    img[30:50, 30:120] = (20, 20, 20)
    shape = img.shape[:2]
    um = build_unit_mask(shape, [word(1, "x", rect(28, 28, 122, 52))], 24.0)
    mask = build_global_mask(shape, [um])
    out = inpaint_all(img, mask)
    assert np.abs(out.astype(int) - np.array(bg)).max() <= 12
    assert np.array_equal(inpaint_all(img, np.zeros(shape, np.uint8)), img)  # nothing to do -> copy


# ------------------------------------------------------------------ colours


def _scene(bg, ink, mask_rect=(20, 20, 140, 50), ink_share=0.35, seed=1):
    """Flat background with ink scattered over `ink_share` of the word polygon."""
    rng = np.random.default_rng(seed)
    h, w = 80, 200
    orig = np.full((h, w, 3), bg, np.uint8)
    x0, y0, x1, y1 = mask_rect
    region = rng.random((y1 - y0, x1 - x0)) < ink_share
    orig[y0:y1, x0:x1][region] = ink
    inpainted = np.full((h, w, 3), bg, np.uint8)
    word_ = word(1, "t", rect(x0, y0, x1 - 1, y1 - 1))
    um = build_unit_mask((h, w), [word_], 30.0)
    corners = np.array(rect(x0 - 3, y0 - 3, x1 + 3, y1 + 3), dtype=np.float64)
    return orig, inpainted, um, corners


def test_dark_ink_on_a_light_background():
    orig, inpainted, um, corners = _scene((240, 235, 220), (30, 30, 30))
    c = pick_colors(orig, inpainted, um, corners, glyph_h=30.0)
    assert not c.fallback
    assert c.text == pytest.approx((30, 30, 30), abs=3)
    assert c.background == (240, 235, 220)
    assert c.contrast > 300


def test_light_ink_on_a_dark_background():
    orig, inpainted, um, corners = _scene((20, 30, 60), (240, 240, 235))
    c = pick_colors(orig, inpainted, um, corners, glyph_h=30.0)
    assert not c.fallback
    assert c.text == pytest.approx((240, 240, 235), abs=3)
    assert c.background == (20, 30, 60)


def test_low_contrast_falls_back_to_black_on_light_and_white_on_dark():
    orig, inpainted, um, corners = _scene((210, 210, 210), (190, 190, 190))
    c = pick_colors(orig, inpainted, um, corners, glyph_h=30.0)
    assert c.fallback and c.text == (0, 0, 0)

    orig, inpainted, um, corners = _scene((40, 40, 40), (60, 60, 60))
    c = pick_colors(orig, inpainted, um, corners, glyph_h=30.0)
    assert c.fallback and c.text == (255, 255, 255)
    assert fallback_text_color((127, 127, 127)) == (255, 255, 255)
    assert fallback_text_color((128, 128, 128)) == (0, 0, 0)


def test_ink_fraction_is_tunable_and_defaults_to_40_percent(monkeypatch):
    from app.colors import ink_fraction

    assert ink_fraction() == 0.40
    monkeypatch.setenv("LAB_INK_FRACTION", "0.2")
    assert ink_fraction() == 0.2
    monkeypatch.setenv("LAB_INK_FRACTION", "7")
    assert ink_fraction() == 1.0
    monkeypatch.setenv("LAB_INK_FRACTION", "nonsense")
    assert ink_fraction() == 0.40


def test_smaller_ink_fraction_gives_a_darker_ink_on_antialiased_text(monkeypatch):
    # 12% solid ink pixels, 28% half-covered edge pixels: the default pool reaches into the edges
    rng = np.random.default_rng(5)
    bg, ink = (240, 240, 240), (20, 20, 20)
    h, w = 80, 200
    orig = np.full((h, w, 3), bg, np.uint8)
    roll = rng.random((30, 120))
    region = orig[20:50, 20:140]
    region[roll < 0.40] = (130, 130, 130)
    region[roll < 0.12] = ink
    inpainted = np.full((h, w, 3), bg, np.uint8)
    um = build_unit_mask((h, w), [word(1, "t", rect(20, 20, 139, 49))], 30.0)
    corners = np.array(rect(15, 15, 145, 55), dtype=np.float64)
    default = pick_colors(orig, inpainted, um, corners, glyph_h=30.0).text[0]
    monkeypatch.setenv("LAB_INK_FRACTION", "0.10")
    tight = pick_colors(orig, inpainted, um, corners, glyph_h=30.0).text[0]
    assert tight < default and tight <= 25


def test_background_is_sampled_from_the_inpainted_image_not_the_ink():
    orig, inpainted, um, corners = _scene((240, 235, 220), (30, 30, 30))
    inpainted[:] = (200, 210, 230)  # pretend the erased background looks different
    c = pick_colors(orig, inpainted, um, corners, glyph_h=30.0)
    assert c.background == (200, 210, 230)


def test_stroke_ratio_separates_bold_from_regular_ink():
    thin = np.zeros((60, 120), np.uint8)
    thick = np.zeros((60, 120), np.uint8)
    for x in range(10, 110, 20):
        cv2.line(thin, (x, 10), (x, 50), 255, 2)
        cv2.line(thick, (x, 10), (x, 50), 255, 9)
    r_thin = estimate_stroke_ratio(thin, glyph_h=40.0)
    r_thick = estimate_stroke_ratio(thick, glyph_h=40.0)
    assert r_thin < 0.18 <= r_thick
    assert estimate_stroke_ratio(np.zeros((10, 10), np.uint8), 20.0) is None
