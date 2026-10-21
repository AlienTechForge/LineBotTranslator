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
LANE_GAP_RATIO = 0.25  # of the glyph height: the gap kept between a grown frame and what blocks it
EDGE_MARGIN_PX = 2.0  # the grown frame stays this far inside the image


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

    def extended(self, axis: int, start: float, end: float) -> "UnitGeometry":
        """The same frame grown by `start` px against and `end` px along frame axis `axis` (0 = x, 1 = y)."""
        start, end = max(0.0, float(start)), max(0.0, float(end))
        if start == 0.0 and end == 0.0:
            return self
        u, v = self.axes
        direction = u if axis == 0 else v
        shift = (end - start) / 2.0
        center = (self.center[0] + shift * float(direction[0]), self.center[1] + shift * float(direction[1]))
        size = (self.size[0] + start + end, self.size[1]) if axis == 0 else (self.size[0], self.size[1] + start + end)
        half_w, half_h = size[0] / 2.0, size[1] / 2.0
        local = np.array([[-half_w, -half_h], [half_w, -half_h], [half_w, half_h], [-half_w, half_h]])
        corners = np.asarray(center) + local[:, 0:1] * u + local[:, 1:2] * v
        return UnitGeometry(center=center, size=size, angle=self.angle, glyph_h=self.glyph_h, corners=corners)

    def resized(self, axis: int, length: float, glyph_h: float | None = None) -> "UnitGeometry":
        """The same frame (centre, angle) with its extent along frame axis `axis` (0 = x, 1 = y) set to `length`.

        `glyph_h` replaces the glyph size the frame stands for (the size cap and the readable floor follow it).
        """
        size = (float(length), self.size[1]) if axis == 0 else (self.size[0], float(length))
        u, v = self.axes
        half_w, half_h = size[0] / 2.0, size[1] / 2.0
        local = np.array([[-half_w, -half_h], [half_w, -half_h], [half_w, half_h], [-half_w, half_h]])
        corners = np.asarray(self.center) + local[:, 0:1] * u + local[:, 1:2] * v
        return UnitGeometry(
            center=self.center,
            size=size,
            angle=self.angle,
            glyph_h=self.glyph_h if glyph_h is None else float(glyph_h),
            corners=corners,
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


# ---------------------------------------------------------------- free lanes (sideways growth)


def _frame_coords(geom: UnitGeometry, points: np.ndarray) -> np.ndarray:
    """Image points as (x along the frame x axis, y along its y axis), relative to the frame centre."""
    u, v = geom.axes
    rel = np.asarray(points, dtype=np.float64) - np.asarray(geom.center, dtype=np.float64)
    return np.stack([rel @ u, rel @ v], axis=1)


def free_lane(
    geom: UnitGeometry,
    axis: int,
    obstacles: list[np.ndarray],
    image_hw: tuple[int, int],
    gap: float | None = None,
) -> tuple[float, float]:
    """(against, along): how far the frame can grow along frame axis `axis` (0 = x, 1 = y) either way.

    The frame is swept outward, against and along the axis, until the box of another unit or of an
    untouched word (`obstacles`, polygons in image coordinates) or the image edge is reached. An
    obstacle only blocks when it overlaps the frame across the other axis; one that already
    overlaps the frame itself blocks the side its centre lies on, at zero. A gap of a quarter of
    the glyph height is kept in front of an obstacle.
    """
    if gap is None:
        gap = LANE_GAP_RATIO * geom.glyph_h
    half = (geom.size[0] / 2.0, geom.size[1] / 2.0)
    along, across = (half[0], half[1]) if axis == 0 else (half[1], half[0])
    against_free = along_free = float("inf")
    for poly in obstacles:
        pts = _frame_coords(geom, poly)
        a, b = (pts[:, 0], pts[:, 1]) if axis == 0 else (pts[:, 1], pts[:, 0])
        if min(b.max(), across) - max(b.min(), -across) <= 1.0:
            continue  # beside the lane, not in it
        lo, hi = float(a.min()), float(a.max())
        if lo >= along - 1.0:
            along_free = min(along_free, max(0.0, lo - along - gap))
        elif hi <= -along + 1.0:
            against_free = min(against_free, max(0.0, -along - hi - gap))
        elif (lo + hi) / 2.0 >= 0.0:
            along_free = 0.0
        else:
            against_free = 0.0
    # the image edge: the largest shift of the frame along +-direction that keeps every corner inside
    u, v = geom.axes
    direction = u if axis == 0 else v
    height, width = image_hw
    limits = []
    for sign in (-1.0, 1.0):
        room = float("inf")
        for cx, cy in geom.corners:
            for coord, bound, d in ((cx, width, sign * direction[0]), (cy, height, sign * direction[1])):
                if d > 1e-9:
                    room = min(room, (bound - EDGE_MARGIN_PX - coord) / d)
                elif d < -1e-9:
                    room = min(room, (EDGE_MARGIN_PX - coord) / d)
        limits.append(max(0.0, room))
    return min(against_free, limits[0]), min(along_free, limits[1])


def extension_polygons(geom: UnitGeometry, axis: int, start: float, end: float) -> list[np.ndarray]:
    """The strips (image-space corner lists) a frame gains when it grows `start` against / `end` along `axis`."""
    u, v = geom.axes
    hw, hh = geom.size[0] / 2.0, geom.size[1] / 2.0
    cx, cy = geom.center
    polys: list[np.ndarray] = []
    for lo, hi in ((-hw - start, -hw), (hw, hw + end)) if axis == 0 else ((-hh - start, -hh), (hh, hh + end)):
        if hi - lo <= 0.5:
            continue
        if axis == 0:
            local = np.array([[lo, -hh], [hi, -hh], [hi, hh], [lo, hh]])
        else:
            local = np.array([[-hw, lo], [hw, lo], [hw, hi], [-hw, hi]])
        polys.append(np.array([cx, cy]) + local[:, 0:1] * u + local[:, 1:2] * v)
    return polys
