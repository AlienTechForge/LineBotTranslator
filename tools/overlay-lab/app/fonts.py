"""Font discovery. The Docker image ships Noto Sans (Latin), Noto Sans CJK and DejaVu Sans; other hosts fall back."""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from PIL import ImageFont

log = logging.getLogger("lab.fonts")

FONT_ROOTS = [Path("/usr/share/fonts"), Path("/usr/local/share/fonts"), Path.home() / ".fonts"]

# target language -> suffix of the Noto Sans CJK face name
CJK_FACE = {"zh-TW": "TC", "zh-CN": "SC", "ja": "JP", "ko": "KR"}
DEFAULT_CJK_FACE = "TC"

CJK_REGULAR_PATTERNS = ["NotoSansCJK*-Regular.*", "NotoSansCJK-Regular.*"]
CJK_BOLD_PATTERNS = ["NotoSansCJK*-Bold.*", "NotoSansCJK-Bold.*"]
CJK_FALLBACKS = ["wqy-zenhei.ttc", "ipag.ttf", "fonts-japanese-gothic.ttf", "DroidSansFallback*.ttf"]
# Noto Sans (the fonts-noto-core package) first, then the fallbacks of earlier rounds
LATIN_REGULAR = ["NotoSans-Regular.ttf", "NotoSans-Regular.otf", "DejaVuSans.ttf", "LiberationSans-Regular.ttf", "FreeSans.ttf"]
LATIN_BOLD = ["NotoSans-Bold.ttf", "NotoSans-Bold.otf", "DejaVuSans-Bold.ttf", "LiberationSans-Bold.ttf", "FreeSansBold.ttf"]


@dataclass(frozen=True)
class FontSpec:
    path: str | None  # None -> Pillow's built-in font (no CJK glyphs)
    index: int
    name: str
    synthetic_bold: bool
    cjk: bool


def _find(patterns: list[str]) -> Path | None:
    for pattern in patterns:
        for root in FONT_ROOTS:
            if not root.exists():
                continue
            hits = sorted(root.rglob(pattern))
            if hits:
                return hits[0]
    return None


def _face_index(path: Path, face: str) -> tuple[int, str]:
    """Index of the Noto Sans CJK face for `face` (TC/SC/JP/KR/HK) inside a collection."""
    if path.suffix.lower() != ".ttc":
        return 0, path.stem
    first = ""
    for i in range(12):
        try:
            name = ImageFont.truetype(str(path), 20, index=i).getname()[0]
        except OSError:
            break
        if i == 0:
            first = name
        if name.rstrip().endswith(" " + face):
            return i, name
    return 0, first or path.stem


@lru_cache(maxsize=64)
def resolve_font(cjk: bool, face: str = DEFAULT_CJK_FACE, bold: bool = False) -> FontSpec:
    """Pick a font file. Environment overrides: LAB_FONT_CJK[_BOLD], LAB_FONT_LATIN[_BOLD]."""
    kind = "CJK" if cjk else "LATIN"
    override = os.environ.get(f"LAB_FONT_{kind}_BOLD" if bold else f"LAB_FONT_{kind}")
    if override and Path(override).is_file():
        path: Path | None = Path(override)
        index, name = _face_index(path, face) if cjk else (0, path.stem)
        return FontSpec(str(path), index, name, False, cjk)

    if cjk:
        wanted = CJK_BOLD_PATTERNS if bold else CJK_REGULAR_PATTERNS
        path = _find(wanted)
        synthetic = False
        if path is None and bold:
            path = _find(CJK_REGULAR_PATTERNS)
            synthetic = path is not None
        if path is None:
            path = _find(CJK_FALLBACKS)
            synthetic = bold and path is not None
        if path is not None:
            index, name = _face_index(path, face)
            return FontSpec(str(path), index, name, synthetic, True)
        log.warning("no CJK font found; CJK glyphs will render as boxes (install fonts-noto-cjk)")
        return FontSpec(None, 0, "PIL default (no CJK)", bold, True)

    path = _find(LATIN_BOLD if bold else LATIN_REGULAR)
    synthetic = False
    if path is None and bold:
        path = _find(LATIN_REGULAR)
        synthetic = path is not None
    if path is not None:
        return FontSpec(str(path), 0, path.stem, synthetic, False)
    return FontSpec(None, 0, "PIL default", bold, False)


@lru_cache(maxsize=512)
def load_font(spec: FontSpec, size: int) -> ImageFont.FreeTypeFont:
    if spec.path is None:
        return ImageFont.load_default(size=max(1, int(size)))  # type: ignore[return-value]
    return ImageFont.truetype(spec.path, max(1, int(size)), index=spec.index)


def face_for_language(lang: str) -> str:
    return CJK_FACE.get(lang, DEFAULT_CJK_FACE)


def pick_font(lang: str, text: str, bold: bool) -> FontSpec:
    """CJK font for CJK targets, or whenever the text itself contains CJK characters."""
    from .layout import contains_cjk

    cjk = lang in CJK_FACE or contains_cjk(text)
    return resolve_font(cjk, face_for_language(lang), bold)
