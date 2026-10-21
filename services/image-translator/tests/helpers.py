"""Small builders shared by several test modules."""

from __future__ import annotations

import math

import pytest

from app.fonts import resolve_font
from app.models import Word


def word(idx: int, text: str, polygon, para: int | None = 0) -> Word:
    return Word(
        id=f"w{idx:04d}",
        text=text,
        polygon=[(float(x), float(y)) for x, y in polygon],
        confidence=0.9,
        para=para,
    )


def rect(x0: float, y0: float, x1: float, y1: float):
    """Corners in Vision order: TL, TR, BR, BL."""
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


def rotate_poly(poly, center, deg_cw: float):
    """Rotate points clockwise (visually, y axis down) around `center`."""
    t = math.radians(deg_cw)
    out = []
    for x, y in poly:
        dx, dy = x - center[0], y - center[1]
        out.append(
            (center[0] + dx * math.cos(t) - dy * math.sin(t), center[1] + dx * math.sin(t) + dy * math.cos(t))
        )
    return out


def has_cjk_font() -> bool:
    return resolve_font(True).path is not None


needs_cjk_font = pytest.mark.skipif(not has_cjk_font(), reason="no CJK font installed")
