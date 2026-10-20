"""Fixtures for LAB_MOCK=1: a synthetic image, canned Vision words and canned Claude answers.

With LAB_MOCK=1 no network call is made: Vision returns the canned words (scaled to whatever
image was uploaded), Claude returns the canned units (Mode A) and the canned judge answer
(Mode B), and Mode B returns the input image unchanged. `make_fixture()` draws the matching
image (also served at /api/mock-image).
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass
from functools import lru_cache

from PIL import Image, ImageDraw

from .fonts import load_font, resolve_font
from .models import OcrResult, Word

FIXTURE_W, FIXTURE_H = 640, 480
BACKGROUND = (245, 239, 224)  # cream
INK = (25, 25, 25)
RED = (170, 35, 35)
BLUE = (25, 60, 140)
ROTATION_DEG = 15  # clockwise, visual


@dataclass
class Fixture:
    image: Image.Image
    words: list[Word]


def _rect_polygon(l: float, t: float, r: float, b: float, pad: float = 3.0):
    return [(l - pad, t - pad), (r + pad, t - pad), (r + pad, b + pad), (l - pad, b + pad)]


def _rotate_point(p, center, deg):
    t = math.radians(deg)
    x, y = p[0] - center[0], p[1] - center[1]
    return (center[0] + x * math.cos(t) - y * math.sin(t), center[1] + x * math.sin(t) + y * math.cos(t))


def _pseudo_glyph(draw: ImageDraw.ImageDraw, x: int, y: int, size: int, ink) -> None:
    """A square, CJK-looking blob (box with a cross) so the fixture needs no CJK font."""
    m = size // 8
    draw.rectangle([x + m, y + m, x + size - m, y + size - m], outline=ink, width=max(2, size // 12))
    draw.line([x + m, y + size // 2, x + size - m, y + size // 2], fill=ink, width=max(2, size // 14))
    draw.line([x + size // 2, y + m, x + size // 2, y + size - m], fill=ink, width=max(2, size // 14))


@lru_cache(maxsize=1)
def _build() -> Fixture:
    img = Image.new("RGB", (FIXTURE_W, FIXTURE_H), BACKGROUND)
    draw = ImageDraw.Draw(img)
    spec = resolve_font(False, bold=False)
    words: list[Word] = []

    def add(text: str, polygon) -> None:
        lang = "zh" if any(ord(ch) > 0x2E80 for ch in text) else "en"
        words.append(Word(id=f"w{len(words) + 1:04d}", text=text, polygon=[(float(x), float(y)) for x, y in polygon],
                          confidence=0.98, brk="SPACE", para=len({w.para for w in words}), lang=lang))  # fmt: skip

    # 1) horizontal title, three words
    font = load_font(spec, 34)
    x = 40.0
    for text in ("TONKATSU", "SET", "MENU"):
        box = draw.textbbox((x, 40), text, font=font)
        draw.text((x, 40), text, font=font, fill=INK)
        add(text, _rect_polygon(*box))
        x = box[2] + font.getlength(" ")

    # 2) a price that must be kept
    pfont = load_font(spec, 30)
    box = draw.textbbox((430, 44), "NT$120", font=pfont)
    draw.text((430, 44), "NT$120", font=pfont, fill=RED)
    add("NT$120", _rect_polygon(*box))

    # 3) a vertical column of five CJK-like glyphs, upright
    cell, cx, cy = 40, 556, 120
    for i in range(5):
        _pseudo_glyph(draw, cx, cy + i * cell, cell, INK)
    add("紅燒肉飯店", _rect_polygon(cx, cy, cx + cell, cy + 5 * cell, pad=4))

    # 4) a line turned 15 degrees clockwise, two words
    tfont = load_font(spec, 30)
    tmp = Image.new("L", (300, 48), 0)
    tdraw = ImageDraw.Draw(tmp)
    local = []
    lx = 6.0
    for text in ("LUNCH", "SPECIAL"):
        b = tdraw.textbbox((lx, 6), text, font=tfont)
        tdraw.text((lx, 6), text, font=tfont, fill=255)
        local.append((text, _rect_polygon(*b, pad=2)))
        lx = b[2] + tfont.getlength(" ")
    rotated = tmp.rotate(-ROTATION_DEG, expand=True, resample=Image.BICUBIC)
    px, py = 90, 250
    img.paste(BLUE, (px, py), rotated)
    c_in = (tmp.width / 2.0, tmp.height / 2.0)
    c_out = (rotated.width / 2.0, rotated.height / 2.0)
    for text, poly in local:
        out = []
        for p in poly:
            rx, ry = _rotate_point(p, c_in, ROTATION_DEG)
            out.append((px + c_out[0] + (rx - c_in[0]), py + c_out[1] + (ry - c_in[1])))
        add(text, out)
    return Fixture(img, words)


def make_fixture() -> Fixture:
    fx = _build()
    return Fixture(fx.image.copy(), [w.model_copy(deep=True) for w in fx.words])


def vision_words(width: int, height: int) -> OcrResult:
    """Canned Vision output, scaled to the uploaded image size."""
    sx, sy = width / FIXTURE_W, height / FIXTURE_H
    words = []
    for w in _build().words:
        words.append(w.model_copy(update={"polygon": [(x * sx, y * sy) for x, y in w.polygon]}))
    return OcrResult(width=width, height=height, words=words, text=" ".join(w.text for w in words))


def judge_response(source_lines: list[str]) -> tuple[str, int, int]:
    """Canned judge answer: (json text, input tokens, output tokens).

    The mock Mode B returns the input image, so the 'output' is the source itself: a judge that
    reads it finds nothing wrong. The reference translation just marks each source line.
    """
    answer = {
        "reference_translation": [f"(mock) {line}" for line in source_lines],
        "coverage": 1.0,
        "garbled_segments": [],
        "untranslated_segments": [],
        "mistranslated_segments": [],
        "altered_numbers": [],
        "summary": "Mock judge: a canned answer, nothing was actually checked.",
    }
    return json.dumps(answer, ensure_ascii=False), 1800, 420


def claude_response(ocr: OcrResult) -> tuple[str, int, int]:
    """Canned model answer: (json text, input tokens, output tokens)."""
    ids = [w.id for w in ocr.words]
    if len(ids) < 7:
        return json.dumps({"units": []}), 100, 10
    units = [
        {"word_ids": ids[0:3], "source_text": "TONKATSU SET MENU", "translation": "Pork Cutlet Set Menu",
         "direction": "horizontal", "rotated": False, "role": "title", "keep": False, "notes": ""},
        {"word_ids": [ids[3]], "source_text": "NT$120", "translation": "NT$120",
         "direction": "horizontal", "rotated": False, "role": "price", "keep": True, "notes": "price"},
        {"word_ids": [ids[4]], "source_text": "紅燒肉飯店", "translation": "Braised pork rice shop",
         "direction": "vertical_rl", "rotated": False, "role": "label", "keep": False, "notes": ""},
        {"word_ids": ids[5:7], "source_text": "LUNCH SPECIAL", "translation": "Today's special lunch",
         "direction": "horizontal", "rotated": True, "role": "body", "keep": False, "notes": ""},
    ]  # fmt: skip
    return json.dumps({"units": units}, ensure_ascii=False), 2345, 612
