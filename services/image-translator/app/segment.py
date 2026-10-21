"""Mode C, step 2: the translation units, from the geometry of the OCR alone (no model call).

Mode A asks Claude to group the OCR words into units and spent 132 s and 18k output tokens on a menu of 135
units; it also merged the single-letter badges `L` / `M` / `H` (white on a black bar) with the words next to
them. Google's image translation uses the layout analysis of its OCR engine instead (words, lines, paragraphs,
blocks, columns). `segment_units` does the same with the hierarchy Vision already returns (`Word.para`,
`Word.block`, `Word.brk`) and the pixels under the word boxes. It builds the same `ClaudeUnit` objects the
planner used to return, so `pipeline_a.render_units` draws them unchanged. The rules, in this order, each
with a named constant:

1. **Lines**: inside a paragraph a line ends at a word whose break is LINE_BREAK or EOL_SURE_SPACE (HYPHEN
   joins with the next line, without a space); a word whose centre is more than LINE_SPLIT_RATIO x glyph height
   from the line's baseline starts a new line too (Vision sometimes runs two lines together). The reading
   direction comes from the geometry: stacked tall narrow CJK words are `vertical_rl` (columns, right to
   left), a baseline turned more than ROTATED_MIN_DEG degrees is `rotated`.
2. **Columns**: inside a line, a gap larger than COLUMN_GAP_RATIO x the line's glyph height splits the line.
3. **Polarity**: each word is sampled (dominant colour = background, pixels farther than erase.INK_DISTANCE =
   ink): `dark_on_light` or `light_on_dark`. A word that differs from the majority of its line (by box area)
   is cut out into a unit of its own (the white `L` on the black badge next to `Light`).
4. **Merging lines into a unit**: consecutive lines of one paragraph (not split by 2 or 3) become one
   multi-line unit when their glyph heights are within LINE_HEIGHT_TOLERANCE, they share an alignment (left,
   centre or right edge within ALIGN_TOLERANCE_RATIO x glyph height of the paragraph box) and the gap between
   them is below LINE_GAP_RATIO x glyph height. Vertical columns are never merged.
5. **Keep**: a unit whose text is a price, number, quantity, time, date, code, single letter or digit, URL,
   e-mail, phone number or pure punctuation is `keep` (never translated, never erased: `render_units` leaves
   the words of a `kept` unit untouched and shields them from the erase of their neighbours).
6. **Group**: `b{block}-h{round(glyph height / GROUP_BUCKET_PX)}`: the units of one block with a similar size
   share a font size and a weight (the renderer's group machinery).
7. **Align, role, importance**: from the line edges against the paragraph box; `heading` when the glyph is
   more than HEADING_RATIO x the page median, `minor` when it is below MINOR_RATIO x the median.
8. **Reading order**: blocks top to bottom, left to right, with a column test (two blocks that overlap
   vertically and not horizontally: the left one first); the unit list is in that order.
9. `source_text` joins the words of a unit: no space around CJK (or Thai ...) text, a space between Latin words.

The `SegmentReport` counts what happened and gives the reasons per unit; it goes into the units JSON.
"""

from __future__ import annotations

import math
import re
import statistics
import unicodedata
from collections import Counter
from dataclasses import dataclass, field

import cv2
import numpy as np

from .erase import INK_DISTANCE, SECOND_COLOR_MIN_SHARE, _dominant_colors
from .geometry import baseline_angle
from .models import ClaudeUnit, OcrResult, Word

LINE_SPLIT_RATIO = 0.6  # a word farther than this x glyph height (across) from the line's baseline starts a new line
COLUMN_GAP_RATIO = 2.0  # a gap along the line larger than this x glyph height splits the line into columns
LINE_HEIGHT_TOLERANCE = 0.25  # lines merge when their glyph heights differ by at most this share
ALIGN_TOLERANCE_RATIO = 0.3  # an edge counts as aligned within this x glyph height of the paragraph box
LINE_GAP_RATIO = 1.0  # lines merge when the gap between them is below this x glyph height
HEADING_RATIO = 1.5  # glyph height above this x the page median = heading
MINOR_RATIO = 0.6  # glyph height below this x the page median = minor
GROUP_BUCKET_PX = 4  # units of one block whose glyph heights fall in the same bucket of this many px share a group
ROTATED_MIN_DEG = 5.0  # a baseline turned more than this from level is a rotated line
VERTICAL_ASPECT = 1.5  # a CJK word taller than this x its width belongs to a vertical column
MIN_POLARITY_PIXELS = 12  # a word box with fewer pixels than this says nothing about polarity
MIN_INK_SHARE = 0.02  # ... and neither does one with less ink than this
RING_SAMPLE_PX = 4  # the band around a word box that breaks the tie between two background candidates

LINE_ENDS = ("LINE_BREAK", "EOL_SURE_SPACE")
HYPHEN = "HYPHEN"
DARK_ON_LIGHT, LIGHT_ON_DARK, UNKNOWN = "dark_on_light", "light_on_dark", "unknown"

# ---------------------------------------------------------------- scripts and joining

_NO_SPACE_RANGES = (
    (0x2E80, 0x303F), (0x3040, 0x30FF), (0x3100, 0x312F), (0x31F0, 0x31FF), (0x3400, 0x4DBF), (0x4E00, 0x9FFF),
    (0xAC00, 0xD7AF), (0xF900, 0xFAFF), (0xFF00, 0xFFEF), (0x0E00, 0x0EFF), (0x0E80, 0x0EFF), (0x1780, 0x17FF),
    (0x1000, 0x109F), (0x20000, 0x2FA1F),
)  # CJK, kana, hangul, full-width forms, Thai, Lao, Khmer, Myanmar: scripts that are written without spaces
_CJK_RANGES = _NO_SPACE_RANGES[:9]


def _in(ch: str, ranges) -> bool:
    cp = ord(ch)
    return any(lo <= cp <= hi for lo, hi in ranges)


def is_spaceless(ch: str) -> bool:
    return bool(ch) and _in(ch, _NO_SPACE_RANGES)


def has_cjk(text: str) -> bool:
    return any(_in(ch, _CJK_RANGES) for ch in text)


_CLOSING = set(",.;:!?)]}%、。，；：！？）」』】》")
_OPENING = set("([{「『【《")


def join_words(words: list[Word]) -> str:
    """The text of some words in reading order: no space around CJK / Thai text, one between Latin words.

    A closing punctuation mark sticks to the word before it, an opening bracket to the word after it, and a
    word that ended on a HYPHEN break is joined to the next without a space.
    """
    out = ""
    prev: Word | None = None
    for word in words:
        text = " ".join(word.text.split())
        if not text:
            continue
        if prev is not None and out:
            last, first = out[-1], text[0]
            glue = (
                is_spaceless(last) or is_spaceless(first) or first in _CLOSING or last in _OPENING or prev.brk == HYPHEN
            )
            if not glue:
                out += " "
        out += text
        prev = word
    return out


# ---------------------------------------------------------------- keep patterns

_CUR = r"(?:NT\$|US\$|HK\$|[$¥€£₩₫฿₹₱]|RM|Rp|CHF|TWD|NTD|USD|JPY|THB|VND|KRW|EUR)"
_CUR_AFTER = r"(?:円|元|圓|원|yen|jpy|usd|ntd|twd|thb|vnd|krw|eur|đ|₫|kr|€|\$|¥|£)"
_NUM = r"(?:\d{1,3}(?:[,.\s]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)"
_UNIT = (
    r"(?:g|kg|mg|ml|cl|dl|l|oz|lb|lbs|cm|mm|m|km|min|mins|h|hr|hrs|sec|s|pcs|pc|pax|kcal|cal|%|°c|°f|℃|℉)"
)
_TIME = r"\d{1,2}:\d{2}(?::\d{2})?"
_DATE = r"\d{1,4}[/.\-]\d{1,2}(?:[/.\-]\d{1,4})?"
_RANGE = r"\s?[-–—~〜]\s?"
_FROM = r"\s?[~〜+*]?"  # "¥1,180~" (from this price), "¥1,180*"

KEEP_PATTERNS: tuple[tuple[str, re.Pattern], ...] = tuple(
    (kind, re.compile(pattern, re.IGNORECASE))
    for kind, pattern in (
        ("url", r"(?:https?://|www\.)\S+|[\w-]+(?:\.[\w-]+)*\.(?:com|net|org|edu|gov|io|co|jp|tw|cn|hk|kr|th|vn|sg|uk|de|fr|info|biz|shop|app)(?:[:/?#]\S*)?"),
        ("email", r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+"),
        ("datetime", rf"{_TIME}(?:{_RANGE}{_TIME})?|{_DATE}(?:{_RANGE}{_DATE})?"),
        ("price", rf"[(（]?(?:{_CUR}\s?{_NUM}|{_NUM}\s?{_CUR_AFTER}){_FROM}[)）]?"),
        ("quantity", rf"{_NUM}(?:{_RANGE}{_NUM})?\s?{_UNIT}"),
        ("number", r"\d+(?:[,.]\d+)*"),
        ("code", r"(?:no\.?|#)\s?\d+[a-z]?|[a-z]{1,3}[-–.]?\d{1,5}[a-z]?|\d{1,5}[-–][a-z]{1,3}"),
        ("letter", r"\W{0,2}[a-z0-9]\W{0,2}"),
        ("phone", r"\+?\(?\d[\d\s().-]{5,}\d"),
    )
)  # each pattern must match the whole text of a unit (re.fullmatch)


def keep_kind(text: str) -> str | None:
    """Why a unit with this text is never translated: price, number, quantity, datetime, code, letter, url, email,
    phone or punctuation; None for text that is to be translated. Full-width forms are folded first (NFKC)."""
    folded = unicodedata.normalize("NFKC", text).strip()
    if not folded:
        return None
    if not any(ch.isalnum() for ch in folded):
        return "punctuation"
    for kind, pattern in KEEP_PATTERNS:
        if pattern.fullmatch(folded):
            if kind == "phone" and sum(ch.isdigit() for ch in folded) < 7:
                continue
            return kind
    return None


# ---------------------------------------------------------------- polarity


def word_polarity(rgb: np.ndarray, word: Word) -> str:
    """dark_on_light, light_on_dark, or unknown (too few pixels, or no ink) for the pixels under one word box.

    The dominant colour of the box is its background and the pixels farther than INK_DISTANCE from it are the
    ink; when a second colour covers a good share of the box (bold text, a white letter on a black badge) the
    candidate closer to the band just outside the box is the background.
    """
    img_h, img_w = rgb.shape[:2]
    x, y, w, h = word.bbox
    x0, y0, x1, y1 = max(0, x), max(0, y), min(img_w, x + w), min(img_h, y + h)
    if x1 <= x0 or y1 <= y0:
        return UNKNOWN
    shape = np.zeros((y1 - y0, x1 - x0), np.uint8)
    poly = np.round(np.asarray(word.polygon, dtype=np.float64) - np.array([x0, y0])).astype(np.int32)
    cv2.fillPoly(shape, [poly], 255)
    pixels = rgb[y0:y1, x0:x1][shape > 0].astype(np.float32)
    if pixels.shape[0] < MIN_POLARITY_PIXELS:
        return UNKNOWN
    candidates = _dominant_colors(pixels)
    background = candidates[0][0]
    if len(candidates) > 1 and candidates[1][1] >= SECOND_COLOR_MIN_SHARE:
        rx0, ry0 = max(0, x0 - RING_SAMPLE_PX), max(0, y0 - RING_SAMPLE_PX)
        rx1, ry1 = min(img_w, x1 + RING_SAMPLE_PX), min(img_h, y1 + RING_SAMPLE_PX)
        around = rgb[ry0:ry1, rx0:rx1]
        outside = np.ones(around.shape[:2], bool)
        outside[y0 - ry0 : y1 - ry0, x0 - rx0 : x1 - rx0] = False
        band = around[outside]
        if band.shape[0] >= 8:
            ring = np.median(band.astype(np.float32), axis=0)
            background = min(candidates, key=lambda c: float(np.linalg.norm(c[0] - ring)))[0]
    ink = np.linalg.norm(pixels - background, axis=1) > INK_DISTANCE
    if int(ink.sum()) < max(3, MIN_INK_SHARE * pixels.shape[0]):
        return UNKNOWN
    weights = np.array([0.299, 0.587, 0.114], np.float32)
    ink_lum = float(np.median(pixels[ink], axis=0) @ weights)
    return DARK_ON_LIGHT if ink_lum < float(np.asarray(background) @ weights) else LIGHT_ON_DARK


# ---------------------------------------------------------------- the reading frame of a paragraph


@dataclass
class _Item:
    """A word placed in the reading frame of its paragraph."""

    word: Word
    a0: float  # extent along the reading direction
    a1: float
    c: float  # centre across it
    h: float  # glyph size: the extent across the reading direction
    polarity: str = UNKNOWN

    @property
    def area(self) -> float:
        return max(1.0, (self.a1 - self.a0) * self.h)


@dataclass
class _Line:
    items: list[_Item]
    pieces: list[list[_Item]] = field(default_factory=list)  # after the column and polarity splits (reading order)
    piece_reasons: list[list[str]] = field(default_factory=list)  # why each piece was cut out (parallel to `pieces`)
    column_splits: int = 0
    polarity_splits: int = 0

    @property
    def h(self) -> float:
        return float(statistics.median(i.h for i in self.items))

    @property
    def c(self) -> float:
        return float(statistics.median(i.c for i in self.items))

    @property
    def a0(self) -> float:
        return min(i.a0 for i in self.items)

    @property
    def a1(self) -> float:
        return max(i.a1 for i in self.items)


@dataclass
class _Para:
    key: tuple
    block: int | None
    para: int | None
    words: list[Word]
    direction: str = "horizontal"
    rotated: bool = False
    angle: float = 0.0
    lines: list[_Line] = field(default_factory=list)


def _aspect(word: Word) -> float:
    p = np.asarray(word.polygon, dtype=np.float64)
    along, across = float(np.linalg.norm(p[1] - p[0])), float(np.linalg.norm(p[2] - p[1]))
    return along / across if across > 1e-6 else 1.0


def _is_vertical(words: list[Word]) -> bool:
    """Stacked tall narrow CJK words (Vision gives upright columns a box taller than wide)."""
    cjk = [w for w in words if has_cjk(w.text)]
    if not cjk:
        return False
    tall = [w for w in cjk if len(w.text.strip()) >= 2 and _aspect(w) <= 1.0 / VERTICAL_ASPECT]
    if tall and 2 * len(tall) >= len(cjk):
        return True
    if len(cjk) >= 2:  # single characters, one under the other
        boxes = [np.asarray(w.polygon, dtype=np.float64) for w in cjk]
        cx = np.array([b[:, 0].mean() for b in boxes])
        cy = np.array([b[:, 1].mean() for b in boxes])
        glyph = float(statistics.median(w.box_height for w in cjk))
        return float(np.ptp(cx)) <= 0.5 * glyph and float(np.ptp(cy)) >= 0.9 * glyph * (len(cjk) - 1)
    return False


def _angle_off_level(deg: float) -> float:
    """How far a direction is from level (0 degrees): text that is upside down or on its side is far from it."""
    return abs((deg + 180.0) % 360.0 - 180.0)


def _frame(para: _Para) -> tuple[np.ndarray, np.ndarray]:
    """(along, across) unit vectors of the paragraph: horizontal text reads along +u and its lines stack down +v;
    vertical text reads down +u and its columns stack from right to left along +v."""
    theta = math.radians(para.angle)
    if para.direction == "horizontal":
        return np.array([math.cos(theta), math.sin(theta)]), np.array([-math.sin(theta), math.cos(theta)])
    return np.array([-math.sin(theta), math.cos(theta)]), np.array([-math.cos(theta), -math.sin(theta)])


def _place(word: Word, u: np.ndarray, v: np.ndarray) -> _Item:
    pts = np.asarray(word.polygon, dtype=np.float64)
    along, across = pts @ u, pts @ v
    # the glyph size is the extent across the reading direction (the height of a horizontal word, the width of a
    # vertical column), not the short side of the box: a narrow word such as "1" or "l" is taller than wide
    return _Item(word, float(along.min()), float(along.max()), float(across.mean()), max(1.0, float(across.max() - across.min())))


def _build_lines(para: _Para) -> None:
    """Rules 1 and 2: the lines of a paragraph (provider order, split by break type and by distance from the baseline)."""
    para.angle = baseline_angle(para.words)
    para.direction = "vertical_rl" if _is_vertical(para.words) else "horizontal"
    para.rotated = _angle_off_level(para.angle) > ROTATED_MIN_DEG
    u, v = _frame(para)
    lines: list[_Line] = []
    previous: Word | None = None
    for word in para.words:
        item = _place(word, u, v)
        if lines:
            line = lines[-1]
            ended = previous is not None and previous.brk in LINE_ENDS
            off = abs(item.c - line.c) > LINE_SPLIT_RATIO * line.h
            if not (ended or off):
                line.items.append(item)
                previous = word
                continue
        lines.append(_Line([item]))
        previous = word
    para.lines = lines


def _split_columns(line: _Line) -> list[list[_Item]]:
    """Rule 2: the line cut where the gap between two words exceeds COLUMN_GAP_RATIO x the glyph height.

    The words stay in the provider's order (which is the reading order, right to left for Arabic or Hebrew), so the
    gap is measured on whichever side the next word lies.
    """
    limit = COLUMN_GAP_RATIO * line.h
    cells: list[list[_Item]] = [[line.items[0]]]
    low, high = line.items[0].a0, line.items[0].a1
    for item in line.items[1:]:
        if max(item.a0 - high, low - item.a1) > limit:
            cells.append([item])
            low, high = item.a0, item.a1
        else:
            cells[-1].append(item)
            low, high = min(low, item.a0), max(high, item.a1)
    return cells


def _split_polarity(cell: list[_Item]) -> list[list[_Item]]:
    """Rule 3: runs of words whose polarity differs from the majority (by box area) are cut out of the cell."""
    if len(cell) < 2:
        return [cell]
    weight = {DARK_ON_LIGHT: 0.0, LIGHT_ON_DARK: 0.0}
    for item in cell:
        if item.polarity in weight:
            weight[item.polarity] += item.area
    if not any(weight.values()):
        return [cell]
    majority = DARK_ON_LIGHT if weight[DARK_ON_LIGHT] >= weight[LIGHT_ON_DARK] else LIGHT_ON_DARK
    runs: list[list[_Item]] = []
    last = None
    for item in cell:
        polarity = item.polarity if item.polarity != UNKNOWN else majority
        if runs and polarity == last:
            runs[-1].append(item)
        else:
            runs.append([item])
        last = polarity
    return runs


def _dominant(items: list[_Item]) -> str:
    weight = {DARK_ON_LIGHT: 0.0, LIGHT_ON_DARK: 0.0}
    for item in items:
        if item.polarity in weight:
            weight[item.polarity] += item.area
    if not any(weight.values()):
        return UNKNOWN
    return DARK_ON_LIGHT if weight[DARK_ON_LIGHT] >= weight[LIGHT_ON_DARK] else LIGHT_ON_DARK


# ---------------------------------------------------------------- merging lines, alignment


def _tags(a0: float, a1: float, h: float, box: tuple[float, float]) -> set[str]:
    tol = ALIGN_TOLERANCE_RATIO * h
    tags = set()
    if abs(a0 - box[0]) <= tol:
        tags.add("left")
    if abs(a1 - box[1]) <= tol:
        tags.add("right")
    if abs((a0 + a1) / 2.0 - (box[0] + box[1]) / 2.0) <= tol:
        tags.add("center")
    return tags


def _merge_reason(a: _Line, b: _Line, box: tuple[float, float]) -> tuple[bool, str]:
    """Rule 4 for two consecutive single-piece lines of one paragraph: (merge?, why / why not)."""
    ha, hb = a.h, b.h
    if abs(ha - hb) > LINE_HEIGHT_TOLERANCE * max(ha, hb):
        return False, "glyph heights differ"
    shared = _tags(a.a0, a.a1, ha, box) & _tags(b.a0, b.a1, hb, box)
    if not shared:
        return False, "no shared alignment"
    gap = (b.c - a.c) - (ha + hb) / 2.0
    if b.c <= a.c or gap >= LINE_GAP_RATIO * (ha + hb) / 2.0:
        return False, "gap too large"
    return True, f"same size, {'/'.join(sorted(shared))} aligned, gap {max(gap, 0.0) / ((ha + hb) / 2.0):.2f} h"


def _align_of(
    spans: list[tuple[float, float, float]], para_box: tuple[float, float], block_box: tuple[float, float] | None, lone: bool
) -> str:
    """The `align` of a unit: the edge its lines share with the paragraph box ("left" when nothing says otherwise).

    `spans` are the (start, end, glyph height) of its lines along the reading direction. A lone line (a paragraph
    of one line that is the whole unit) has nothing to be compared with in its own paragraph: the block it sits
    in, when that holds more, is measured instead.
    """
    box = block_box if lone and block_box is not None else para_box
    common: set[str] | None = None
    for a0, a1, h in spans:
        tags = _tags(a0, a1, h, box)
        common = tags if common is None else common & tags
    common = common or set()
    if "left" in common:
        return "left"
    if "center" in common:
        return "center"
    if "right" in common:
        return "right"
    return "left"


# ---------------------------------------------------------------- reading order of the blocks


def order_boxes(boxes: list[tuple[float, float, float, float]]) -> list[int]:
    """Indexes of `boxes` (x0, y0, x1, y1) in reading order: top to bottom, left to right.

    A box that overlaps another vertically and not horizontally is part of a column structure: the one on the
    left comes first, whatever their tops say.
    """
    remaining = sorted(range(len(boxes)), key=lambda i: (boxes[i][1], boxes[i][0]))
    order: list[int] = []

    def overlap(lo1: float, hi1: float, lo2: float, hi2: float) -> float:
        return min(hi1, hi2) - max(lo1, lo2)

    while remaining:
        pick = remaining[0]
        while True:
            x0, y0, x1, y1 = boxes[pick]
            left = [
                j for j in remaining
                if j != pick
                and overlap(boxes[j][1], boxes[j][3], y0, y1) > 0
                and boxes[j][2] <= x0
            ]  # fmt: skip
            if not left:
                break
            pick = min(left, key=lambda j: boxes[j][0])
        order.append(pick)
        remaining.remove(pick)
    return order


# ---------------------------------------------------------------- the report


@dataclass
class SegmentReport:
    words: int = 0
    paragraphs: int = 0
    lines: int = 0
    units: int = 0
    kept: int = 0
    polarity_splits: int = 0
    column_splits: int = 0
    merges: int = 0
    vertical: int = 0
    page_median_px: float = 0.0
    source_lang: str | None = None
    target_lang: str = ""
    reasons: list[dict] = field(default_factory=list)  # one entry per unit, in the order of the unit list

    def to_dict(self) -> dict:
        return {
            "words": self.words, "paragraphs": self.paragraphs, "lines": self.lines, "units": self.units,
            "kept": self.kept, "polarity_splits": self.polarity_splits, "column_splits": self.column_splits,
            "merges": self.merges, "vertical_units": self.vertical, "page_median_px": round(self.page_median_px, 1),
            "source_lang": self.source_lang, "target_lang": self.target_lang, "constants": CONSTANTS, "reasons": self.reasons,
        }  # fmt: skip


CONSTANTS = {
    "LINE_SPLIT_RATIO": LINE_SPLIT_RATIO, "COLUMN_GAP_RATIO": COLUMN_GAP_RATIO,
    "LINE_HEIGHT_TOLERANCE": LINE_HEIGHT_TOLERANCE, "ALIGN_TOLERANCE_RATIO": ALIGN_TOLERANCE_RATIO,
    "LINE_GAP_RATIO": LINE_GAP_RATIO, "HEADING_RATIO": HEADING_RATIO, "MINOR_RATIO": MINOR_RATIO,
}  # fmt: skip


def dominant_language(words: list[Word]) -> str | None:
    """The base language code most of the text is in (by characters, from Vision's per-word guess), else None."""
    count: Counter = Counter()
    for word in words:
        if word.lang:
            count[word.lang] += len(word.text.strip())
    return count.most_common(1)[0][0] if count else None


# ---------------------------------------------------------------- the whole segmentation


def segment_units(ocr: OcrResult, rgb: np.ndarray, lang: str) -> tuple[list[ClaudeUnit], SegmentReport]:
    """Layout units from the geometry of `ocr` and the pixels of `rgb`; `lang` (the target language) is recorded
    only: the layout of the source does not depend on it. Never calls a model and never raises on odd input."""
    words = [w for w in ocr.words if w.text.strip() and len(w.polygon) >= 3]
    report = SegmentReport(words=len(words))
    if not words:
        return [], report
    report.source_lang = dominant_language(words)
    report.target_lang = lang

    # paragraphs, in the order they first appear; words without paragraph info form one loose paragraph per block
    paras: dict[tuple, _Para] = {}
    for word in words:
        key = (word.block, word.para) if word.para is not None else (word.block, None)
        if key not in paras:
            paras[key] = _Para(key, word.block, word.para, [])
        paras[key].words.append(word)
    report.paragraphs = len(paras)

    polarity_cache: dict[str, str] = {}

    def polarity(item: _Item) -> str:
        if item.word.id not in polarity_cache:
            try:
                polarity_cache[item.word.id] = word_polarity(rgb, item.word)
            except Exception:  # noqa: BLE001 - a word that cannot be sampled has no polarity, nothing more
                polarity_cache[item.word.id] = UNKNOWN
        return polarity_cache[item.word.id]

    # blocks: the box of every block, for the reading order, the alignment of lone lines and the groups
    block_of: dict[tuple, tuple] = {}
    block_words: dict[tuple, list[Word]] = {}
    for key, para in paras.items():
        bkey = ("block", para.block) if para.block is not None else ("para", key)
        block_of[key] = bkey
        block_words.setdefault(bkey, []).extend(para.words)

    def bbox_of(ws: list[Word]) -> tuple[float, float, float, float]:
        pts = np.asarray([p for w in ws for p in w.polygon], dtype=np.float64)
        return float(pts[:, 0].min()), float(pts[:, 1].min()), float(pts[:, 0].max()), float(pts[:, 1].max())

    block_keys = list(block_words)
    block_rank = {block_keys[i]: r for r, i in enumerate(order_boxes([bbox_of(block_words[k]) for k in block_keys]))}

    for para in paras.values():
        _build_lines(para)
    report.page_median_px = float(statistics.median(i.h for para in paras.values() for ln in para.lines for i in ln.items))

    # build the units of every paragraph
    built: list[tuple[int, int, int, dict]] = []  # (block rank, paragraph order, unit order, unit info)
    for p_order, (key, para) in enumerate(paras.items()):
        u, _ = _frame(para)
        para_box = (min(i.a0 for ln in para.lines for i in ln.items), max(i.a1 for ln in para.lines for i in ln.items))
        bwords = block_words[block_of[key]]
        block_box = None
        if len(bwords) > len(para.words):  # the block holds more than this paragraph
            bpts = np.asarray([p for w in bwords for p in w.polygon], dtype=np.float64) @ u
            block_box = (float(bpts.min()), float(bpts.max()))
        report.lines += len(para.lines)
        vertical = para.direction != "horizontal"

        # rules 2 and 3: cut every line into pieces (columns, then words of the other polarity)
        for line in para.lines:
            for item in line.items:
                item.polarity = polarity(item)
            cells = _split_columns(line)
            line.pieces, line.piece_reasons = [], []
            for cell in cells:
                runs = _split_polarity(cell)
                for run in runs:
                    why = []
                    if len(cells) > 1:
                        why.append(f"column split: a gap above {COLUMN_GAP_RATIO:g} x the glyph height")
                    if len(runs) > 1:
                        why.append(f"polarity split: {_dominant(run)} against the rest of its line")
                    line.pieces.append(run)
                    line.piece_reasons.append(why)
                line.polarity_splits += len(runs) - 1
            line.column_splits = len(cells) - 1
            report.column_splits += line.column_splits
            report.polarity_splits += line.polarity_splits

        # rule 4: consecutive lines of one paragraph that were cut into nothing may become one unit
        groups: list[dict] = []
        for line in para.lines:
            if groups and len(line.pieces) == 1 and para.para is not None and not vertical:
                last = groups[-1]
                if last["whole_lines"]:
                    ok, why = _merge_reason(last["last_line"], line, para_box)
                    if ok:
                        last["pieces"].append(line.pieces[0])
                        last["spans"].append((line.a0, line.a1, line.h))
                        last["last_line"] = line
                        last["reasons"].append(f"merged line {len(last['pieces'])}: {why}")
                        report.merges += 1
                        continue
            whole = len(line.pieces) == 1
            for piece, why in zip(line.pieces, line.piece_reasons):
                spans = [(line.a0, line.a1, line.h)] if whole else [
                    (min(i.a0 for i in piece), max(i.a1 for i in piece), float(statistics.median(i.h for i in piece)))
                ]  # fmt: skip
                groups.append({
                    "pieces": [piece], "spans": spans, "reasons": list(why), "last_line": line,
                    "whole_lines": whole, "lone": whole and len(para.lines) == 1,
                })  # fmt: skip
        for u_order, group in enumerate(groups):
            items = [i for piece in group["pieces"] for i in piece]
            ws = [i.word for i in items]
            text = join_words(ws)
            built.append((block_rank[block_of[key]], p_order, u_order, {
                "words": ws, "text": text, "kind": keep_kind(text), "glyph": float(statistics.median(i.h for i in items)),
                "align": _align_of(group["spans"], para_box, block_box, group["lone"]), "polarity": _dominant(items),
                "direction": para.direction, "rotated": para.rotated, "block": para.block, "para": para.para,
                "lines": len(group["pieces"]) if group["whole_lines"] else 1, "reasons": group["reasons"],
            }))  # fmt: skip

    built.sort(key=lambda t: t[:3])
    units: list[ClaudeUnit] = []
    median = report.page_median_px
    for index, (_, _, _, info) in enumerate(built, start=1):
        glyph = info["glyph"]
        kind = info["kind"]
        role = "heading" if glyph > HEADING_RATIO * median else "other"
        if kind in ("price",):
            role = "price"
        elif kind in ("number", "quantity", "code"):
            role = "number"
        importance = "minor" if glyph < MINOR_RATIO * median else "major"
        group = f"b{info['block']}-h{int(round(glyph / GROUP_BUCKET_PX))}" if info["block"] is not None else ""
        text = info["text"]
        unit = ClaudeUnit(
            word_ids=[w.id for w in info["words"]],
            source_text=text,
            translation=text if kind else "",
            direction=info["direction"],
            rotated=info["rotated"],
            role=role,
            keep=bool(kind),
            notes=f"keep: {kind}" if kind else "",
            align=info["align"],
            weight="regular",
            importance=importance,
            group=group,
        )
        units.append(unit)
        reasons = list(info["reasons"])
        if info["direction"] != "horizontal":
            reasons.insert(0, "vertical column (stacked tall narrow CJK words)")
            report.vertical += 1
        if kind:
            reasons.append(f"kept: {kind}")
            report.kept += 1
        report.reasons.append({
            "id": f"u{index:02d}", "words": len(info["words"]), "lines": info["lines"], "block": info["block"],
            "para": info["para"], "polarity": info["polarity"], "keep": kind, "align": info["align"],
            "glyph_px": round(glyph, 1), "reasons": reasons,
        })  # fmt: skip
    report.units = len(units)
    return units, report
