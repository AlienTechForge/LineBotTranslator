"""Mode A, step 4: erase the source glyphs without smearing neighbours over them.

Round 2 dilated every word mask and inpainted all of them in one OpenCV call. On a receipt the
dilated masks reached into the black price cells and the table rules next to the dish names, and
Telea inpainting pulled that black into the white cells. Now each translated unit is looked at on
its own:

* its background is measured on the pixels of its own (undilated) box: the dominant colour, and the
  spread (per-channel std) of the pixels that are not ink (ink = far from that colour);
* round 6: the box is not trusted alone. A ring of RING_PX pixels just outside the unit's erase area
  (leaving out every other word and every pixel of a foreign colour) must agree with the box: its
  spread at most FLAT_STD_MAX, its median within RING_MATCH_MAX of the box colour and its two ends
  along the reading direction within RING_MATCH_MAX of each other (shading across a long label).
  Only then is the background flat, and the fill colour is the ring median, not the box colour
  (whose halo and JPEG noise pull it a few levels off the paper: the grey and white rectangles of
  round 5). A box a little noisier than FLAT_STD_MAX (the halo of a heavy heading, up to
  BOX_SPREAD_WITH_RING_MAX) passes too when the ring is clean and agrees; round 5 inpainted those
  headings and Telea dragged their orange into the cream paper;
* a flat background is erased by **flat fill**, edges feathered over FEATHER_SIGMA; anything else
  is **inpainted** (OpenCV Telea);
* in both cases the dilation of the glyph mask is **colour-guarded**: it only grows over pixels
  whose colour is within a tolerance of the unit's background colour, so it never reaches into a
  black cell, a rule line or any neighbour of another colour (for inpainting, not even within the
  radius Telea reads from);
* the words that are not translated keep their pixels.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import cv2
import numpy as np

from .models import Word

INPAINT_RADIUS = 3
MIN_DILATE_PX = 2
DILATE_RATIO = 0.15

BG_BIN = 32  # colour quantisation step of the histogram that finds the dominant colour
FINE_BIN = 4  # the grid on which the most frequent value inside the winning bin is looked for
BG_REFINE_DISTANCE = 12.0  # the background colour is the median of the pixels this close to that value
INK_DISTANCE = 40.0  # RGB distance from the background beyond which a pixel of the box counts as ink
FLAT_STD_MAX = 10.0  # per-channel std (0-255) of the non-ink pixels at or below which the background is flat
MIN_BG_PIXELS = 12  # fewer non-ink pixels than this: no evidence of a flat background
MAX_INK_SHARE = 0.75  # more "ink" than this: the box straddles backgrounds or is a steep gradient, not flat
GUARD_TOLERANCE = 56.0  # RGB distance from the background a pixel may have for the dilation to grow over it
GUARD_SPREAD_K = 3.0  # the tolerance grows with the spread of a textured background ...
GUARD_TOLERANCE_MAX = 110.0  # ... but never beyond this
SECOND_COLOR_MIN_SHARE = 0.15  # a second candidate for the background needs this share of the box
FEATHER_SIGMA = 1.5  # px; the fill fades out over about three pixels, only over background-coloured pixels
RING_PX = 6  # width of the band sampled just outside the unit's erase area
RING_MATCH_MAX = 8.0  # RGB distance: the ring median vs the box colour; also what a lane extension may be off the fill
BOX_SPREAD_WITH_RING_MAX = 16.0  # a box a little too noisy to be flat alone (the halo of big glyphs) passes when the ring is clean
RING_END_MIN_PX = 24  # an end of the ring with fewer usable pixels than this is not compared with the other one
MIN_RING_PX = 24  # fewer usable ring pixels than this: no evidence either way (the box decides alone)
RING_MIN_SHARE = 0.15  # the ring's background colour must cover this share of the ring, else the ring is too mixed
OCCUPIED_PAD_PX = 2  # words of the page are left out of every ring, padded by this much


def dilation_px(glyph_h: float) -> int:
    """Dilation radius: max(2 px, 15% of the glyph height)."""
    return max(MIN_DILATE_PX, int(round(DILATE_RATIO * glyph_h)))


@dataclass
class UnitMask:
    """Masks of one unit, stored on a small ROI instead of the whole image."""

    x0: int
    y0: int
    raw: np.ndarray  # uint8 0/255, the undilated union of the word polygons
    dilated: np.ndarray  # uint8 0/255, raw dilated by dilation_px (unguarded)
    guarded: np.ndarray | None = None  # raw + the dilation over background-coloured pixels only
    allowed: np.ndarray | None = None  # bool: the pixels the guarded dilation (and the feather) may cover

    @property
    def shape(self) -> tuple[int, int]:
        return self.raw.shape  # type: ignore[return-value]

    def paste_into(self, target: np.ndarray, which: str = "dilated") -> None:
        src = {"raw": self.raw, "dilated": self.dilated, "guarded": self.guarded}[which]
        if src is None:
            raise ValueError("this unit mask has no guarded mask yet (call guard_dilation first)")
        h, w = src.shape
        y1, x1 = self.y0 + h, self.x0 + w
        view = target[self.y0 : y1, self.x0 : x1]
        np.bitwise_or(view, src[: view.shape[0], : view.shape[1]], out=view)

    def roi(self, img: np.ndarray) -> np.ndarray:
        h, w = self.raw.shape
        return img[self.y0 : self.y0 + h, self.x0 : self.x0 + w]


def build_unit_mask(
    image_hw: tuple[int, int], words: list[Word], glyph_h: float, dilate: int | None = None
) -> UnitMask | None:
    """Filled union of the word polygons plus its dilation, clipped to the image."""
    img_h, img_w = image_hw
    r = dilation_px(glyph_h) if dilate is None else dilate
    polys = [np.round(np.asarray(w.polygon, dtype=np.float64)).astype(np.int32) for w in words]
    allp = np.concatenate(polys, axis=0)
    margin = r + 2
    x0 = max(0, int(allp[:, 0].min()) - margin)
    y0 = max(0, int(allp[:, 1].min()) - margin)
    x1 = min(img_w, int(allp[:, 0].max()) + margin + 1)
    y1 = min(img_h, int(allp[:, 1].max()) + margin + 1)
    if x1 <= x0 or y1 <= y0:
        return None
    raw = np.zeros((y1 - y0, x1 - x0), np.uint8)
    for p in polys:
        cv2.fillPoly(raw, [p - np.array([x0, y0], dtype=np.int32)], 255)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
    dilated = cv2.dilate(raw, kernel)
    return UnitMask(x0, y0, raw, dilated)


def build_polygon_mask(image_hw: tuple[int, int], polygons: list[np.ndarray]) -> UnitMask | None:
    """A mask of some polygons (frame corner lists), no dilation: the grown part of a unit's frame."""
    img_h, img_w = image_hw
    polys = [np.round(np.asarray(p, dtype=np.float64)).astype(np.int32) for p in polygons]
    if not polys:
        return None
    pts = np.concatenate(polys, axis=0)
    x0, y0 = max(0, int(pts[:, 0].min())), max(0, int(pts[:, 1].min()))
    x1, y1 = min(img_w, int(pts[:, 0].max()) + 1), min(img_h, int(pts[:, 1].max()) + 1)
    if x1 <= x0 or y1 <= y0:
        return None
    raw = np.zeros((y1 - y0, x1 - x0), np.uint8)
    for poly in polys:
        cv2.fillPoly(raw, [poly - np.array([x0, y0], dtype=np.int32)], 255)
    return UnitMask(x0, y0, raw, raw.copy())


# ---------------------------------------------------------------- the background of a unit


@dataclass
class BackgroundInfo:
    color: tuple[int, int, int]  # the unit's background colour
    spread: float  # the largest per-channel std of the non-ink pixels (255 when too few)
    spread_norm: float  # the norm of the three per-channel stds
    ink_share: float  # share of the box that is far from the background colour
    non_ink_px: int
    flat: bool  # the final answer: the box AND the ring agree (see `reason`)
    tolerance: float  # RGB distance from `color` that still counts as background for the guard
    ring_color: tuple[int, int, int] | None = None  # median of the ring's background pixels (None: no ring evidence)
    ring_spread: float | None = None  # the largest per-channel std of those pixels
    ring_px: int = 0  # how many pixels the ring statistics rest on
    ring_gap: float | None = None  # half the distance between the ring's two ends along the reading direction
    interior_flat: bool = False  # the box alone looked flat
    reason: str = ""  # why this method: a short phrase that goes into the unit report

    @property
    def method(self) -> str:
        return "flat" if self.flat else "inpaint"

    @property
    def fill_color(self) -> tuple[int, int, int]:
        """What a flat fill paints: the ring median when there is one, else the box colour."""
        return self.ring_color if (self.flat and self.ring_color is not None) else self.color

    def to_dict(self) -> dict:
        return {
            "color": list(self.color),
            "spread": round(self.spread, 2),
            "ink_share": round(self.ink_share, 3),
            "flat": self.flat,
            "tolerance": round(self.tolerance, 1),
            "ring_color": None if self.ring_color is None else list(self.ring_color),
            "ring_spread": None if self.ring_spread is None else round(self.ring_spread, 2),
            "ring_px": self.ring_px,
            "ring_gap": None if self.ring_gap is None else round(self.ring_gap, 2),
            "fill_color": list(self.fill_color),
            "reason": self.reason,
        }


def _fine_mode(pixels: np.ndarray) -> np.ndarray:
    """The most frequent colour of a pixel set, found on a 4-level grid and returned as that bin's median."""
    bins = (pixels // FINE_BIN).astype(np.int32)
    keys = bins[:, 0] * 10_000 + bins[:, 1] * 100 + bins[:, 2]
    uniq, inverse, counts = np.unique(keys, return_inverse=True, return_counts=True)
    return np.median(pixels[inverse == int(np.argmax(counts))], axis=0)


def _dominant_colors(pixels: np.ndarray, count: int = 2) -> list[tuple[np.ndarray, float]]:
    """The `count` most frequent colours of an (n, 3) pixel set as (colour, share).

    Pixels are binned coarsely (BG_BIN) to find the winners and their shares. A winner's colour is
    then its most frequent value on a fine grid (not the median of the coarse bin, which the
    anti-aliased edges of the glyphs would pull away from the real background), refined as the
    median of everything within BG_REFINE_DISTANCE of it, so that a noisy background still gives one
    colour that matches its centre.
    """
    bins = (pixels // BG_BIN).astype(np.int32)
    keys = bins[:, 0] * 10_000 + bins[:, 1] * 100 + bins[:, 2]
    uniq, inverse, counts = np.unique(keys, return_inverse=True, return_counts=True)
    out: list[tuple[np.ndarray, float]] = []
    for idx in np.argsort(-counts)[:count]:
        color = _fine_mode(pixels[inverse == idx])
        near = np.linalg.norm(pixels - color, axis=1) <= BG_REFINE_DISTANCE
        if near.any():
            color = np.median(pixels[near], axis=0)
        out.append((color, float(counts[idx]) / len(pixels)))
    return out


REASON_RING_AGREES = "ring agrees with the box"
REASON_RING_VOUCHES = "ring is clean and agrees; the box noise is glyph halo"
REASON_NO_RING = "box only: no usable ring"
REASON_BOX_NOT_FLAT = "box not flat"
REASON_RING_MIXED = "ring too mixed"
REASON_RING_NOT_FLAT = "ring not flat"
REASON_RING_COLOUR = "ring colour differs from the box"
REASON_RING_GRADIENT = "ring ends differ (shading)"


def _ellipse(radius: int) -> np.ndarray:
    return cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * radius + 1, 2 * radius + 1))


def occupied_map(image_hw: tuple[int, int], polygons: list) -> np.ndarray:
    """bool image-sized map of every pixel under a word polygon (padded): the rings leave these out."""
    img_h, img_w = image_hw
    m = np.zeros((img_h, img_w), np.uint8)
    for poly in polygons:
        cv2.fillPoly(m, [np.round(np.asarray(poly, dtype=np.float64)).astype(np.int32)], 255)
    return cv2.dilate(m, _ellipse(OCCUPIED_PAD_PX)) > 0


def ring_sample(rgb: np.ndarray, um: UnitMask, exclude: np.ndarray | None = None) -> tuple[np.ndarray, np.ndarray]:
    """The colours of the band of RING_PX just outside the unit's (dilated) erase area.

    Returns ((n, 3) float32 colours, (n,) int8 labels): 0 for the pixels at the start of the box along
    its long axis (left of a wide box, above a tall one), 1 for those at its far end, -1 for the rest.
    The band is clipped to the image; `exclude` (image-sized bool, see occupied_map) removes every
    pixel of another word, so neither another unit's glyphs nor a kept price are sampled.
    """
    img_h, img_w = rgb.shape[:2]
    h, w = um.dilated.shape
    pad = RING_PX + 1
    x0, y0 = max(0, um.x0 - pad), max(0, um.y0 - pad)
    x1, y1 = min(img_w, um.x0 + w + pad), min(img_h, um.y0 + h + pad)
    ox, oy = um.x0 - x0, um.y0 - y0
    inner = np.zeros((y1 - y0, x1 - x0), np.uint8)
    inner[oy : oy + h, ox : ox + w] = um.dilated
    band = (cv2.dilate(inner, _ellipse(RING_PX)) > 0) & (inner == 0)
    if exclude is not None:
        band &= ~exclude[y0:y1, x0:x1]
    ys, xs = np.nonzero(band)
    if ys.size == 0:
        return np.zeros((0, 3), np.float32), np.zeros(0, np.int8)
    raw_y, raw_x = np.nonzero(um.raw)
    lo_x, hi_x, lo_y, hi_y = int(raw_x.min()) + ox, int(raw_x.max()) + ox, int(raw_y.min()) + oy, int(raw_y.max()) + oy
    along, lo, hi = (xs, lo_x, hi_x) if (hi_x - lo_x) >= (hi_y - lo_y) else (ys, lo_y, hi_y)
    end = np.where(along < lo, 0, np.where(along > hi, 1, -1)).astype(np.int8)
    return rgb[y0:y1, x0:x1][ys, xs].astype(np.float32), end


@dataclass
class RingStats:
    color: tuple[int, int, int]  # median of the ring's background pixels
    spread: float  # largest per-channel std of those pixels
    px: int  # how many pixels (of `total`) they are
    total: int
    gap: float  # half the distance between the medians of the ring's two ends (0 when an end is too thin)
    mixed: bool  # the background colour covers too little of the ring to say anything


def ring_stats(ring: np.ndarray, end: np.ndarray, box_color: np.ndarray) -> RingStats | None:
    """What the ring says about the paper, or None when it holds too few pixels to say anything.

    The ring's background colour is its dominant colour; when a second colour covers a good share
    (a neighbouring cell next to the box) the one closer to the colour of the box wins. Pixels
    farther than INK_DISTANCE from it (another cell, a rule, some foreign ink) are left out: they
    are what the colour guard keeps the erase away from, not evidence about the paper. Median and
    spread are taken over the pixels that remain. `gap` compares the two ends of the ring along the
    reading direction: a flat fill is off by about half of it at either end of the box, which is
    what a long label on shaded paper shows as a patch. (The sides across the reading direction are
    not compared: in a table they hold the rules between the rows.)
    """
    total = ring.shape[0]
    if total < MIN_RING_PX:
        return None
    candidates = _dominant_colors(ring)
    pick = candidates[0][0]
    if len(candidates) > 1 and candidates[1][1] >= SECOND_COLOR_MIN_SHARE:
        pick = min(candidates, key=lambda c: float(np.linalg.norm(c[0] - box_color)))[0]
    near = np.linalg.norm(ring - pick, axis=1) <= INK_DISTANCE
    count = int(near.sum())
    if count < MIN_RING_PX:  # the ring is all over the place: not one colour has enough pixels
        return RingStats(tuple(int(round(c)) for c in pick), 255.0, count, total, 0.0, True)  # type: ignore[arg-type]
    bg = ring[near]
    first, last = ring[near & (end == 0)], ring[near & (end == 1)]
    gap = 0.0
    if first.shape[0] >= RING_END_MIN_PX and last.shape[0] >= RING_END_MIN_PX:
        gap = float(np.linalg.norm(np.median(first, axis=0) - np.median(last, axis=0))) / 2.0
    return RingStats(
        tuple(int(round(c)) for c in np.median(bg, axis=0)),  # type: ignore[arg-type]
        float(bg.std(axis=0).max()),
        count,
        total,
        gap,
        count / total < RING_MIN_SHARE,
    )


def region_agrees(pixels: np.ndarray, fill: tuple[int, int, int] | np.ndarray) -> tuple[bool, float, float]:
    """Would a flat fill of `fill` be invisible over these pixels? (ok, distance of their median, their spread).

    The pixels must be flat (per-channel std at most FLAT_STD_MAX, a few faint marks aside) and their
    median within RING_MATCH_MAX of `fill`: the same test the ring has to pass, applied to a lane
    extension before it is grown into or filled. A block too small to judge agrees.
    """
    pix = np.asarray(pixels, np.float32).reshape(-1, 3)
    if pix.shape[0] < MIN_RING_PX:
        return True, 0.0, 0.0
    median = np.median(pix, axis=0)
    inliers = np.linalg.norm(pix - median, axis=1) <= INK_DISTANCE
    if inliers.mean() < 0.9:
        return False, float(np.linalg.norm(median - np.asarray(fill, np.float32))), 255.0
    spread = float(pix[inliers].std(axis=0).max())
    distance = float(np.linalg.norm(median - np.asarray(fill, np.float32)))
    return distance <= RING_MATCH_MAX and spread <= FLAT_STD_MAX, distance, spread


def measure_background(rgb: np.ndarray, um: UnitMask, exclude: np.ndarray | None = None) -> BackgroundInfo:
    """Colour and flatness of the background of one unit: its own undilated box AND the ring around it.

    The background colour is the dominant colour of the box; when a second colour covers a good share
    of it (bold or inverted text) the one closer to the ring just outside the box wins. Ink is every
    pixel farther than INK_DISTANCE from that colour. The box looks flat when the non-ink pixels
    have a per-channel std of at most FLAT_STD_MAX, there are enough of them and the ink does not
    take over (MAX_INK_SHARE).

    The box alone is not enough (round 6): the paper around it, sampled in a band of RING_PX just
    outside the erase area (ring_sample; `exclude` keeps other words out), has to agree. The unit is
    flat only when the ring is flat too (std at most FLAT_STD_MAX, which catches a gradient that a
    small box cannot show), its median is within RING_MATCH_MAX of the box colour and the two ends of
    the ring along the reading direction are within RING_MATCH_MAX of each other (`ring_gap`, half
    their distance, is at most RING_MATCH_MAX: a long label on paper that is shaded from one end to the
    other). The fill colour is then the ring median (`fill_color`). A ring with no usable pixels leaves
    the decision to the box. `reason` says which test decided.
    """
    roi = um.roi(rgb)
    raw = um.raw > 0
    pixels = roi[raw].astype(np.float32)
    if pixels.shape[0] < 4:
        fallback = tuple(int(c) for c in np.median(roi.reshape(-1, 3), axis=0))
        return BackgroundInfo(  # type: ignore[arg-type]
            fallback, 255.0, 441.0, 1.0, 0, False, GUARD_TOLERANCE, reason=REASON_BOX_NOT_FLAT
        )
    candidates = _dominant_colors(pixels)
    color = candidates[0][0]
    near_ring = roi[(um.dilated > 0) & ~raw].astype(np.float32)
    if len(candidates) > 1 and candidates[1][1] >= SECOND_COLOR_MIN_SHARE and near_ring.shape[0] >= 8:
        around = np.median(near_ring, axis=0)
        color = min(candidates, key=lambda c: float(np.linalg.norm(c[0] - around)))[0]
    distance = np.linalg.norm(pixels - color, axis=1)
    non_ink = distance <= INK_DISTANCE
    count = int(non_ink.sum())
    ink_share = 1.0 - count / pixels.shape[0]
    if count >= MIN_BG_PIXELS:
        std = pixels[non_ink].std(axis=0)
        spread, spread_norm = float(std.max()), float(np.linalg.norm(std))
    else:
        spread, spread_norm = 255.0, 441.0
    usable_box = count >= MIN_BG_PIXELS and ink_share <= MAX_INK_SHARE
    interior_flat = usable_box and spread <= FLAT_STD_MAX
    noisy_box = usable_box and not interior_flat and spread <= BOX_SPREAD_WITH_RING_MAX
    tolerance = min(GUARD_TOLERANCE_MAX, max(GUARD_TOLERANCE, GUARD_SPREAD_K * (0.0 if spread >= 255 else spread_norm)))

    ring = ring_stats(*ring_sample(rgb, um, exclude), color)
    if not (interior_flat or noisy_box):
        flat, reason = False, REASON_BOX_NOT_FLAT
    elif ring is None:
        flat, reason = (True, REASON_NO_RING) if interior_flat else (False, REASON_BOX_NOT_FLAT)
    elif ring.mixed:
        flat, reason = False, REASON_RING_MIXED
    elif ring.spread > FLAT_STD_MAX:
        flat, reason = False, REASON_RING_NOT_FLAT
    elif float(np.linalg.norm(np.asarray(ring.color, np.float32) - color)) > RING_MATCH_MAX:
        flat, reason = False, REASON_RING_COLOUR
    elif ring.gap > RING_MATCH_MAX:
        flat, reason = False, REASON_RING_GRADIENT
    else:
        flat, reason = True, (REASON_RING_AGREES if interior_flat else REASON_RING_VOUCHES)
    return BackgroundInfo(
        tuple(int(round(c)) for c in color),  # type: ignore[arg-type]
        spread,
        spread_norm,
        ink_share,
        count,
        flat,
        tolerance,
        ring_color=None if ring is None else ring.color,
        ring_spread=None if ring is None else ring.spread,
        ring_px=0 if ring is None else ring.px,
        ring_gap=None if ring is None else ring.gap,
        interior_flat=interior_flat,
        reason=reason,
    )


def guard_dilation(rgb: np.ndarray, um: UnitMask, info: BackgroundInfo) -> None:
    """Fill `um.guarded` (raw + dilation over background-coloured pixels) and `um.allowed`.

    A pixel of the dilation ring is erased only when its colour is within `info.tolerance` of the
    unit's background colour. For inpainting the pixels within INPAINT_RADIUS of a foreign pixel
    (another colour, outside this unit's own box) are left out as well, because Telea reads that far
    around the mask and would pull the foreign colour in.
    """
    roi = um.roi(rgb).astype(np.float32)
    raw = um.raw > 0
    background_like = np.linalg.norm(roi - np.asarray(info.color, np.float32), axis=2) <= info.tolerance
    allowed = background_like.copy()
    if not info.flat:
        foreign = (~background_like & ~raw).astype(np.uint8)
        k = 2 * INPAINT_RADIUS + 1
        keep_out = cv2.dilate(foreign, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))) > 0
        allowed &= ~keep_out
    grown = (um.dilated > 0) & allowed
    um.allowed = allowed
    um.guarded = (((um.raw > 0) | grown).astype(np.uint8)) * 255


# ---------------------------------------------------------------- masks and the erase itself


def build_global_mask(
    image_hw: tuple[int, int],
    unit_masks: list[UnitMask],
    protect_masks: list[UnitMask] | None = None,
    which: str = "dilated",
    own_masks: list[UnitMask] | None = None,
) -> np.ndarray:
    """OR of the unit masks (`which`: dilated or guarded), minus the raw masks of words we do not translate.

    Words that are kept or not listed keep their pixels even when a neighbour's dilation
    would have reached them, unless a translated word's own polygon covers the same pixels
    (`own_masks`, default: the unit masks themselves, are the polygons that win).
    """
    img_h, img_w = image_hw
    mask = np.zeros((img_h, img_w), np.uint8)
    own = np.zeros((img_h, img_w), np.uint8)
    for um in unit_masks:
        um.paste_into(mask, which)
    for um in own_masks if own_masks is not None else unit_masks:
        um.paste_into(own, "raw")
    if protect_masks:
        protect = np.zeros((img_h, img_w), np.uint8)
        for pm in protect_masks:
            pm.paste_into(protect, "raw")
        mask[(protect > 0) & (own == 0)] = 0
    return mask


def inpaint_all(rgb: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """One Telea inpaint over the whole image for the given mask."""
    if not mask.any():
        return rgb.copy()
    return cv2.inpaint(rgb, mask, INPAINT_RADIUS, cv2.INPAINT_TELEA)


@dataclass
class EraseUnit:
    uid: str
    mask: UnitMask
    info: BackgroundInfo
    extension: UnitMask | None = None  # the part the frame grew by; only a flat unit may have one


@dataclass
class EraseResult:
    erased: np.ndarray
    methods: dict[str, str]  # uid -> flat | inpaint
    mask_px: dict[str, int]  # uid -> pixels of its guarded erase area
    extension: dict[str, str] = field(default_factory=dict)  # uid -> filled | skipped (only units that grew)


def _flat_fill(out: np.ndarray, um: UnitMask, color: tuple[int, int, int], blocked: np.ndarray) -> None:
    """Fill the guarded area of one unit with its fill colour, feathered over background pixels only."""
    h, w = um.raw.shape
    y1, x1 = min(out.shape[0], um.y0 + h), min(out.shape[1], um.x0 + w)
    sl = (slice(um.y0, y1), slice(um.x0, x1))
    hh, ww = y1 - um.y0, x1 - um.x0
    free = ~blocked[sl]
    inside = (um.guarded[:hh, :ww] > 0) & free
    alpha = inside.astype(np.float32)
    soft = cv2.GaussianBlur(alpha, (0, 0), FEATHER_SIGMA)
    outside = um.allowed[:hh, :ww] & free & ~inside
    alpha = np.where(inside, 1.0, np.where(outside, soft, 0.0))[..., None]
    region = out[sl].astype(np.float32)
    out[sl] = np.clip(np.rint(region * (1.0 - alpha) + np.asarray(color, np.float32) * alpha), 0, 255).astype(np.uint8)


def erase_units(
    rgb: np.ndarray, units: list[EraseUnit], protect_masks: list[UnitMask] | None = None
) -> EraseResult:
    """Erase every translated unit: flat fill where the background is flat, inpaint elsewhere.

    The dilation of every unit mask is colour-guarded first. Untouched words (`protect_masks`) keep
    their pixels in both methods, except where a translated unit's own polygon covers them. The
    inpaint units share one Telea call (on the original), then the flat fills are applied on top.
    """
    img_h, img_w = rgb.shape[:2]
    extension_state: dict[str, str] = {}
    for unit in units:
        guard_dilation(rgb, unit.mask, unit.info)
        if unit.extension is not None:
            # the grown part was checked when the lane was measured; guard it all the same, and test it
            # again with the same rule as the ring before anything is painted over it (a part that is not
            # flat or not the fill colour is left as it is: the text is drawn over the real background)
            extension = unit.extension
            roi = extension.roi(rgb).astype(np.float32)
            ok = np.linalg.norm(roi - np.asarray(unit.info.fill_color, np.float32), axis=2) <= unit.info.tolerance
            covered = (extension.raw > 0) & ok
            agrees, _, _ = region_agrees(roi[covered], unit.info.fill_color)
            if not agrees:
                covered = np.zeros_like(covered)
            extension.raw = covered.astype(np.uint8) * 255
            extension.guarded = extension.raw.copy()
            extension.allowed = ok & covered.any()
            extension_state[unit.uid] = "filled" if agrees else "skipped"
    own = [u.mask for u in units]
    blocked = np.zeros((img_h, img_w), bool)
    if protect_masks:
        protect = np.zeros((img_h, img_w), np.uint8)
        for pm in protect_masks:
            pm.paste_into(protect, "raw")
        own_px = np.zeros((img_h, img_w), np.uint8)
        for um in own:
            um.paste_into(own_px, "raw")
        blocked = (protect > 0) & (own_px == 0)

    inpaint_units = [u for u in units if not u.info.flat]
    mask = build_global_mask(
        (img_h, img_w), [u.mask for u in inpaint_units], protect_masks, which="guarded", own_masks=own
    )
    erased = inpaint_all(rgb, mask)
    methods: dict[str, str] = {}
    mask_px: dict[str, int] = {}
    for unit in units:
        methods[unit.uid] = unit.info.method
        if unit.info.flat:
            _flat_fill(erased, unit.mask, unit.info.fill_color, blocked)
            if unit.extension is not None and extension_state.get(unit.uid) == "filled":
                _flat_fill(erased, unit.extension, unit.info.fill_color, blocked)
        mask_px[unit.uid] = int(np.count_nonzero(unit.mask.guarded)) + (
            int(np.count_nonzero(unit.extension.guarded)) if unit.extension is not None else 0
        )
    return EraseResult(erased, methods, mask_px, extension_state)
