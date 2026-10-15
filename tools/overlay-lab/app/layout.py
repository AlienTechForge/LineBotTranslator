"""Mode A, step 6 (part 1): wrap and fit translated text into a unit frame.

Everything here works in the unit's own frame (x across, y down), independent of any
rotation. Rendering lives in render.py.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Callable

MIN_FONT_PX = 10
LINE_RATIO = 1.2  # line pitch / font size for horizontal text
COLUMN_PITCH_RATIO = 1.2  # column pitch / font size for vertical CJK text
MAX_OVERFLOW = 0.30  # allowed overflow of the frame bottom (or column end) at the minimum size
SIZE_CAP_RATIO = 1.1  # largest font size = source glyph height x 1.1

NO_LINE_START = set("，。、．,.;:!?！？；：）)]}】」』〉》”’…・ー%")
VERTICAL_FORMS = {
    "，": "︐",
    "、": "︑",
    "。": "︒",
    "：": "︓",
    "；": "︔",
    "！": "︕",
    "？": "︖",
    "（": "︵",
    "）": "︶",
    "「": "﹁",
    "」": "﹂",
    "『": "﹃",
    "』": "﹄",
    "【": "︻",
    "】": "︼",
    "—": "︱",
    "…": "︙",
    "～": "︴",
    "~": "︴",
}


def is_cjk(ch: str) -> bool:
    cp = ord(ch)
    return (
        0x2E80 <= cp <= 0x2FDF
        or 0x3000 <= cp <= 0x30FF
        or 0x3100 <= cp <= 0x31FF
        or 0x3400 <= cp <= 0x4DBF
        or 0x4E00 <= cp <= 0x9FFF
        or 0xAC00 <= cp <= 0xD7AF
        or 0xF900 <= cp <= 0xFAFF
        or 0xFE30 <= cp <= 0xFE4F
        or 0xFF00 <= cp <= 0xFFEF
        or 0x20000 <= cp <= 0x2FA1F
    )


def contains_cjk(text: str) -> bool:
    return any(is_cjk(ch) for ch in text)


def tokenize(paragraph: str) -> list[str]:
    """Latin runs stay whole, every CJK glyph is its own token, single spaces are kept."""
    tokens: list[str] = []
    buf = ""
    for ch in paragraph:
        if ch.isspace():
            if buf:
                tokens.append(buf)
                buf = ""
            if not tokens or tokens[-1] != " ":
                tokens.append(" ")
        elif is_cjk(ch):
            if buf:
                tokens.append(buf)
                buf = ""
            tokens.append(ch)
        else:
            buf += ch
    if buf:
        tokens.append(buf)
    return tokens


def _hard_split(token: str, font, max_w: float) -> list[str]:
    chunks: list[str] = []
    cur = ""
    for ch in token:
        if cur and font.getlength(cur + ch) > max_w:
            chunks.append(cur)
            cur = ch
        else:
            cur += ch
    if cur:
        chunks.append(cur)
    return chunks or [""]


def wrap_text(text: str, font, max_w: float, hard_break: bool = False) -> list[str]:
    """Greedy wrap: word wrap for Latin, per-glyph wrap for CJK, simple kinsoku for closers.

    A single token wider than the line stays on its own (over-wide) line unless
    ``hard_break`` is set, in which case it is split between glyphs.
    """
    lines: list[str] = []
    for paragraph in text.replace("\r", "").split("\n"):
        paragraph = paragraph.strip()
        if not paragraph:
            continue
        cur = ""
        pending_space = False
        for tok in tokenize(paragraph):
            if tok == " ":
                pending_space = bool(cur)
                continue
            candidate = cur + (" " if pending_space and cur else "") + tok
            pending_space = False
            if not cur or font.getlength(candidate) <= max_w:
                cur = candidate
            elif tok in NO_LINE_START:
                cur = candidate  # closing punctuation hugs the previous glyph
            else:
                lines.append(cur)
                cur = tok
            if hard_break and font.getlength(cur) > max_w:
                chunks = _hard_split(cur, font, max_w)
                lines.extend(chunks[:-1])
                cur = chunks[-1]
        if cur:
            lines.append(cur)
    return lines


@dataclass
class HLayout:
    size: int
    lines: list[str]
    pitch: int
    fitted: bool  # fits the frame without any overflow
    overflowed: bool
    truncated: bool = False
    notes: list[str] = field(default_factory=list)

    @property
    def block_height(self) -> int:
        return len(self.lines) * self.pitch


FontForSize = Callable[[int], object]


def _ellipsize(line: str, font, max_w: float) -> str:
    line = line.rstrip()
    while line and font.getlength(line + "…") > max_w:
        line = line[:-1].rstrip()
    return line + "…"


def fit_horizontal(
    text: str,
    font_for_size: FontForSize,
    frame_w: float,
    frame_h: float,
    max_size: float,
    min_size: int = MIN_FONT_PX,
) -> HLayout:
    """Largest font size in [min_size, max_size] whose wrapped text fits (frame_w x frame_h).

    If nothing fits at the minimum size the text is laid out at that size and may overflow the
    frame bottom by at most 30% of the frame height (further lines are cut with an ellipsis).
    """
    hi = max(1, int(math.floor(max_size)))
    lo = min(min_size, hi)

    def attempt(size: int, hard: bool):
        font = font_for_size(size)
        lines = wrap_text(text, font, frame_w, hard_break=hard)
        pitch = max(1, int(round(size * LINE_RATIO)))
        widest = max((font.getlength(line) for line in lines), default=0.0)
        return font, lines, pitch, widest

    def fits(size: int) -> bool:
        _, lines, pitch, widest = attempt(size, False)
        return bool(lines) and widest <= frame_w + 0.5 and len(lines) * pitch <= frame_h + 0.5

    if not text.strip():
        return HLayout(lo, [], max(1, int(round(lo * LINE_RATIO))), True, False)

    if fits(hi):
        best = hi
    elif fits(lo):
        good, bad = lo, hi
        while bad - good > 1:
            mid = (good + bad) // 2
            if fits(mid):
                good = mid
            else:
                bad = mid
        best = good
    else:
        font, lines, pitch, _ = attempt(lo, True)
        limit = max(1, int(math.floor(frame_h * (1.0 + MAX_OVERFLOW) / pitch)))
        truncated = len(lines) > limit
        if truncated:
            lines = lines[:limit]
            lines[-1] = _ellipsize(lines[-1], font, frame_w)
        return HLayout(
            size=lo,
            lines=lines,
            pitch=pitch,
            fitted=False,
            overflowed=True,
            truncated=truncated,
            notes=["did not fit at the minimum size"],
        )
    _, lines, pitch, _ = attempt(best, False)
    return HLayout(best, lines, pitch, True, False)


# ------------------------------------------------------------------ vertical CJK


@dataclass
class VLayout:
    size: int
    columns: list[str]  # each string is one column read top to bottom
    pitch: int
    fitted: bool
    overflowed: bool
    truncated: bool = False
    notes: list[str] = field(default_factory=list)


def vertical_glyphs(text: str) -> list[list[str]]:
    """Paragraphs of single characters; spaces dropped, punctuation mapped to vertical forms."""
    paragraphs = []
    for para in text.replace("\r", "").split("\n"):
        chars = [VERTICAL_FORMS.get(ch, ch) for ch in para if not ch.isspace()]
        if chars:
            paragraphs.append(chars)
    return paragraphs


def _columns(paragraphs: list[list[str]], per_column: int) -> list[str]:
    per_column = max(1, per_column)
    cols: list[str] = []
    for chars in paragraphs:
        for i in range(0, len(chars), per_column):
            cols.append("".join(chars[i : i + per_column]))
    return cols


def columns_available(frame_w: float, size: int) -> int:
    """How many columns of cell width `size` and pitch 1.2 x size fit inside `frame_w`."""
    if frame_w < size:
        return 0
    return int(math.floor((frame_w - size) / (COLUMN_PITCH_RATIO * size))) + 1


def fit_vertical(
    text: str,
    frame_w: float,
    frame_h: float,
    max_size: float,
    min_size: int = MIN_FONT_PX,
) -> VLayout:
    """Upright CJK columns, first column at the right edge, column pitch 1.2 x size.

    The size is capped by the column width (frame_w) and by `max_size`. If it does not fit at
    the minimum size, columns may run 30% past the column end and spill past the left edge.
    """
    paragraphs = vertical_glyphs(text)
    hi = max(1, int(math.floor(min(max_size, frame_w))))
    lo = min(min_size, hi)

    def cols_for(size: int, length: float) -> list[str]:
        return _columns(paragraphs, int(math.floor(length / size)))

    def fits(size: int) -> bool:
        return len(cols_for(size, frame_h)) <= max(1, columns_available(frame_w, size))

    if not paragraphs:
        return VLayout(lo, [], int(round(lo * COLUMN_PITCH_RATIO)), True, False)
    if fits(hi):
        best = hi
    elif fits(lo):
        good, bad = lo, hi
        while bad - good > 1:
            mid = (good + bad) // 2
            if fits(mid):
                good = mid
            else:
                bad = mid
        best = good
    else:
        cols = cols_for(lo, frame_h * (1.0 + MAX_OVERFLOW))
        return VLayout(
            size=lo,
            columns=cols,
            pitch=int(round(lo * COLUMN_PITCH_RATIO)),
            fitted=False,
            overflowed=True,
            notes=["did not fit at the minimum size"],
        )
    return VLayout(best, cols_for(best, frame_h), int(round(best * COLUMN_PITCH_RATIO)), True, False)
