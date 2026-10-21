"""Font discovery: Noto Sans (Latin) first, DejaVu and the others as fallbacks, CJK as before."""

import shutil
from pathlib import Path

import pytest

from app import fonts


@pytest.fixture
def roots(monkeypatch, tmp_path):
    """An empty font root the tests fill; the real system fonts are out of reach."""
    monkeypatch.setattr(fonts, "FONT_ROOTS", [tmp_path])
    for name in ("LAB_FONT_LATIN", "LAB_FONT_LATIN_BOLD", "LAB_FONT_CJK", "LAB_FONT_CJK_BOLD"):
        monkeypatch.delenv(name, raising=False)
    fonts.resolve_font.cache_clear()
    yield tmp_path
    fonts.resolve_font.cache_clear()


_SYSTEM_TTF = fonts.resolve_font(False).path  # resolved at import time, before a test points FONT_ROOTS elsewhere


def some_ttf() -> Path:
    if _SYSTEM_TTF is None or not _SYSTEM_TTF.lower().endswith(".ttf"):
        pytest.skip("no TrueType font on this host to copy")
    return Path(_SYSTEM_TTF)


def put(root: Path, name: str, src: Path) -> Path:
    target = root / "truetype" / "noto" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(src, target)
    return target


def test_noto_sans_is_preferred_over_dejavu_for_latin_regular_and_bold(roots):
    src = some_ttf()
    fonts.resolve_font.cache_clear()
    put(roots, "DejaVuSans.ttf", src)
    put(roots, "DejaVuSans-Bold.ttf", src)
    noto = put(roots, "NotoSans-Regular.ttf", src)
    noto_bold = put(roots, "NotoSans-Bold.ttf", src)
    regular, bold = fonts.resolve_font(False, bold=False), fonts.resolve_font(False, bold=True)
    assert Path(regular.path) == noto and Path(bold.path) == noto_bold
    assert not regular.synthetic_bold and not bold.synthetic_bold and not regular.cjk


def test_without_noto_the_earlier_fallbacks_still_work(roots):
    src = some_ttf()
    fonts.resolve_font.cache_clear()
    dejavu = put(roots, "DejaVuSans.ttf", src)
    assert Path(fonts.resolve_font(False).path) == dejavu
    # only a regular face installed: bold is synthesised from it
    bold = fonts.resolve_font(False, bold=True)
    assert Path(bold.path) == dejavu and bold.synthetic_bold


def test_noto_sans_cjk_is_not_mistaken_for_the_latin_face(roots):
    src = some_ttf()
    fonts.resolve_font.cache_clear()
    put(roots, "NotoSansCJK-Regular.ttc", src)  # a CJK collection must never be picked for Latin text
    dejavu = put(roots, "DejaVuSans.ttf", src)
    assert Path(fonts.resolve_font(False).path) == dejavu


def test_the_environment_override_still_wins(roots, monkeypatch):
    src = some_ttf()
    fonts.resolve_font.cache_clear()
    put(roots, "NotoSans-Regular.ttf", src)
    mine = roots / "mine.ttf"
    shutil.copy(src, mine)
    monkeypatch.setenv("LAB_FONT_LATIN", str(mine))
    fonts.resolve_font.cache_clear()
    assert Path(fonts.resolve_font(False).path) == mine


def test_the_dockerfile_installs_noto_sans_next_to_the_cjk_and_dejavu_fonts():
    dockerfile = Path(__file__).resolve().parents[1] / "Dockerfile"
    if not dockerfile.exists():
        pytest.skip("Dockerfile not available")
    text = dockerfile.read_text(encoding="utf-8")
    for package in ("fonts-noto-core", "fonts-noto-cjk", "fonts-dejavu-core"):
        assert package in text, package
