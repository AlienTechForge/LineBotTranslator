"""Mode A, step 5: ink colour, background colour and a cheap "does the ink look bold" test."""

from __future__ import annotations

import os
from dataclasses import dataclass

import cv2
import numpy as np

from .erase import UnitMask

MIN_CONTRAST = 60.0  # RGB distance below which we fall back to black / white
INK_FRACTION = 0.40  # the share of masked pixels farthest from the background used for the ink
BOLD_RATIO = 0.18  # stroke width / glyph height at or above which the ink counts as bold
MIN_BOLD_GLYPH_PX = 12


@dataclass
class ColorChoice:
    text: tuple[int, int, int]
    background: tuple[int, int, int]
    fallback: bool
    contrast: float
    bold: bool
    stroke_ratio: float | None


def ink_fraction() -> float:
    """0.40 by default; LAB_INK_FRACTION (0.05 - 1.0) lets you tune it without touching code.

    A smaller share (say 0.2) gives a darker / more saturated ink on thin, small text whose
    polygons are mostly anti-aliased edge pixels.
    """
    try:
        value = float(os.environ.get("LAB_INK_FRACTION", INK_FRACTION))
    except ValueError:
        return INK_FRACTION
    return min(1.0, max(0.05, value))


def luminance(rgb) -> float:
    r, g, b = (float(c) for c in rgb)
    return 0.299 * r + 0.587 * g + 0.114 * b


def fallback_text_color(background) -> tuple[int, int, int]:
    """Black on light, white on dark."""
    return (0, 0, 0) if luminance(background) >= 127.5 else (255, 255, 255)


def hex_color(rgb) -> str:
    r, g, b = (int(round(c)) for c in rgb)
    return f"#{r:02x}{g:02x}{b:02x}"


def _roi(img: np.ndarray, um: UnitMask) -> np.ndarray:
    h, w = um.raw.shape
    return img[um.y0 : um.y0 + h, um.x0 : um.x0 + w]


def background_color(inpainted: np.ndarray, corners: np.ndarray) -> tuple[int, int, int]:
    """Median of the inpainted pixels under the unit frame."""
    img_h, img_w = inpainted.shape[:2]
    x0 = max(0, int(np.floor(corners[:, 0].min())))
    y0 = max(0, int(np.floor(corners[:, 1].min())))
    x1 = min(img_w, int(np.ceil(corners[:, 0].max())) + 1)
    y1 = min(img_h, int(np.ceil(corners[:, 1].max())) + 1)
    if x1 <= x0 or y1 <= y0:
        return tuple(int(c) for c in np.median(inpainted.reshape(-1, 3), axis=0))  # type: ignore[return-value]
    mask = np.zeros((y1 - y0, x1 - x0), np.uint8)
    poly = np.round(corners - np.array([x0, y0])).astype(np.int32)
    cv2.fillPoly(mask, [poly], 255)
    pixels = inpainted[y0:y1, x0:x1][mask > 0]
    if pixels.size == 0:
        pixels = inpainted[y0:y1, x0:x1].reshape(-1, 3)
    return tuple(int(c) for c in np.median(pixels, axis=0))  # type: ignore[return-value]


def estimate_stroke_ratio(ink: np.ndarray, glyph_h: float) -> float | None:
    """Median over connected ink components of (2 x max distance to background) / glyph height."""
    if glyph_h <= 0 or not ink.any():
        return None
    dist = cv2.distanceTransform(ink, cv2.DIST_L2, 3)
    n, labels, stats, _ = cv2.connectedComponentsWithStats(ink, connectivity=8)
    widths = []
    for label in range(1, min(n, 201)):
        x, y, w, h, area = stats[label]
        if area < 4:
            continue
        sub = labels[y : y + h, x : x + w] == label
        widths.append(2.0 * float(dist[y : y + h, x : x + w][sub].max()))
    if not widths:
        return None
    return float(np.median(widths)) / glyph_h


def pick_colors(
    orig: np.ndarray,
    inpainted: np.ndarray,
    unit_mask: UnitMask,
    corners: np.ndarray,
    glyph_h: float,
) -> ColorChoice:
    """Choose the ink colour (from the pre-inpaint pixels) and the background colour.

    Ink: among the pixels of the undilated word mask, the 40% that differ most from the
    inpainted background at the same pixel; their median colour in the ORIGINAL image.
    Background: median of the inpainted pixels under the unit frame. If the two are closer
    than 60 in RGB distance, fall back to black on light / white on dark.
    """
    bg = background_color(inpainted, corners)
    raw = unit_mask.raw > 0
    o = _roi(orig, unit_mask)[raw].astype(np.float32)
    i = _roi(inpainted, unit_mask)[raw].astype(np.float32)
    if o.shape[0] < 4:
        text = fallback_text_color(bg)
        return ColorChoice(text, bg, True, 0.0, False, None)

    d = np.linalg.norm(o - i, axis=1)
    k = max(1, int(round(ink_fraction() * d.shape[0])))
    order = np.argsort(-d)[:k]
    text = tuple(int(c) for c in np.median(o[order], axis=0))
    contrast = float(np.linalg.norm(np.asarray(text, np.float32) - np.asarray(bg, np.float32)))
    fallback = contrast < MIN_CONTRAST
    if fallback:
        text = fallback_text_color(bg)

    bold = False
    ratio = None
    if glyph_h >= MIN_BOLD_GLYPH_PX and not fallback:
        dist_map = np.zeros(unit_mask.raw.shape, np.float32)
        dist_map[raw] = d
        d8 = np.clip(dist_map, 0, 255).astype(np.uint8)
        thr, _ = cv2.threshold(d8[raw].reshape(-1, 1), 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        ink = ((d8 >= max(thr, 1)) & raw).astype(np.uint8)
        ratio = estimate_stroke_ratio(ink, glyph_h)
        bold = ratio is not None and ratio >= BOLD_RATIO
    return ColorChoice(text, bg, fallback, contrast, bold, ratio)  # type: ignore[arg-type]
