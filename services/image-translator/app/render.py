"""Mode A, step 6 (part 2): fit the text readably, draw it as an alpha mask and paste it into the image.

Text is always drawn in the unit's own (unrotated) frame onto an 'L' mask; the mask is then
rotated by the unit angle around the frame centre and painted with a single colour. Using a mask
(instead of drawing coloured text on an RGBA layer) avoids dark fringes around light text.

`render_unit` decides what is drawn, in this order, so that the text stays readable:

1. the full translation in the frame, 2. the full translation in a frame grown sideways into the
free lane (up to MAX_GROW x the original length, following the planner's `align`), 3. the planner's
`short` alternative the same two ways. "Readable" means a font size of at least MIN_FIT_RATIO of the
source glyph height. When nothing is readable a minor unit is skipped (left untouched) and a major
one is shrunk to the minimum size, as before.

Round 7: a vertical unit of a non-CJK target whose column is narrower than MIN_ROTATED_COLUMN_PX is not drawn
at all (`skipped = "column too narrow"`): rotated text at that size is unreadable and costs more than the
untranslated column does. The CJK targets keep their upright vertical columns whatever the width.

Round 6: `target_size` asks for one exact size (the size the rows of a group share, see
pipeline_a.render_units): the same order of attempts, but only a layout that reaches that size
counts, and a unit that cannot reach it comes back as None instead of being skipped or shrunk.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

from PIL import Image, ImageDraw

from .config import min_rotated_column_px
from .fonts import FontSpec, is_cjk_lang, load_font, pick_font
from .geometry import UnitGeometry
from .layout import (
    GROW_STEPS,
    MAX_GROW,
    MAX_OVERFLOW,
    MIN_FIT_RATIO,
    MIN_FONT_PX,
    SIZE_CAP_RATIO,
    HLayout,
    VLayout,
    fit_horizontal,
    fit_vertical,
)

ORPHAN_ROLES = {"title", "heading"}  # these may not end with a single character or short word on a line
SKIP_TOO_SMALL = "too small"
SKIP_NARROW_COLUMN = "column too narrow"  # a vertical column of a Latin-target image below MIN_ROTATED_COLUMN_PX


@dataclass
class UnitRender:
    mask: Image.Image | None  # 'L'; None when the unit was skipped
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
    text_used: str = "full"  # full | short
    geom: UnitGeometry | None = None  # the frame the mask was drawn for (grown or not)
    grow: tuple[float, float] = (0.0, 0.0)  # px the frame grew against / along its reading axis
    readable: bool = True  # the font size reaches MIN_FIT_RATIO of the source glyph height
    min_fit_px: int = 0
    skipped: str | None = None
    notes: list[str] = field(default_factory=list)


def _stroke(spec: FontSpec, size: int) -> int:
    return max(1, int(round(size / 16))) if spec.synthetic_bold else 0


def _start_x(align: str, frame_w: float, width: float) -> float:
    if align == "center":
        return (frame_w - width) / 2.0
    if align == "right":
        return frame_w - width
    return 0.0


def draw_horizontal_mask(
    layout: HLayout,
    spec: FontSpec,
    frame_w: float,
    frame_h: float,
    align: str = "left",
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
        x = _start_x(align, frame_w, font.getlength(line))
        baseline = top + i * layout.pitch + (layout.pitch - (ascent + descent)) / 2.0 + ascent
        draw.text(
            (x, baseline), line, font=font, fill=255, anchor="ls", stroke_width=stroke, stroke_fill=255
        )
    return mask


def draw_vertical_mask(
    layout: VLayout, spec: FontSpec, frame_w: float, frame_h: float, rtl: bool = True, align: str = "left"
) -> tuple[Image.Image, float]:
    """Upright glyphs, one per cell, columns right-to-left (or left-to-right). Returns (mask, ox).

    `align` places each column along the frame: left = from the top, center, right = from the bottom.
    """
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
        top = max(0.0, _start_x(align, frame_h, len(column) * layout.size))
        for r, ch in enumerate(column):
            cy = top + r * layout.size + layout.size / 2.0
            draw.text((cx, cy), ch, font=font, fill=255, anchor="mm", stroke_width=stroke, stroke_fill=255)
    return mask, (-extra if rtl else 0.0)


def growth_limits(
    length: float, lane: tuple[float, float], align: str, max_grow: float = MAX_GROW
) -> tuple[float, float]:
    """(against, along): how far the frame may grow either way along its reading axis.

    `lane` is the free room either way (geometry.free_lane, already limited to flat background).
    Left-aligned text grows along the axis (to the right), right-aligned against it (to the left),
    centred text both ways by the same amount; the total never exceeds (max_grow - 1) x `length`.
    """
    extra = max(0.0, (max_grow - 1.0) * length)
    against, along = max(0.0, lane[0]), max(0.0, lane[1])
    if align == "right":
        return min(against, extra), 0.0
    if align == "center":
        side = min(against, along, extra / 2.0)
        return side, side
    return 0.0, min(along, extra)


def render_unit(
    text: str,
    direction: str,
    lang: str,
    role: str,
    geom: UnitGeometry,
    bold: bool,
    *,
    short: str = "",
    align: str = "left",
    importance: str = "major",
    lane: tuple[float, float] = (0.0, 0.0),
    min_fit_ratio: float = MIN_FIT_RATIO,
    max_grow: float = MAX_GROW,
    target_size: int | None = None,
    column_px: float | None = None,
) -> UnitRender | None:
    """Fit and draw one unit's translation in its frame; no image is touched here.

    See the module docstring for the order of the attempts. `lane` is the free room along the
    reading axis either way; the result says which text was used (`text_used`), how far the frame
    grew (`grow`, with `geom` the frame that was drawn for) and whether the size is readable.
    A minor unit that cannot be fitted readably comes back with `skipped = "too small"` and no mask.
    With `target_size` only a layout of exactly that size is accepted (full text before short, the
    smallest growth first); when none reaches it the result is None. `column_px` is the width of the
    source column for the narrow-column rule (default: the frame's own glyph size).
    """
    cap = float(target_size) if target_size is not None else geom.glyph_h * SIZE_CAP_RATIO
    spec = pick_font(lang, text + short, bold)
    vertical = direction != "horizontal"
    own_floor = max(1, math.ceil(min_fit_ratio * geom.glyph_h - 1e-9))
    narrow = (geom.glyph_h if column_px is None else column_px) < min_rotated_column_px()
    if target_size is None and vertical and narrow and not is_cjk_lang(lang):
        return UnitRender(
            None, 0.0, 0.0, 0, False, False, False, 0, spec.name, bold, "vertical_latin",
            geom=geom, readable=False, min_fit_px=own_floor, skipped=SKIP_NARROW_COLUMN,
        )  # fmt: skip
    axis = 1 if vertical else 0
    floor = target_size if target_size is not None else own_floor
    avoid_orphan = role in ORPHAN_ROLES and not vertical
    if not short.strip() or len(short.strip()) >= len(text.strip()):
        short = ""

    def font_for(size: int):
        return load_font(spec, size)

    def fit(txt: str, frame: UnitGeometry, min_size: int):
        fw, fh = frame.size
        if not vertical:
            return fit_horizontal(txt, font_for, fw, fh, min(cap, fh), min_size, avoid_orphan=avoid_orphan)
        if is_cjk_lang(lang):
            return fit_vertical(txt, fw, fh, min(cap, fw), min_size)
        # Latin text in a vertical unit: laid out horizontally on a line as long as the column, turned later
        return fit_horizontal(txt, font_for, fh, fw, min(cap, fw), min_size)

    start_max, end_max = growth_limits(geom.size[axis], lane, align, max_grow)
    fractions = [0.0]
    if start_max > 0.5 or end_max > 0.5:
        fractions += [i / GROW_STEPS for i in range(1, GROW_STEPS + 1)]
    frames = [(geom.extended(axis, start_max * f, end_max * f), (start_max * f, end_max * f)) for f in fractions]

    chosen = None
    for used, txt in (("full", text), ("short", short)):
        if not txt:
            continue
        for frame, grown in frames:
            layout = fit(txt, frame, floor)
            if layout.fitted and (target_size is None or layout.size >= target_size):
                chosen = (used, txt, frame, grown, layout, True)
                break
        if chosen:
            break
    if chosen is None and target_size is not None:
        return None
    if chosen is None:
        if importance == "minor":
            return UnitRender(
                None, 0.0, 0.0, 0, False, False, False, 0, spec.name, bold, "horizontal",
                geom=geom, readable=False, min_fit_px=own_floor, skipped=SKIP_TOO_SMALL,
            )  # fmt: skip
        # nothing is readable: the shorter text, in the widest frame, at whatever size it takes (10 px at least)
        used, txt = ("short", short) if short else ("full", text)
        frame, grown = frames[-1]
        chosen = (used, txt, frame, grown, fit(txt, frame, MIN_FONT_PX), False)
    used, txt, frame, grown, layout, readable = chosen

    fw, fh = frame.size
    rtl = direction == "vertical_rl"
    if not vertical:
        mask = draw_horizontal_mask(layout, spec, fw, fh, align=align)
        ox, oy, count, mode = 0.0, 0.0, len(layout.lines), "horizontal"
    elif is_cjk_lang(lang):
        mask, ox = draw_vertical_mask(layout, spec, fw, fh, rtl=rtl, align=align)
        oy, count, mode = 0.0, len(layout.columns), "vertical_cjk"
    else:
        # turn the horizontal layout 90 degrees clockwise into the column: it reads top to bottom,
        # one line per column (the first line at the right edge for vertical_rl, the left for _lr)
        horizontal = draw_horizontal_mask(layout, spec, fh, fw, align=align, reverse=not rtl)
        mask = horizontal.transpose(Image.ROTATE_270)  # 90 degrees clockwise
        ox, oy, count, mode = ((fw - mask.width) if rtl else 0.0), 0.0, len(layout.lines), "vertical_latin"
    return UnitRender(
        mask, ox, oy, layout.size, layout.fitted, layout.overflowed, layout.truncated, count, spec.name, bold, mode,
        text_used=used, geom=frame, grow=grown, readable=readable, min_fit_px=own_floor, notes=list(layout.notes),
    )  # fmt: skip


def paste_unit(
    base: Image.Image, unit: UnitRender, geom: UnitGeometry, color: tuple[int, int, int]
) -> list[int] | None:
    """Rotate the mask by the unit angle around the frame centre and paint it onto `base`.

    `geom` is the frame the unit was drawn for (a grown unit carries its own in `unit.geom`, which
    takes precedence). Parts of the mask that fall outside the image are clipped by Pillow's paste.
    Returns the axis-aligned bbox [x0, y0, x1, y1] of the painted ink inside the image, or None.
    """
    if unit.mask is None:
        return None
    geom = unit.geom or geom
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
