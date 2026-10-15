"""Mode A, step 3: the frame of a translation unit, computed from its member word polygons.

The frame is a rotated rectangle whose x axis follows the glyph baseline (so text is always
drawn "upright" in frame coordinates) and whose y axis runs down the glyphs. Its size is the
extent of the member words along those axes, padded by 10% of the glyph height. For
horizontal text the reading direction is the frame's x axis; for vertical writing it is the
frame's y axis. ``cv2.minAreaRect`` supplies the orientation of the point cloud; the glyph
baseline of the words (Vision orders polygon vertices in the text's own reading frame)
decides which of the four right-angle variants is "up".
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import cv2
import numpy as np

from .models import Direction, Word

PAD_RATIO = 0.10
SNAP_DEG = 0.75


@dataclass
class UnitGeometry:
    center: tuple[float, float]
    size: tuple[float, float]  # (width along frame x, height along frame y), padded
    angle: float  # degrees, clockwise in image coordinates, in (-180, 180]
    glyph_h: float
    corners: np.ndarray  # (4, 2): TL, TR, BR, BL of the frame in frame axes, image coordinates

    @property
    def axes(self) -> tuple[np.ndarray, np.ndarray]:
        t = math.radians(self.angle)
        return np.array([math.cos(t), math.sin(t)]), np.array([-math.sin(t), math.cos(t)])

    @property
    def bbox(self) -> tuple[int, int, int, int]:
        """Axis-aligned bbox of the frame as (x0, y0, x1, y1)."""
        xs, ys = self.corners[:, 0], self.corners[:, 1]
        return int(math.floor(xs.min())), int(math.floor(ys.min())), int(math.ceil(xs.max())), int(
            math.ceil(ys.max())
        )

    def to_dict(self) -> dict:
        return {
            "center": [round(self.center[0], 1), round(self.center[1], 1)],
            "size": [round(self.size[0], 1), round(self.size[1], 1)],
            "angle": round(self.angle, 2),
            "glyph_h": round(self.glyph_h, 1),
            "frame": [[round(float(x), 1), round(float(y), 1)] for x, y in self.corners],
            "bbox": list(self.bbox),
        }


def _norm_angle(deg: float) -> float:
    deg = (deg + 180.0) % 360.0 - 180.0
    return 180.0 if deg == -180.0 else deg


def _angle_diff(a: float, b: float) -> float:
    return abs(_norm_angle(a - b))


def _snap(angle: float) -> float:
    nearest = round(angle / 90.0) * 90.0
    if abs(angle - nearest) <= SNAP_DEG:
        return _norm_angle(nearest)
    return _norm_angle(angle)


def _edges(word: Word) -> tuple[np.ndarray, np.ndarray]:
    p = np.asarray(word.polygon, dtype=np.float64)
    return p[1] - p[0], p[2] - p[1]


def word_short_side(word: Word) -> float:
    e0, e1 = _edges(word)
    return float(min(np.linalg.norm(e0), np.linalg.norm(e1)))


def glyph_height(words: list[Word]) -> float:
    """Median of the words' short sides."""
    return float(np.median([max(1.0, word_short_side(w)) for w in words]))


def baseline_angle(words: list[Word]) -> float:
    """Circular mean of the first polygon edge (the reading baseline in Vision's vertex order)."""
    sx = sy = 0.0
    for w in words:
        e0, _ = _edges(w)
        n = float(np.linalg.norm(e0))
        if n > 1e-6:
            sx += e0[0] / n
            sy += e0[1] / n
    if abs(sx) < 1e-9 and abs(sy) < 1e-9:
        return 0.0
    return math.degrees(math.atan2(sy, sx))


def _median_aspect(words: list[Word]) -> float:
    """Median of (edge along the baseline) / (edge across it); < 1 for tall, upright columns."""
    ratios = []
    for w in words:
        e0, e1 = _edges(w)
        n0, n1 = float(np.linalg.norm(e0)), float(np.linalg.norm(e1))
        if n1 > 1e-6:
            ratios.append(n0 / n1)
    return float(np.median(ratios)) if ratios else 1.0


def compute_geometry(
    words: list[Word], direction: Direction = "horizontal", rotated: bool = False
) -> UnitGeometry:
    if not words:
        raise ValueError("a unit needs at least one word")
    pts = np.asarray([p for w in words for p in w.polygon], dtype=np.float32)
    glyph_h = glyph_height(words)
    b_angle = baseline_angle(words)

    theta = b_angle
    if len(np.unique(pts, axis=0)) >= 3:
        rect = cv2.minAreaRect(pts)
        box = cv2.boxPoints(rect)
        e = box[1] - box[0]
        if float(np.linalg.norm(e)) < 1e-6:
            e = box[2] - box[1]
        a0 = math.degrees(math.atan2(float(e[1]), float(e[0])))
        # The point cloud tells us the axes up to 90 degrees; the word baselines pick the variant.
        theta = min((_norm_angle(a0 + 90.0 * k) for k in range(4)), key=lambda c: _angle_diff(c, b_angle))

    # Vertical columns of upright glyphs: if the vertex order made the frame x axis run along
    # the (tall) words, turn the frame a quarter so that x is across the column again.
    if direction != "horizontal" and not rotated and _median_aspect(words) > 1.5:
        theta = min((_norm_angle(theta - 90.0), _norm_angle(theta + 90.0)), key=abs)

    theta = _snap(theta)
    t = math.radians(theta)
    u = np.array([math.cos(t), math.sin(t)])
    v = np.array([-math.sin(t), math.cos(t)])
    px = pts.astype(np.float64) @ u
    py = pts.astype(np.float64) @ v
    pad = PAD_RATIO * glyph_h
    x0, x1 = float(px.min()) - pad, float(px.max()) + pad
    y0, y1 = float(py.min()) - pad, float(py.max()) + pad
    corners = np.array([[x0, y0], [x1, y0], [x1, y1], [x0, y1]])
    img_corners = corners[:, 0:1] * u + corners[:, 1:2] * v
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    center = cx * u + cy * v
    return UnitGeometry(
        center=(float(center[0]), float(center[1])),
        size=(x1 - x0, y1 - y0),
        angle=theta,
        glyph_h=glyph_h,
        corners=img_corners,
    )
