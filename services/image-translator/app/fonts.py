"""Font discovery. The Docker image ships Noto Sans (Latin), Noto Sans CJK and DejaVu Sans; other hosts fall back."""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from PIL import ImageFont

from .config import normalize_lang

log = logging.getLogger("lab.fonts")

FONT_ROOTS = [Path("/usr/share/fonts"), Path("/usr/local/share/fonts"), Path.home() / ".fonts"]

# target language -> suffix of the Noto Sans CJK face name (zh-HK is drawn with the zh-TW face)
CJK_FACE = {"zh-TW": "TC", "zh-CN": "SC", "zh-HK": "TC", "ja": "JP", "ko": "KR"}
DEFAULT_CJK_FACE = "TC"

# Scripts that Noto Sans (Latin, Cyrillic, Greek) does not cover: one Noto face each (fonts-noto-core).
SCRIPT_BY_LANG = {"th": "thai", "ar": "arabic", "he": "hebrew", "hi": "devanagari"}
SCRIPT_FONT_PATTERNS: dict[str, tuple[list[str], list[str]]] = {  # script -> (regular patterns, bold patterns)
    "thai": (
        ["NotoSansThai-Regular.*", "NotoSansThai[[]*", "NotoSansThaiUI-Regular.*"],
        ["NotoSansThai-Bold.*", "NotoSansThaiUI-Bold.*"],
    ),
    "arabic": (
        ["NotoSansArabic-Regular.*", "NotoSansArabic[[]*", "NotoSansArabicUI-Regular.*", "NotoNaskhArabic-Regular.*"],
        ["NotoSansArabic-Bold.*", "NotoSansArabicUI-Bold.*", "NotoNaskhArabic-Bold.*"],
    ),
    "hebrew": (["NotoSansHebrew-Regular.*", "NotoSansHebrew[[]*"], ["NotoSansHebrew-Bold.*"]),
    "devanagari": (
        ["NotoSansDevanagari-Regular.*", "NotoSansDevanagari[[]*", "NotoSansDevanagariUI-Regular.*"],
        ["NotoSansDevanagari-Bold.*", "NotoSansDevanagariUI-Bold.*"],
    ),
}
RTL_LANGS = frozenset({"ar", "he"})  # drawn as-is (no bidi, no shaping): see font_notes

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


def is_cjk_lang(lang: str) -> bool:
    """A Chinese, Japanese or Korean target (zh-TW, zh-CN, zh-HK, ja, ko), whatever its spelling."""
    return normalize_lang(lang)[0] in CJK_FACE


def face_for_language(lang: str) -> str:
    return CJK_FACE.get(normalize_lang(lang)[0], DEFAULT_CJK_FACE)


def _base(lang: str) -> str:
    return normalize_lang(lang)[0].split("-")[0].lower()


@lru_cache(maxsize=16)
def resolve_script_font(script: str, bold: bool = False) -> FontSpec | None:
    """The Noto face of a script (Thai, Arabic, Hebrew, Devanagari), or None when it is not installed."""
    regular, heavy = SCRIPT_FONT_PATTERNS[script]
    path = _find(heavy if bold else regular)
    synthetic = False
    if path is None and bold:
        path = _find(regular)
        synthetic = path is not None
    if path is None:
        return None
    return FontSpec(str(path), 0, path.stem, synthetic, False)


def pick_font(lang: str, text: str, bold: bool) -> FontSpec:
    """CJK font for CJK targets (or whenever the text has CJK characters), the Noto face of the script
    for Thai, Arabic, Hebrew and Devanagari when it is installed, else Noto Sans / DejaVu."""
    from .layout import contains_cjk

    if is_cjk_lang(lang) or contains_cjk(text):
        return resolve_font(True, face_for_language(lang), bold)
    script = SCRIPT_BY_LANG.get(_base(lang))
    if script:
        spec = resolve_script_font(script, bold)
        if spec is not None:
            return spec
    return resolve_font(False, face_for_language(lang), bold)


def font_notes(lang: str) -> list[str]:
    """What a reader of the response should know about drawing this language (empty for the usual ones)."""
    code = normalize_lang(lang)[0]
    notes: list[str] = []
    script = SCRIPT_BY_LANG.get(_base(lang))
    if script and resolve_script_font(script, False) is None:
        notes.append(
            f"no Noto Sans face for the {script} script is installed (fonts-noto-core): {code} is drawn with a "
            "fallback face and the glyphs may be missing"
        )
    if _base(lang) in RTL_LANGS:
        notes.append(f"{code} is written right to left: it is drawn as-is, without bidi or shaping, so the picture is likely wrong")
    if is_cjk_lang(lang) and resolve_font(True, face_for_language(lang), False).path is None:
        notes.append("no CJK font is installed (fonts-noto-cjk): CJK glyphs are drawn as boxes")
    return notes
