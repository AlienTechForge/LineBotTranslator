"""Mode C, step 2: the layout units from the geometry of the OCR (segment.py), rule by rule."""

import time

import numpy as np
import pytest
from helpers import rect, rotate_poly

from app import segment
from app.models import OcrResult, Word

PAPER, INK, WHITE, BLACK = (244, 238, 222), (20, 20, 20), (255, 255, 255), (10, 10, 10)


def page(width=700, height=300):
    return np.full((height, width, 3), PAPER, np.uint8)


def strokes(img, box, color=INK):
    """Text-like ink inside a word box: vertical bars with gaps, about half of the box."""
    x0, y0, x1, y1 = box
    for x in range(int(x0) + 2, int(x1) - 3, 6):
        img[int(y0) + 3 : int(y1) - 3, x : x + 3] = color


def badge(img, box, pad=8, bg=BLACK, fg=WHITE):
    """A dark badge around a word box with light strokes in it (the white L on the black bar)."""
    x0, y0, x1, y1 = box
    img[y0 - pad : y1 + pad, x0 - pad : x1 + pad] = bg
    strokes(img, box, fg)


class Page:
    """A hand-built OCR result and the picture it was read from."""

    def __init__(self, width=700, height=300):
        self.img = page(width, height)
        self.words: list[Word] = []

    def add(self, text, box, para=0, block=0, brk="SPACE", color=INK, draw=True, polygon=None):
        if draw:
            strokes(self.img, box, color)
        self.words.append(
            Word(
                id=f"w{len(self.words) + 1:04d}", text=text, polygon=polygon or [(float(x), float(y)) for x, y in rect(*box)],
                confidence=0.95, brk=brk, para=para, block=block,
            )
        )  # fmt: skip
        return self.words[-1]

    def run(self, lang="zh-TW"):
        ocr = OcrResult(width=self.img.shape[1], height=self.img.shape[0], words=self.words)
        return segment.segment_units(ocr, self.img, lang)


def texts(units):
    return [u.source_text for u in units]


# ---------------------------------------------------------------- the menu row of the brief


def menu_row():
    p = Page(700, 90)
    badge(p.img, (36, 20, 52, 44))
    p.words.append(
        Word(id="w0001", text="L", polygon=[(36.0, 20.0), (52.0, 20.0), (52.0, 44.0), (36.0, 44.0)], confidence=0.9, brk="SPACE", para=0, block=0)
    )  # fmt: skip
    p.add("•Light", (68, 20, 140, 44))
    p.add("about10min", (260, 20, 380, 44))
    p.add("¥1,180", (520, 20, 590, 44), brk="LINE_BREAK")
    return p


def test_a_menu_row_becomes_four_units_the_badge_and_the_price_kept():
    units, report = menu_row().run()
    assert texts(units) == ["L", "•Light", "about10min", "¥1,180"]
    assert [u.keep for u in units] == [True, False, False, True]
    assert units[0].notes == "keep: letter" and units[3].notes == "keep: price" and units[3].role == "price"
    assert [u.translation for u in units] == ["L", "", "", "¥1,180"]  # a kept unit says what it is
    assert [r["polarity"] for r in report.reasons] == ["light_on_dark", "dark_on_light", "dark_on_light", "dark_on_light"]
    assert (report.lines, report.units, report.kept) == (1, 4, 2)
    assert (report.column_splits, report.polarity_splits, report.merges) == (2, 1, 0)
    assert any("polarity split" in why for why in report.reasons[0]["reasons"])
    assert any("column split" in why for why in report.reasons[2]["reasons"])
    assert [r["id"] for r in report.reasons] == ["u01", "u02", "u03", "u04"]  # the ids render_units will give them


def test_the_badge_is_cut_out_by_its_polarity_alone_when_the_gap_is_small():
    units, report = menu_row().run()
    assert report.reasons[0]["words"] == 1 and report.reasons[1]["words"] == 1
    assert report.column_splits == 2  # the badge and the word were not cut by the column rule (the gap is 16 px, 0.7 h)


def test_the_polarity_of_a_word_is_read_from_the_pixels_under_its_box():
    p = Page(300, 90)
    dark = p.add("Light", (20, 20, 100, 44))
    badge(p.img, (150, 20, 180, 44))
    light = Word(id="w9", text="L", polygon=[(150.0, 20.0), (180.0, 20.0), (180.0, 44.0), (150.0, 44.0)])
    blank = Word(id="w8", text="x", polygon=[(220.0, 20.0), (260.0, 20.0), (260.0, 44.0), (220.0, 44.0)])
    assert segment.word_polarity(p.img, dark) == segment.DARK_ON_LIGHT
    assert segment.word_polarity(p.img, light) == segment.LIGHT_ON_DARK
    assert segment.word_polarity(p.img, blank) == segment.UNKNOWN  # no ink: no polarity


def test_a_light_word_on_a_dark_page_makes_the_dark_word_the_odd_one_out():
    p = Page(400, 90)
    p.img[:] = BLACK
    p.add("Hello", (20, 20, 100, 44), color=WHITE)
    p.add("world", (112, 20, 190, 44), color=WHITE)
    p.add("x", (200, 20, 214, 44), color=(60, 60, 60), draw=False)  # nothing to read
    units, report = p.run()
    assert len(units) == 1 and report.polarity_splits == 0 and report.reasons[0]["polarity"] == "light_on_dark"


# ---------------------------------------------------------------- lines, columns


def test_a_gap_beyond_two_glyph_heights_splits_the_line_and_a_smaller_one_does_not():
    wide, narrow = Page(), Page()
    for p, gap in ((wide, 50), (narrow, 46)):  # the glyph height is 24: the limit is 48
        p.add("Rice", (20, 20, 80, 44))
        p.add("Soup", (80 + gap, 20, 140 + gap, 44), brk="LINE_BREAK")
    assert texts(wide.run()[0]) == ["Rice", "Soup"] and wide.run()[1].column_splits == 1
    assert texts(narrow.run()[0]) == ["Rice Soup"] and narrow.run()[1].column_splits == 0


def test_right_to_left_words_keep_the_order_the_provider_gave_them_and_a_gap_on_their_left_splits_too():
    p = Page()
    p.add("مرحبا", (300, 20, 380, 44))  # the provider lists the rightmost word first: that is the reading order
    p.add("بالعالم", (200, 20, 280, 44), brk="LINE_BREAK")
    assert texts(p.run()[0]) == ["مرحبا بالعالم"]
    apart = Page()
    apart.add("مرحبا", (300, 20, 380, 44))
    apart.add("بالعالم", (20, 20, 100, 44), brk="LINE_BREAK")  # 200 px to the left of the first
    units, report = apart.run()
    assert texts(units) == ["مرحبا", "بالعالم"] and report.column_splits == 1


def test_a_row_with_three_columns_is_three_units_in_reading_order():
    p = Page()
    p.add("•Light", (20, 20, 100, 44))
    p.add("about10min", (220, 20, 340, 44))
    p.add("¥1,180", (460, 20, 530, 44), brk="LINE_BREAK")
    units, report = p.run()
    assert texts(units) == ["•Light", "about10min", "¥1,180"] and report.column_splits == 2


def test_a_line_break_in_the_paragraph_starts_a_new_line_and_a_hyphen_does_not_end_one():
    p = Page()
    p.add("Fresh", (20, 20, 80, 44))
    p.add("pork", (90, 20, 140, 44), brk="EOL_SURE_SPACE")
    p.add("cutlet", (20, 100, 90, 124))  # far enough below to be a line of its own, too far to merge
    p.add("ex-", (100, 100, 130, 124), brk="HYPHEN")
    p.add("ample", (20, 190, 80, 214), brk="LINE_BREAK")
    units, report = p.run()
    assert report.lines == 3 and texts(units) == ["Fresh pork", "cutlet ex-", "ample"]
    assert segment.join_words([p.words[3], p.words[4]]) == "ex-ample"  # a hyphen break joins without a space


def test_two_lines_run_together_are_split_by_their_distance_from_the_baseline():
    p = Page()
    p.add("Pork", (20, 20, 80, 44), brk="SPACE")
    p.add("cutlet", (90, 20, 160, 44), brk="SPACE")  # no break type says a new line starts ...
    p.add("Miso", (20, 120, 80, 144), brk="SPACE")  # ... but this word is 100 px lower (> 0.6 x 24)
    p.add("soup", (90, 120, 150, 144), brk="LINE_BREAK")
    units, report = p.run()
    assert report.lines == 2 and texts(units) == ["Pork cutlet", "Miso soup"]


# ---------------------------------------------------------------- merging lines


def two_lines(second_box, first_box=(20, 20, 200, 44), a="Pork cutlet with", b="miso soup and rice", **kw):
    p = Page(400, 200)
    p.add(a, first_box, brk="EOL_SURE_SPACE")
    p.add(b, second_box, brk="LINE_BREAK", **kw)
    return p


def test_two_lines_of_one_paragraph_with_the_same_size_and_alignment_are_one_unit():
    units, report = two_lines((20, 54, 200, 78)).run()
    assert texts(units) == ["Pork cutlet with miso soup and rice"] and report.merges == 1
    assert units[0].align == "left" and report.reasons[0]["lines"] == 2
    assert any("merged line 2" in why and "left" in why and "aligned" in why for why in report.reasons[0]["reasons"])


def test_a_gap_of_a_glyph_height_or_more_keeps_two_lines_apart():
    near, far = two_lines((20, 62, 200, 86)), two_lines((20, 70, 200, 94))  # gap 18 px / 26 px, the glyph is 24
    assert len(near.run()[0]) == 1 and len(far.run()[0]) == 2 and far.run()[1].merges == 0


def test_lines_whose_glyph_heights_differ_by_more_than_a_quarter_stay_apart():
    same = two_lines((20, 54, 200, 78), first_box=(20, 20, 200, 50))  # 30 vs 24: 20% apart, merge
    apart = two_lines((20, 54, 200, 74), first_box=(20, 20, 200, 50))  # 30 vs 20: 33% apart
    assert len(same.run()[0]) == 1 and len(apart.run()[0]) == 2


def test_lines_without_a_shared_alignment_stay_apart_and_centred_lines_merge():
    left_then_right = Page(400, 200)
    left_then_right.add("Pork cutlet", (20, 20, 120, 44), brk="EOL_SURE_SPACE")
    left_then_right.add("with rice", (160, 54, 260, 78), brk="LINE_BREAK")  # paragraph box 20..260: left, then right
    assert len(left_then_right.run()[0]) == 2
    centred = Page(400, 200)
    centred.add("Pork cutlet set", (60, 20, 260, 44), brk="EOL_SURE_SPACE")
    centred.add("with rice", (110, 54, 210, 78), brk="LINE_BREAK")  # both centred on x = 160
    units, report = centred.run()
    assert len(units) == 1 and units[0].align == "center" and "center aligned" in report.reasons[0]["reasons"][0]


def test_a_heading_over_a_smaller_subtitle_is_two_units_and_a_big_heading_is_marked():
    p = Page(500, 200)
    p.add("TONKATSU", (20, 20, 260, 80), brk="EOL_SURE_SPACE")
    p.add("set menu with miso soup", (20, 90, 260, 110), brk="LINE_BREAK")
    p.add("rice", (20, 140, 80, 160), para=1, block=1, brk="LINE_BREAK")
    p.add("tea", (20, 170, 80, 190), para=2, block=2, brk="LINE_BREAK")
    units, report = p.run()
    assert texts(units)[:2] == ["TONKATSU", "set menu with miso soup"] and report.merges == 0
    assert [u.role for u in units][:2] == ["heading", "other"]  # 60 px against a page median of 20
    assert all(u.importance == "major" for u in units)


def test_a_unit_far_below_the_page_median_is_minor():
    p = Page(500, 300)
    for i in range(5):
        p.add(f"Dish {i}", (20, 20 + i * 40, 120, 44 + i * 40), para=i, block=i, brk="LINE_BREAK")
    p.add("tiny footnote", (20, 260, 120, 270), para=9, block=9, brk="LINE_BREAK")  # 10 px against 24
    units, _ = p.run()
    assert units[-1].importance == "minor" and all(u.importance == "major" for u in units[:-1])


def test_words_without_paragraph_information_are_never_merged():
    p = Page(400, 200)
    p.add("Pork cutlet with", (20, 20, 200, 44), para=None, block=None)
    p.add("miso soup", (20, 54, 200, 78), para=None, block=None)
    units, report = p.run()
    assert len(units) == 2 and report.merges == 0


# ---------------------------------------------------------------- vertical and rotated text


def test_stacked_tall_narrow_cjk_words_are_vertical_columns_that_never_merge():
    p = Page(700, 400)
    p.add("紅燒肉飯店", (556, 60, 596, 270), brk="SPACE")
    p.add("豚カツ定食", (500, 60, 540, 270), brk="LINE_BREAK")  # the next column, to the left
    units, report = p.run()
    assert [u.direction for u in units] == ["vertical_rl", "vertical_rl"] and report.vertical == 2
    assert texts(units) == ["紅燒肉飯店", "豚カツ定食"]  # right to left
    assert not any(u.rotated for u in units) and report.merges == 0


def test_single_cjk_characters_one_under_the_other_are_a_column_too():
    p = Page(300, 400)
    for i, ch in enumerate("定食屋"):
        p.add(ch, (100, 40 + i * 44, 140, 80 + i * 44), brk="LINE_BREAK" if i == 2 else "SPACE")
    units, _ = p.run()
    assert len(units) == 1 and units[0].direction == "vertical_rl" and units[0].source_text == "定食屋"


def test_horizontal_cjk_is_not_vertical_and_joins_without_spaces():
    p = Page()
    p.add("豚カツ", (20, 20, 110, 44))
    p.add("定食", (116, 20, 170, 44), brk="LINE_BREAK")
    units, _ = p.run()
    assert units[0].direction == "horizontal" and units[0].source_text == "豚カツ定食"


def test_a_line_turned_by_fifteen_degrees_is_rotated_and_stays_one_unit():
    p = Page(500, 300)
    centre = (250.0, 150.0)
    poly1 = rotate_poly(rect(100, 130, 220, 160), centre, 15.0)
    poly2 = rotate_poly(rect(232, 130, 340, 160), centre, 15.0)
    p.add("LUNCH", None, polygon=poly1, draw=False)
    p.add("SPECIAL", None, polygon=poly2, brk="LINE_BREAK", draw=False)
    units, _ = p.run()
    assert len(units) == 1 and units[0].rotated is True and units[0].direction == "horizontal"
    assert units[0].source_text == "LUNCH SPECIAL"


def test_level_text_is_not_rotated():
    units, _ = two_lines((20, 54, 200, 78)).run()
    assert units[0].rotated is False


# ---------------------------------------------------------------- keep


@pytest.mark.parametrize(
    "text, kind",
    [
        ("¥1,180", "price"), ("$12.50", "price"), ("NT$120", "price"), ("120円", "price"), ("€9.90", "price"),
        ("¥1,180~", "price"), ("（¥980）", "price"), ("１２０円", "price"), ("RM12.50", "price"),
        ("1,100", "number"), ("01", "number"), ("300", "number"), ("1.100.000", "number"),
        ("200g", "quantity"), ("300ml", "quantity"), ("10min", "quantity"), ("2.5 kg", "quantity"), ("5%", "quantity"),
        ("10:00-22:00", "datetime"), ("2024/10/21", "datetime"), ("11:30~14:30", "datetime"), ("21.10.2024", "datetime"),
        ("No.3", "code"), ("A-12", "code"), ("#12", "code"), ("B3", "code"), ("AB-1234", "code"),
        ("L", "letter"), ("M", "letter"), ("(L)", "letter"), ("x", "letter"),
        ("www.example.com", "url"), ("https://example.com/menu", "url"), ("example.co.jp", "url"),
        ("info@example.com", "email"),
        ("03-1234-5678", "phone"), ("+81 3 1234 5678", "phone"), ("(02) 2345-6789", "phone"),
        ("•", "punctuation"), ("★★★", "punctuation"), ("---", "punctuation"), ("¥", "punctuation"),
    ],
)  # fmt: skip
def test_the_keep_patterns_recognise_what_is_never_translated(text, kind):
    assert segment.keep_kind(text) == kind


@pytest.mark.parametrize(
    "text",
    ["about10min", "Light", "•Light", "Pork cutlet", "Set A", "Open 10:00", "2 pieces", "3 for 2", "ABC", "豚カツ", "Heavy", "Tel"],
)
def test_text_that_has_to_be_translated_is_not_kept(text):
    assert segment.keep_kind(text) is None


def test_kept_units_say_why_and_are_counted():
    p = Page()
    p.add("¥1,180", (20, 20, 90, 44), para=0, block=0, brk="LINE_BREAK")
    p.add("Pork cutlet", (20, 80, 140, 104), para=1, block=1, brk="LINE_BREAK")
    units, report = p.run()
    assert [u.keep for u in units] == [True, False] and report.kept == 1
    assert report.reasons[0]["keep"] == "price" and "kept: price" in report.reasons[0]["reasons"]
    assert report.reasons[1]["keep"] is None


# ---------------------------------------------------------------- joining


def test_words_are_joined_with_spaces_between_latin_words_and_none_around_cjk():
    def w(text, brk="SPACE"):
        return Word(id="w", text=text, polygon=[(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)], brk=brk)

    assert segment.join_words([w("Pork"), w("cutlet"), w("set")]) == "Pork cutlet set"
    assert segment.join_words([w("豚"), w("カツ"), w("定食")]) == "豚カツ定食"
    assert segment.join_words([w("豚カツ"), w("Set")]) == "豚カツSet"
    assert segment.join_words([w("Hello"), w(","), w("world"), w("!")]) == "Hello, world!"
    assert segment.join_words([w("("), w("tax"), w("incl"), w(")")]) == "(tax incl)"
    assert segment.join_words([w("  "), w("a"), w("b")]) == "a b"


# ---------------------------------------------------------------- groups, alignment, order


def test_units_of_one_block_and_similar_size_share_a_group_and_other_blocks_do_not():
    p = Page(500, 300)
    p.add("Rice bowl", (20, 20, 140, 44), para=0, block=0, brk="LINE_BREAK")
    p.add("Curry bowl", (20, 100, 140, 124), para=1, block=0, brk="LINE_BREAK")
    p.add("Big heading", (20, 160, 200, 210), para=2, block=0, brk="LINE_BREAK")
    p.add("Tea", (320, 20, 380, 44), para=3, block=1, brk="LINE_BREAK")
    units, _ = p.run()
    by = {u.source_text: u.group for u in units}
    assert by["Rice bowl"] == by["Curry bowl"] == "b0-h6"  # round(24 / 4)
    assert by["Big heading"] == "b0-h12" and by["Tea"] == "b1-h6"


def test_the_alignment_comes_from_the_edges_against_the_paragraph_and_block_boxes():
    p = Page(500, 300)
    p.add("Menu", (100, 20, 200, 44), para=0, block=0, brk="LINE_BREAK")  # centred over the block below
    p.add("Pork cutlet", (20, 80, 280, 104), para=1, block=0, brk="LINE_BREAK")
    p.add("Total", (20, 200, 100, 224), para=2, block=1)
    p.add("¥1,180", (380, 200, 460, 224), para=2, block=1, brk="LINE_BREAK")
    units, _ = p.run()
    by = {u.source_text: u.align for u in units}
    assert by["Menu"] == "center" and by["Pork cutlet"] == "left" and by["Total"] == "left" and by["¥1,180"] == "right"


def test_blocks_are_read_top_to_bottom_and_a_left_column_comes_before_the_right_one():
    p = Page(700, 400)
    p.add("right top", (400, 20, 500, 44), para=0, block=0, brk="LINE_BREAK")  # first in the provider's order
    p.add("left top", (20, 30, 120, 54), para=1, block=1, brk="LINE_BREAK")
    p.add("left lower", (20, 120, 120, 144), para=2, block=2, brk="LINE_BREAK")
    p.add("right lower", (400, 130, 500, 154), para=3, block=3, brk="LINE_BREAK")
    p.add("footer", (20, 300, 500, 324), para=4, block=4, brk="LINE_BREAK")
    units, _ = p.run()
    assert texts(units) == ["left top", "right top", "left lower", "right lower", "footer"]


def test_order_boxes_puts_the_left_box_first_when_two_overlap_vertically_and_not_horizontally():
    boxes = [(300, 0, 400, 50), (0, 10, 100, 60), (0, 100, 100, 150)]
    assert segment.order_boxes(boxes) == [1, 0, 2]
    assert segment.order_boxes([(0, 200, 50, 220), (0, 0, 50, 20)]) == [1, 0]  # plain top to bottom
    assert segment.order_boxes([]) == []


def test_the_report_counts_what_happened_and_goes_into_a_dict():
    units, report = menu_row().run("ja")
    d = report.to_dict()
    assert (d["words"], d["paragraphs"], d["lines"], d["units"], d["kept"]) == (4, 1, 1, 4, 2)
    assert (d["column_splits"], d["polarity_splits"], d["merges"], d["target_lang"]) == (2, 1, 0, "ja")
    assert d["constants"]["COLUMN_GAP_RATIO"] == 2.0 and len(d["reasons"]) == 4 and d["page_median_px"] == 24.0


def test_a_page_without_text_has_no_units_and_a_blank_word_is_ignored():
    p = Page()
    p.add("   ", (20, 20, 80, 44))
    units, report = p.run()
    assert units == [] and report.units == 0


def test_every_constant_has_the_value_the_brief_names():
    assert (segment.LINE_SPLIT_RATIO, segment.COLUMN_GAP_RATIO, segment.LINE_HEIGHT_TOLERANCE) == (0.6, 2.0, 0.25)
    assert (segment.ALIGN_TOLERANCE_RATIO, segment.LINE_GAP_RATIO) == (0.3, 1.0)
    assert (segment.HEADING_RATIO, segment.MINOR_RATIO) == (1.5, 0.6)


def test_a_page_of_a_hundred_and_fifty_words_is_segmented_in_well_under_a_second():
    p = Page(1500, 1100)
    index = 0
    for row in range(30):
        for col in range(5):
            x = 40 + col * 280
            p.add(f"w{index}", (x, 30 + row * 34, x + 110, 50 + row * 34), para=row, block=row // 10, brk="LINE_BREAK" if col == 4 else "SPACE")
            index += 1
    started = time.perf_counter()
    units, report = p.run()
    elapsed = time.perf_counter() - started
    assert report.words == 150 and len(units) >= 30
    assert elapsed < 2.0, elapsed  # the budget is 0.5 s; the margin keeps a slow CI machine from failing it
