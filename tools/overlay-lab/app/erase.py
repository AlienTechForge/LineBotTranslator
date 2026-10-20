"""Mode A, step 4: erase the source glyphs without smearing neighbours over them.

Round 2 dilated every word mask and inpainted all of them in one OpenCV call. On a receipt the
dilated masks reached into the black price cells and the table rules next to the dish names, and
Telea inpainting pulled that black into the white cells. Now each translated unit is looked at on
its own:

* its background is measured on the pixels of its own (undilated) box: the dominant colour, and the
  spread (per-channel std) of the pixels that are not ink (ink = far from that colour);
* a flat background (spread at most FLAT_STD_MAX) is erased by **flat fill** with that colour,
  edges feathered by a pixel or two; anything else is **inpainted** (OpenCV Telea);
* in both cases the dilation of the glyph mask is **colour-guarded**: it only grows over pixels
  whose colour is within a tolerance of the unit's background colour, so it never reaches into a
  black cell, a rule line or any neighbour of another colour (for inpainting, not even within the
  radius Telea reads from);
* the words that are not translated keep their pixels.
"""

from __future__ import annotations

from dataclasses import dataclass

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
FEATHER_SIGMA = 1.0  # px; the fill fades out over about two pixels, only over background-coloured pixels


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
    flat: bool
    tolerance: float  # RGB distance from `color` that still counts as background for the guard

    @property
    def method(self) -> str:
        return "flat" if self.flat else "inpaint"

    def to_dict(self) -> dict:
        return {
            "color": list(self.color),
            "spread": round(self.spread, 2),
            "ink_share": round(self.ink_share, 3),
            "flat": self.flat,
            "tolerance": round(self.tolerance, 1),
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


def measure_background(rgb: np.ndarray, um: UnitMask) -> BackgroundInfo:
    """Colour and flatness of the background of one unit, measured on its own undilated box.

    The background colour is the dominant colour of the box; when a second colour covers a good share
    of it (bold or inverted text) the one closer to the ring just outside the box wins. Ink is every
    pixel farther than INK_DISTANCE from that colour. The background is flat when the non-ink pixels
    have a per-channel std of at most FLAT_STD_MAX, there are enough of them and the ink does not
    take over (MAX_INK_SHARE).
    """
    roi = um.roi(rgb)
    raw = um.raw > 0
    pixels = roi[raw].astype(np.float32)
    if pixels.shape[0] < 4:
        fallback = tuple(int(c) for c in np.median(roi.reshape(-1, 3), axis=0))
        return BackgroundInfo(fallback, 255.0, 441.0, 1.0, 0, False, GUARD_TOLERANCE)  # type: ignore[arg-type]
    candidates = _dominant_colors(pixels)
    color = candidates[0][0]
    ring = roi[(um.dilated > 0) & ~raw].astype(np.float32)
    if len(candidates) > 1 and candidates[1][1] >= SECOND_COLOR_MIN_SHARE and ring.shape[0] >= 8:
        around = np.median(ring, axis=0)
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
    flat = count >= MIN_BG_PIXELS and spread <= FLAT_STD_MAX and ink_share <= MAX_INK_SHARE
    tolerance = min(GUARD_TOLERANCE_MAX, max(GUARD_TOLERANCE, GUARD_SPREAD_K * (0.0 if spread >= 255 else spread_norm)))
    return BackgroundInfo(
        tuple(int(round(c)) for c in color), spread, spread_norm, ink_share, count, flat, tolerance  # type: ignore[arg-type]
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


def _flat_fill(out: np.ndarray, um: UnitMask, color: tuple[int, int, int], blocked: np.ndarray) -> None:
    """Fill the guarded area of one unit with its background colour, feathered over background pixels only."""
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
    for unit in units:
        guard_dilation(rgb, unit.mask, unit.info)
        if unit.extension is not None:
            # the grown part is flat by construction (the lane was checked); guard it all the same
            extension = unit.extension
            roi = extension.roi(rgb).astype(np.float32)
            ok = np.linalg.norm(roi - np.asarray(unit.info.color, np.float32), axis=2) <= unit.info.tolerance
            extension.raw = ((extension.raw > 0) & ok).astype(np.uint8) * 255
            extension.guarded = extension.raw.copy()
            extension.allowed = ok
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
            _flat_fill(erased, unit.mask, unit.info.color, blocked)
            if unit.extension is not None:
                _flat_fill(erased, unit.extension, unit.info.color, blocked)
        mask_px[unit.uid] = int(np.count_nonzero(unit.mask.guarded)) + (
            int(np.count_nonzero(unit.extension.guarded)) if unit.extension is not None else 0
        )
    return EraseResult(erased, methods, mask_px)
