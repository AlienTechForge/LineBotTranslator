"""Shared data models."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

Direction = Literal["horizontal", "vertical_rl", "vertical_lr"]
Role = Literal["title", "heading", "body", "label", "price", "number", "other"]


def base_language(code: str | None) -> str | None:
    """'zh-Hant' -> 'zh', 'JA' -> 'ja', 'und' / '' / None -> None."""
    if not code:
        return None
    base = code.strip().replace("_", "-").split("-")[0].lower()
    return None if base in ("", "und", "zxx", "mul") else base


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
