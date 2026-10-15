"""Shared data models."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

Direction = Literal["horizontal", "vertical_rl", "vertical_lr"]
Role = Literal["title", "heading", "body", "label", "price", "number", "other"]


class ModeError(RuntimeError):
    """A failure inside one mode; shown in that mode's error box, never fatal for the other mode."""


class Word(BaseModel):
    id: str
    text: str
    polygon: list[tuple[float, float]]  # 4 points, provider order kept
    confidence: float = 0.0
    brk: str | None = None  # detected break after the word (SPACE, LINE_BREAK, ...)
    para: int | None = None  # provider paragraph index (JSON panel only)

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


class ClaudeUnit(BaseModel):
    model_config = ConfigDict(extra="ignore")

    word_ids: list[str]
    source_text: str = ""
    translation: str = ""
    direction: Direction = "horizontal"
    rotated: bool = False
    role: Role = "other"
    keep: bool = False
    notes: str = ""


class ClaudeUnits(BaseModel):
    model_config = ConfigDict(extra="ignore")

    units: list[ClaudeUnit] = Field(default_factory=list)
