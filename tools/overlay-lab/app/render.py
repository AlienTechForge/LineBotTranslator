"""Mode A, step 6 (part 2): draw the fitted text as an alpha mask and paste it into the image.

Text is always drawn in the unit's own (unrotated) frame onto an 'L' mask; the mask is then
rotated by the unit angle around the frame centre and painted with a single colour. Using a
mask (instead of drawing coloured text on an RGBA layer) avoids dark fringes around light text.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from PIL import Image, ImageDraw

from .fonts import CJK_FACE, FontSpec, load_font, pick_font
from .geometry import UnitGeometry
from .layout import (
    MAX_OVERFLOW,
    SIZE_CAP_RATIO,
    HLayout,
    VLayout,
    fit_horizontal,
    fit_vertical,
)

CENTERED_ROLES = {"title", "heading"}


@dataclass
class UnitRender:
    mask: Image.Image  # 'L'
    ox: float  # layer top-left relative to the frame top-left, in frame axes
    oy: float
    font_size: int
    fitted: bool
    overflowed: bool
    truncated: bool
    lines: int
    font_name: str
    bold: bool
    mode: str  # horizontal | vertical_cjk | vertical_latin


def _stroke(spec: FontSpec, size: int) -> int:
    return max(1, int(round(size / 16))) if spec.synthetic_bold else 0


def draw_horizontal_mask(
    layout: HLayout,
    spec: FontSpec,
    frame_w: float,
    frame_h: float,
    center: bool = False,
    reverse: bool = False,
) -> Image.Image:
    """Lines drawn left to right on a canvas of the frame width and 130% of its height."""
    canvas_w = int(math.ceil(frame_w))
    canvas_h = int(math.ceil(frame_h * (1.0 + MAX_OVERFLOW))) + 2
    mask = Image.new("L", (max(1, canvas_w), max(1, canvas_h)), 0)
    draw = ImageDraw.Draw(mask)
    font = load_font(spec, layout.size)
    ascent, descent = font.getmetrics()
    lines = list(reversed(layout.lines)) if reverse else layout.lines
    total = layout.block_height
    if len(lines) == 1 and total <= frame_h:
        top = (frame_h - total) / 2.0
    elif reverse:
        top = canvas_h - total
    else:
        top = 0.0
    stroke = _stroke(spec, layout.size)
    for i, line in enumerate(lines):
        width = font.getlength(line)
        x = (frame_w - width) / 2.0 if center else 0.0
        baseline = top + i * layout.pitch + (layout.pitch - (ascent + descent)) / 2.0 + ascent
        draw.text(
            (x, baseline), line, font=font, fill=255, anchor="ls", stroke_width=stroke, stroke_fill=255
        )
    return mask


def draw_vertical_mask(
    layout: VLayout, spec: FontSpec, frame_w: float, frame_h: float, rtl: bool = True
) -> tuple[Image.Image, float]:
    """Upright glyphs, one per cell, columns right-to-left (or left-to-right). Returns (mask, ox)."""
    n = len(layout.columns)
    need_w = layout.size + max(0, n - 1) * layout.pitch
    extra = max(0.0, need_w - frame_w)
    canvas_w = int(math.ceil(frame_w + extra))
    canvas_h = int(math.ceil(frame_h * (1.0 + MAX_OVERFLOW))) + 2
    mask = Image.new("L", (max(1, canvas_w), max(1, canvas_h)), 0)
    draw = ImageDraw.Draw(mask)
    font = load_font(spec, layout.size)
    stroke = _stroke(spec, layout.size)
    for c, column in enumerate(layout.columns):
        if rtl:
            cx = canvas_w - layout.size / 2.0 - c * layout.pitch
        else:
            cx = layout.size / 2.0 + c * layout.pitch
        for r, ch in enumerate(column):
            cy = r * layout.size + layout.size / 2.0
            draw.text((cx, cy), ch, font=font, fill=255, anchor="mm", stroke_width=stroke, stroke_fill=255)
    return mask, (-extra if rtl else 0.0)


def render_unit(
    text: str, direction: str, lang: str, role: str, geom: UnitGeometry, bold: bool
) -> UnitRender:
    """Fit and draw one unit's translation in its frame; no image is touched here."""
    fw, fh = geom.size
    cap = geom.glyph_h * SIZE_CAP_RATIO
    spec = pick_font(lang, text, bold)

    def font_for(size: int):
        return load_font(spec, size)

    if direction == "horizontal":
        layout = fit_horizontal(text, font_for, fw, fh, min(cap, fh))
        mask = draw_horizontal_mask(layout, spec, fw, fh, center=role in CENTERED_ROLES)
        return UnitRender(
            mask, 0.0, 0.0, layout.size, layout.fitted, layout.overflowed, layout.truncated,
            len(layout.lines), spec.name, bold, "horizontal",
        )  # fmt: skip

    rtl = direction == "vertical_rl"
    if lang in CJK_FACE:
        vlayout = fit_vertical(text, fw, fh, min(cap, fw))
        mask, ox = draw_vertical_mask(vlayout, spec, fw, fh, rtl=rtl)
        return UnitRender(
            mask, ox, 0.0, vlayout.size, vlayout.fitted, vlayout.overflowed, vlayout.truncated,
            len(vlayout.columns), spec.name, bold, "vertical_cjk",
        )  # fmt: skip

    # Latin target in a vertical unit: lay the text out horizontally in a frame whose width is
    # the column length, then turn it 90 degrees clockwise into the column.
    layout = fit_horizontal(text, font_for, fh, fw, min(cap, fw))
    horizontal = draw_horizontal_mask(
        layout, spec, fh, fw, center=role in CENTERED_ROLES, reverse=not rtl
    )
    mask = horizontal.transpose(Image.ROTATE_270)  # 90 degrees clockwise
    ox = (fw - mask.width) if rtl else 0.0
    return UnitRender(
        mask, ox, 0.0, layout.size, layout.fitted, layout.overflowed, layout.truncated,
        len(layout.lines), spec.name, bold, "vertical_latin",
    )  # fmt: skip


def paste_unit(
    base: Image.Image, unit: UnitRender, geom: UnitGeometry, color: tuple[int, int, int]
) -> list[int] | None:
    """Rotate the mask by the unit angle around the frame centre and paint it onto `base`.

    Parts of the mask that fall outside the image are clipped by Pillow's paste. Returns the
    axis-aligned bbox [x0, y0, x1, y1] of the painted ink inside the image, or None.
    """
    layer = unit.mask
    angle = geom.angle
    rotated = layer if abs(angle) < 1e-6 else layer.rotate(-angle, expand=True, resample=Image.BICUBIC)
    fw, fh = geom.size
    dx = unit.ox + layer.width / 2.0 - fw / 2.0
    dy = unit.oy + layer.height / 2.0 - fh / 2.0
    u, v = geom.axes
    cx = geom.center[0] + dx * u[0] + dy * v[0]
    cy = geom.center[1] + dx * u[1] + dy * v[1]
    px = int(round(cx - rotated.width / 2.0))
    py = int(round(cy - rotated.height / 2.0))
    base.paste(color, (px, py), rotated)
    bbox = rotated.point(lambda v: 255 if v >= 64 else 0).getbbox()
    if bbox is None:
        return None
    x0, y0 = max(0, px + bbox[0]), max(0, py + bbox[1])
    x1, y1 = min(base.width, px + bbox[2]), min(base.height, py + bbox[3])
    return [x0, y0, x1, y1] if x1 > x0 and y1 > y0 else None
