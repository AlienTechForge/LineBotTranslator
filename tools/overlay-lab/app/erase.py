"""Mode A, step 4: erase the source glyphs by inpainting (one OpenCV call for all units)."""

from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

from .models import Word

INPAINT_RADIUS = 3
MIN_DILATE_PX = 2
DILATE_RATIO = 0.15


def dilation_px(glyph_h: float) -> int:
    """Dilation radius: max(2 px, 15% of the glyph height)."""
    return max(MIN_DILATE_PX, int(round(DILATE_RATIO * glyph_h)))


@dataclass
class UnitMask:
    """Masks of one unit, stored on a small ROI instead of the whole image."""

    x0: int
    y0: int
    raw: np.ndarray  # uint8 0/255, the undilated union of the word polygons
    dilated: np.ndarray  # uint8 0/255, raw dilated by dilation_px

    @property
    def shape(self) -> tuple[int, int]:
        return self.raw.shape  # type: ignore[return-value]

    def paste_into(self, target: np.ndarray, which: str = "dilated") -> None:
        src = self.dilated if which == "dilated" else self.raw
        h, w = src.shape
        y1, x1 = self.y0 + h, self.x0 + w
        view = target[self.y0 : y1, self.x0 : x1]
        np.bitwise_or(view, src[: view.shape[0], : view.shape[1]], out=view)


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


def build_global_mask(
    image_hw: tuple[int, int],
    unit_masks: list[UnitMask],
    protect_masks: list[UnitMask] | None = None,
) -> np.ndarray:
    """OR of the dilated unit masks, minus the raw masks of words we do not translate.

    Words that are kept or not listed keep their pixels even when a neighbour's dilation
    would have reached them, unless a translated word's own polygon covers the same pixels.
    """
    img_h, img_w = image_hw
    mask = np.zeros((img_h, img_w), np.uint8)
    own = np.zeros((img_h, img_w), np.uint8)
    for um in unit_masks:
        um.paste_into(mask, "dilated")
        um.paste_into(own, "raw")
    if protect_masks:
        protect = np.zeros((img_h, img_w), np.uint8)
        for pm in protect_masks:
            pm.paste_into(protect, "raw")
        mask[(protect > 0) & (own == 0)] = 0
    return mask


def inpaint_all(rgb: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """One Telea inpaint over the whole image for every translated unit together."""
    if not mask.any():
        return rgb.copy()
    return cv2.inpaint(rgb, mask, INPAINT_RADIUS, cv2.INPAINT_TELEA)
