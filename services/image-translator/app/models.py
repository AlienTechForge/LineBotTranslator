"""Shared data models."""

from __future__ import annotations

import math
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

Direction = Literal["horizontal", "vertical_rl", "vertical_lr"]
Role = Literal["title", "heading", "body", "label", "price", "number", "other"]
Align = Literal["left", "center", "right"]
Weight = Literal["regular", "bold"]
Importance = Literal["major", "minor"]


def base_language(code: str | None) -> str | None:
    """'zh-Hant' -> 'zh', 'JA' -> 'ja', 'und' / '' / None -> None."""
    if not code:
        return None
    base = code.strip().replace("_", "-").split("-")[0].lower()
    return None if base in ("", "und", "zxx", "mul") else base


def polygon_height(polygon: list[tuple[float, float]]) -> float:
    """Height of a word box, from its polygon: the SHORT side of the (rotated) rectangle.

    Vision orders the vertices in the text's own frame, so the edge lengths do not depend on how
    the page is turned. The short side is the glyph height of horizontal text and, for a column of
    upright vertical glyphs (a tall, narrow box), the glyph width: in both cases the size of one
    character, which is what the "big leftover word" rule compares.
    """
    if len(polygon) < 3:
        return 0.0
    pts = list(polygon[:4])
    while len(pts) < 4:
        pts.append(pts[-1])
    (x0, y0), (x1, y1), (x2, y2), (x3, y3) = pts
    across = (math.hypot(x1 - x0, y1 - y0) + math.hypot(x2 - x3, y2 - y3)) / 2.0
    down = (math.hypot(x3 - x0, y3 - y0) + math.hypot(x2 - x1, y2 - y1)) / 2.0
    return min(across, down)


class ModeError(RuntimeError):
    """A failure inside one mode; shown in that mode's error box, never fatal for the other mode."""


class Word(BaseModel):
    id: str
    text: str
    polygon: list[tuple[float, float]]  # 4 points, provider order kept
    confidence: float = 0.0
    brk: str | None = None  # detected break after the word (SPACE, LINE_BREAK, ...)
    para: int | None = None  # provider paragraph index (JSON panel only)
    # Vision's own guesses for this word, (base code, confidence), best first; empty when it gave none
    detected_languages: list[tuple[str, float]] = Field(default_factory=list)
    # the language used by the Mode B checks: the best of `detected_languages`, else the one of the
    # word's paragraph, block or page (base code, e.g. "ja"); None when Vision said nothing at all
    lang: str | None = None
    # box height in px (see polygon_height); filled by vision.ocr_words, 0.0 = not recorded
    height: float = 0.0

    @property
    def box_height(self) -> float:
        """`height` as recorded by Vision, else computed from the polygon."""
        return self.height if self.height > 0 else polygon_height(self.polygon)

    @property
    def bbox(self) -> tuple[int, int, int, int]:
        """Axis-aligned bbox as (x, y, w, h) in integer pixels."""
        xs = [p[0] for p in self.polygon]
        ys = [p[1] for p in self.polygon]
        x0, y0 = int(min(xs)), int(min(ys))
        x1, y1 = int(round(max(xs))), int(round(max(ys)))
        return x0, y0, max(1, x1 - x0), max(1, y1 - y0)


class OcrResult(BaseModel):
    width: int
    height: int
    words: list[Word]
    text: str = ""  # Vision's full text (line breaks kept); only filled by vision.ocr_document


class ClaudeUnit(BaseModel):
    """One translation unit as the planner returns it (it never gives coordinates).

    Unknown fields are rejected (the JSON schema forbids them too), enums are checked. The fields
    added in rounds 5 and 6 have defaults only so that a hand-built unit stays short in code.
    """

    model_config = ConfigDict(extra="forbid")

    word_ids: list[str]
    source_text: str = ""
    translation: str = ""
    direction: Direction = "horizontal"
    rotated: bool = False
    role: Role = "other"
    keep: bool = False
    notes: str = ""
    short: str = ""  # a shorter alternative translation, "" when the translation is short already
    align: Align = "left"  # how the source text is set
    weight: Weight = "regular"  # how heavy the source text looks
    importance: Importance = "major"  # minor: decoration, footnotes, logo lettering
    # round 6: a short id shared by the units of one table column, list or run of labels ("" = stands alone);
    # the renderer gives every unit of a group one font size and one weight
    group: str = ""

    @field_validator("group", mode="after")
    @classmethod
    def _tidy_group(cls, value: str) -> str:
        return " ".join(value.split())[:40]


class ClaudeUnits(BaseModel):
    model_config = ConfigDict(extra="forbid")

    units: list[ClaudeUnit] = Field(default_factory=list)
