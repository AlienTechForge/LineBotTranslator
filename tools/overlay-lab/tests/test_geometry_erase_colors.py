"""Unit geometry, inpaint-mask building and colour picking."""

import cv2
import numpy as np
import pytest
from helpers import rect, rotate_poly, word

from app import erase
from app.colors import (
    estimate_stroke_ratio,
    fallback_text_color,
    pick_colors,
)
from app.erase import (
    EraseUnit,
    build_global_mask,
    build_polygon_mask,
    build_unit_mask,
    dilation_px,
    erase_units,
    guard_dilation,
    inpaint_all,
    measure_background,
)
from app.geometry import compute_geometry, extension_polygons, free_lane

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


# ------------------------------------------------------------------ round 5: clean erase (flat fill / inpaint, colour guard)

WHITE, BLACK = (255, 255, 255), (0, 0, 0)


def table_scene(text_right=80, noise=0.0, seed=3):
    """A white cell (x < 84) with black text, a black price cell right of it (x >= 84) and a black rule below the text."""
    img = np.full((80, 140, 3), WHITE, np.uint8)
    img[:, 84:] = BLACK  # the price cell
    img[56:59, :84] = BLACK  # a table rule under the text
    img[22:42, 44:text_right][np.random.default_rng(seed).random((20, text_right - 44)) < 0.55] = BLACK  # the glyphs
    if noise:
        rng = np.random.default_rng(seed)
        out = img.astype(np.float32)
        out[:, :84] += rng.normal(0, noise, (80, 84, 1))
        img = np.clip(out, 0, 255).astype(np.uint8)
    return img


def speckle(img, yx, color, share=0.4, seed=0):
    """Glyph-like ink: `share` of the pixels of the (y0, y1, x0, x1) block painted `color`."""
    y0, y1, x0, x1 = yx
    img[y0:y1, x0:x1][np.random.default_rng(seed).random((y1 - y0, x1 - x0)) < share] = color


def textured_scene(rng, ink=(10, 10, 10), size=(60, 200)):
    """A steep horizontal gradient with strong noise (a photo-like texture) and glyph-like ink in the middle."""
    h, w = size
    ramp = np.linspace(40, 230, w)[None, :, None] + rng.normal(0, 22, (h, w, 1))
    img = np.clip(np.repeat(ramp, h, axis=0).repeat(3, axis=2), 0, 255).astype(np.uint8)
    img[20:40, 30 : min(w - 8, 120)][rng.random((20, min(w - 8, 120) - 30)) < 0.4] = ink
    return img


def unit_of(img, rect_xyxy, glyph_h=24.0, uid="u01"):
    x0, y0, x1, y1 = rect_xyxy
    um = build_unit_mask(img.shape[:2], [word(1, "x", rect(x0, y0, x1, y1))], glyph_h)
    return EraseUnit(uid, um, measure_background(img, um))


def test_a_white_cell_next_to_a_black_cell_comes_out_white_with_the_black_cell_untouched():
    img = table_scene()
    unit = unit_of(img, (42, 20, 82, 44))  # dilated by 4 px it would reach x = 86, into the black cell (x >= 84)
    assert unit.info.flat and unit.info.color == WHITE
    result = erase_units(img, [unit])
    out = result.erased
    assert result.methods == {"u01": "flat"}
    assert (out[:56, :84] >= 250).all(), "black left inside the white cell"  # no ink, no black pulled in
    assert (out[:, 84:] == 0).all() and (out[56:59, :84] == 0).all()  # the black cell and the rule are exactly as they were
    # the round-2 behaviour (unguarded dilation + one inpaint) on the same scene smears into the cell: the test would catch it
    old = inpaint_all(img, build_global_mask(img.shape[:2], [unit.mask]))
    assert (old[:, 84:] != 0).any() or (old[:56, :84] < 250).any()


def test_the_guard_keeps_the_dilation_out_of_neighbours_of_another_colour():
    img = table_scene()
    unit = unit_of(img, (42, 20, 82, 44))
    guard_dilation(img, unit.mask, unit.info)
    um = unit.mask
    full = np.zeros(img.shape[:2], np.uint8)
    um.paste_into(full, "guarded")
    dilated = np.zeros(img.shape[:2], np.uint8)
    um.paste_into(dilated, "dilated")
    own = np.zeros(img.shape[:2], np.uint8)
    um.paste_into(own, "raw")
    assert dilated[30, 85] == 255 and full[30, 85] == 0  # the plain dilation reaches the black cell, the guarded one does not
    assert full[30, 83] == 255  # ... but grows over white up to the cell
    black = (img == 0).all(axis=2)
    assert (full[black & (own == 0)] == 0).all()  # never over a black pixel outside the unit's own box
    assert (dilated[full > 0] == 255).all()  # the guarded mask is a part of the dilated one


def test_a_black_rule_just_below_the_text_survives_the_erase():
    img = table_scene()
    out = erase_units(img, [unit_of(img, (42, 20, 82, 53))]).erased  # the box and its dilation reach the rule at y = 56
    assert (out[56:59, :84] == 0).all() and (out[:56, :84] >= 250).all()


def test_a_flat_background_is_measured_on_the_non_ink_pixels_and_gets_a_flat_fill():
    img = np.full((60, 200, 3), (245, 239, 224), np.uint8)
    img[20:40, 30:120][np.random.default_rng(1).random((20, 90)) < 0.4] = (25, 25, 25)
    unit = unit_of(img, (28, 18, 122, 42))
    info = unit.info
    assert info.flat and info.color == (245, 239, 224) and info.spread < 1.0 and 0.2 < info.ink_share < 0.6
    assert info.method == "flat" and info.to_dict()["flat"] is True
    out = erase_units(img, [unit]).erased
    assert (out == np.array((245, 239, 224))).all()  # every glyph pixel is the background colour again


def test_a_slightly_noisy_paper_background_is_still_flat_and_a_textured_gradient_is_not():
    rng = np.random.default_rng(7)
    paper = np.clip(np.full((60, 200, 3), 240.0) + rng.normal(0, 5, (60, 200, 1)), 0, 255).astype(np.uint8)
    paper[20:40, 30:120][rng.random((20, 90)) < 0.4] = (25, 25, 25)
    assert unit_of(paper, (28, 18, 122, 42)).info.flat  # JPEG-like noise of a few levels
    textured = textured_scene(rng)
    info = unit_of(textured, (28, 18, 122, 42)).info
    assert not info.flat and info.method == "inpaint" and info.tolerance >= erase.GUARD_TOLERANCE


def test_a_textured_noisy_gradient_takes_the_inpaint_path_and_the_text_is_gone():
    img = textured_scene(np.random.default_rng(11), ink=(5, 5, 5))
    unit = unit_of(img, (28, 18, 122, 42))
    result = erase_units(img, [unit])
    assert result.methods == {"u01": "inpaint"} and result.mask_px["u01"] > 1000
    inside = result.erased[20:40, 30:120]
    assert inside.min() > 30  # the black glyphs are filled in from their surroundings (not left, not pure fill)
    assert abs(float(inside.mean()) - float(img[:18, 30:120].mean())) < 40  # and look like the neighbourhood
    assert np.array_equal(result.erased[:10], img[:10]) and np.array_equal(result.erased[:, :20], img[:, :20])  # nothing else changes


def test_the_inpaint_path_does_not_pull_a_neighbouring_black_cell_into_the_texture():
    rng = np.random.default_rng(5)
    img = textured_scene(rng, ink=(0, 0, 0), size=(60, 140))
    img[:, 100:] = 0  # a black cell
    unit = unit_of(img, (28, 18, 94, 42))
    assert unit.info.method == "inpaint"
    guard_dilation(img, unit.mask, unit.info)
    full = np.zeros(img.shape[:2], np.uint8)
    unit.mask.paste_into(full, "guarded")
    own = np.zeros(img.shape[:2], np.uint8)
    unit.mask.paste_into(own, "raw")
    near_cell = np.zeros(img.shape[:2], bool)
    near_cell[:, 100 - erase.INPAINT_RADIUS :] = True
    # beyond the unit's own box nothing within INPAINT_RADIUS of the black cell is erased: Telea would read it
    assert not ((full > 0) & (own == 0) & near_cell).any()
    assert (erase_units(img, [unit]).erased[:, 100:] == 0).all()


def test_dominant_colour_survives_bold_text_that_covers_most_of_the_box():
    img = np.full((50, 120, 3), WHITE, np.uint8)
    img[15:35, 20:100] = BLACK  # a heavy block of ink filling most of the word box
    um = build_unit_mask(img.shape[:2], [word(1, "x", rect(18, 13, 102, 37))], 24.0)
    info = measure_background(img, um)
    assert info.color == WHITE  # the ring around the box decides between the two candidates
    assert info.ink_share > erase.MAX_INK_SHARE and not info.flat  # more ink than background: not trusted as a flat fill


def test_words_that_stay_keep_their_pixels_even_inside_a_neighbour_s_dilation():
    img = np.full((60, 200, 3), WHITE, np.uint8)
    speckle(img, (20, 40, 20, 80), BLACK, seed=2)
    img[20:40, 84:140] = (200, 30, 30)  # a kept price right next to the translated word
    mine = unit_of(img, (18, 18, 82, 42), uid="u01")
    kept = build_unit_mask(img.shape[:2], [word(2, "NT$", rect(84, 18, 142, 42))], 24.0, dilate=0)
    result = erase_units(img, [mine], [kept])
    assert np.array_equal(result.erased[20:40, 84:140], img[20:40, 84:140])
    assert (result.erased[20:40, 20:80] == 255).all()


def test_the_unit_s_own_polygon_wins_over_a_protected_word_that_overlaps_it():
    img = np.full((50, 120, 3), WHITE, np.uint8)
    speckle(img, (15, 35, 20, 100), BLACK, seed=4)
    mine = unit_of(img, (18, 13, 102, 37))
    other = build_unit_mask(img.shape[:2], [word(2, "o", rect(60, 13, 110, 37))], 24.0, dilate=0)
    out = erase_units(img, [mine], [other]).erased
    assert (out[15:35, 60:100] == 255).all()  # inside this unit's polygon: erased though the other word covers it


def test_the_grown_part_of_a_frame_is_flat_filled_over_background_coloured_pixels_only():
    img = np.full((60, 200, 3), WHITE, np.uint8)
    speckle(img, (20, 40, 20, 80), BLACK, seed=6)
    img[:, 150:] = (30, 60, 200)  # something that is not the background at the end of the lane
    img[25, 100] = (250, 250, 250)  # faint marks inside the lane (still background-coloured)
    img[25, 101] = (225, 225, 225)
    unit = unit_of(img, (18, 18, 82, 42))
    unit.extension = build_polygon_mask(img.shape[:2], [np.array([[82, 18], [170, 18], [170, 42], [82, 42]], float)])
    result = erase_units(img, [unit])
    assert (result.erased[18:42, 82:150] == 255).all()  # the extension is erased too
    assert np.array_equal(result.erased[:, 150:], img[:, 150:])  # but never over the foreign colour
    assert result.mask_px["u01"] > int(np.count_nonzero(unit.mask.raw))


def test_feather_fades_the_fill_only_over_background_pixels():
    img = np.full((60, 140, 3), (240, 240, 240), np.uint8)
    img[:, 100:] = BLACK
    speckle(img, (20, 40, 30, 60), BLACK, seed=8)
    unit = unit_of(img, (28, 18, 62, 42))
    assert unit.info.flat
    out = erase_units(img, [unit]).erased
    assert (out[:, 100:] == 0).all()  # the black side is never touched by the feathered edge
    assert (np.abs(out[:, :100].astype(int) - 240) <= 1).all()


def test_pixels_of_a_unit_that_is_too_small_to_measure_do_not_crash():
    img = np.full((40, 40, 3), (10, 200, 10), np.uint8)
    um = build_unit_mask(img.shape[:2], [word(1, "x", rect(10, 10, 11, 11))], 2.0)
    info = measure_background(img, um)
    assert not info.flat or info.color == (10, 200, 10)
    erase_units(img, [EraseUnit("u", um, info)])


# ------------------------------------------------------------------ round 5: growing a frame, free lanes


def test_extended_grows_the_frame_along_its_axis_and_keeps_the_other_one():
    g = compute_geometry([word(1, "AB", rect(100, 50, 180, 74))], "horizontal")
    right = g.extended(0, 0.0, 40.0)
    assert right.size == pytest.approx((g.size[0] + 40.0, g.size[1])) and right.angle == g.angle
    assert right.corners[0] == pytest.approx(g.corners[0]) and right.corners[1][0] == pytest.approx(g.corners[1][0] + 40.0)
    left = g.extended(0, 30.0, 0.0)
    assert left.corners[1] == pytest.approx(g.corners[1]) and left.corners[0][0] == pytest.approx(g.corners[0][0] - 30.0)
    both = g.extended(0, 10.0, 10.0)
    assert both.center == pytest.approx(g.center) and both.size[0] == pytest.approx(g.size[0] + 20.0)
    down = g.extended(1, 0.0, 12.0)
    assert down.size == pytest.approx((g.size[0], g.size[1] + 12.0)) and g.extended(0, 0.0, 0.0) is g


def test_a_turned_frame_grows_along_its_own_reading_axis():
    poly = rotate_poly(rect(150, 190, 250, 210), (200, 200), 90)  # reads downwards
    g = compute_geometry([word(1, "HELLO", poly)], "horizontal", rotated=True)
    grown = g.extended(0, 0.0, 50.0)
    xs = [c[0] for c in grown.corners]
    ys = [c[1] for c in grown.corners]
    assert max(ys) - max(c[1] for c in g.corners) == pytest.approx(50.0, abs=1e-6)  # +x of the frame is down the page
    assert (max(xs), min(xs)) == pytest.approx((max(c[0] for c in g.corners), min(c[0] for c in g.corners)))


def frame_at(x0, y0, x1, y1):
    return compute_geometry([word(1, "A", rect(x0, y0, x1, y1))], "horizontal")


def test_free_lane_stops_at_the_next_unit_the_image_edge_and_keeps_a_gap():
    g = frame_at(100, 50, 180, 74)  # glyph height 24: the gap is 6 px
    neighbour = rect(260, 48, 320, 76)
    against, along = free_lane(g, 0, [np.array(neighbour, float)], (200, 400))
    assert along == pytest.approx(260 - (180 + 2.4) - 6.0, abs=1.0)  # frame edge at 182.4, neighbour at 260, gap 6
    assert against == pytest.approx(100 - 2.4 - 2.0, abs=1.0)  # nothing on the left: the image edge, 2 px margin
    wide_open = free_lane(g, 0, [], (200, 400))
    assert wide_open[1] == pytest.approx(400 - 182.4 - 2.0, abs=1.0)


def test_an_obstacle_beside_the_lane_does_not_block_it_but_one_in_it_does():
    g = frame_at(100, 50, 180, 74)
    above = np.array(rect(190, 10, 400, 40), float)  # a line above, not overlapping the frame's height
    assert free_lane(g, 0, [above], (200, 400))[1] > 150
    in_lane = np.array(rect(230, 60, 300, 90), float)  # overlaps the lane's height
    assert free_lane(g, 0, [in_lane], (200, 400))[1] == pytest.approx(230 - 182.4 - 6.0, abs=1.0)
    on_the_left = np.array(rect(20, 50, 80, 74), float)
    assert free_lane(g, 0, [on_the_left], (200, 400))[0] == pytest.approx(97.6 - 80 - 6.0, abs=1.0)


def test_an_obstacle_that_already_overlaps_the_frame_blocks_its_side_completely():
    g = frame_at(100, 50, 180, 74)
    assert free_lane(g, 0, [np.array(rect(170, 50, 230, 74), float)], (200, 400))[1] == 0.0
    assert free_lane(g, 0, [np.array(rect(60, 50, 110, 74), float)], (200, 400))[0] == 0.0


def test_the_lane_of_a_vertical_unit_runs_down_the_column_and_a_turned_frame_is_swept_along_its_axis():
    column = compute_geometry([word(1, "縱", rect(300, 100, 340, 200))], "vertical_rl")
    below = np.array(rect(296, 260, 344, 330), float)
    against, along = free_lane(column, 1, [below], (400, 500))
    assert along == pytest.approx(260 - (200 + 4.0) - 10.0, abs=1.0) and against == pytest.approx(100 - 4.0 - 2.0, abs=1.0)
    turned = compute_geometry([word(1, "A", rotate_poly(rect(100, 200, 200, 220), (150, 210), 90))], "horizontal", rotated=True)
    lane = free_lane(turned, 0, [], (400, 400))
    assert lane[1] == pytest.approx(400 - max(c[1] for c in turned.corners) - 2.0, abs=1.0)  # +x runs down the page


def test_extension_polygons_are_the_strips_added_by_the_growth():
    g = frame_at(100, 50, 180, 74)
    (right,) = extension_polygons(g, 0, 0.0, 40.0)
    assert right[:, 0].min() == pytest.approx(g.corners[1][0]) and right[:, 0].max() == pytest.approx(g.corners[1][0] + 40.0)
    both = extension_polygons(g, 0, 10.0, 20.0)
    assert len(both) == 2 and extension_polygons(g, 0, 0.0, 0.0) == []
    mask = build_polygon_mask((200, 400), both)
    assert mask is not None and np.count_nonzero(mask.raw) >= 0.9 * 30 * g.size[1]


def test_a_polygon_mask_outside_the_image_is_none():
    assert build_polygon_mask((50, 50), [np.array([[100, 100], [120, 100], [120, 120], [100, 120]], float)]) is None
    assert build_polygon_mask((50, 50), []) is None
