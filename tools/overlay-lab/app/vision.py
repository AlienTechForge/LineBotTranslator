"""Google Cloud Vision wrapper: words (with detected languages) and full text for Mode A and Mode B checks."""

from __future__ import annotations

import logging
import time
from functools import lru_cache

from .config import REQUEST_TIMEOUT_S, get_settings
from .models import ModeError, OcrResult, Word, base_language, polygon_height

log = logging.getLogger("lab.vision")


@lru_cache(maxsize=1)
def _client():
    from google.cloud import vision  # imported lazily so mock mode needs no credentials

    return vision.ImageAnnotatorClient()


def _annotate(image_bytes: bytes):
    from google.cloud import vision

    try:
        client = _client()
    except Exception as exc:  # noqa: BLE001 - DefaultCredentialsError and friends
        raise ModeError(
            "Google Vision credentials are not usable "
            f"({type(exc).__name__}: {exc}). Set GOOGLE_APPLICATION_CREDENTIALS to the "
            "service-account JSON."
        ) from exc
    response = client.document_text_detection(
        image=vision.Image(content=image_bytes), timeout=REQUEST_TIMEOUT_S
    )
    if response.error and response.error.message:
        raise ModeError(f"Vision API error: {response.error.message}")
    return response


def _languages(prop) -> list[tuple[str, float]]:
    """`detected_languages` of a Vision `property` message as (base code, confidence), best first.

    Script subtags are dropped ('zh-Hant' -> 'zh'), undetermined codes ('und') are ignored and
    two codes with the same base keep the higher confidence.
    """
    try:
        raw = list(prop.detected_languages)
    except AttributeError:
        return []
    best: dict[str, float] = {}
    for item in raw:
        code = base_language(item.language_code)
        if code:
            best[code] = max(best.get(code, 0.0), float(item.confidence or 0.0))
    return sorted(best.items(), key=lambda pair: -pair[1])


def _best_language(prop) -> str | None:
    langs = _languages(prop)
    return langs[0][0] if langs else None


def ocr_words(image_bytes: bytes, width: int, height: int) -> OcrResult:
    """Run document_text_detection and flatten the hierarchy to a word list (ids w0001...).

    Every word carries `detected_languages` (what Vision said about the word itself, when it
    said anything) and `lang`, the language the checks use: the best of those or, when Vision
    gave none for the word, the one of its paragraph, block or page (None when there is no
    information), its `confidence` (the digit check treats low-confidence handwriting softly) and
    its box `height` in px (the verification flags a leftover word much bigger than the rest).
    `OcrResult.text` is Vision's full text. Used for the source image (Mode A and
    the Mode B verification share one call per job) and for each Mode B output.
    """
    if get_settings().mock:
        from . import mock

        return mock.vision_words(width, height)

    t0 = time.perf_counter()
    response = _annotate(image_bytes)
    words: list[Word] = []
    page_w, page_h = width, height
    para_index = 0
    annotation = response.full_text_annotation
    pages = annotation.pages if annotation else []
    for page_no, page in enumerate(pages):
        if page_no == 0 and page.width and page.height:
            page_w, page_h = int(page.width), int(page.height)
        page_lang = _best_language(page.property)
        for block in page.blocks:
            block_lang = _best_language(block.property) or page_lang
            for paragraph in block.paragraphs:
                para_lang = _best_language(paragraph.property) or block_lang
                for word in paragraph.words:
                    vertices = [(float(v.x), float(v.y)) for v in word.bounding_box.vertices]
                    if len(vertices) < 3:
                        continue
                    while len(vertices) < 4:  # degenerate polygon: repeat the last point
                        vertices.append(vertices[-1])
                    text = "".join(sym.text for sym in word.symbols)
                    brk = None
                    if word.symbols:
                        break_type = word.symbols[-1].property.detected_break.type_
                        if int(break_type) != 0:
                            brk = break_type.name
                    own = _languages(word.property)
                    words.append(
                        Word(
                            id=f"w{len(words) + 1:04d}",
                            text=text,
                            polygon=vertices[:4],
                            confidence=float(word.confidence),
                            brk=brk,
                            para=para_index,
                            detected_languages=own,
                            lang=own[0][0] if own else para_lang,
                            height=round(polygon_height(vertices[:4]), 2),
                        )
                    )
                para_index += 1
    if (page_w, page_h) != (width, height):
        log.warning(
            "stage=vision page size %dx%d differs from the image size %dx%d",
            page_w,
            page_h,
            width,
            height,
        )
    log.info(
        "stage=vision ms=%d words=%d paragraphs=%d page=%dx%d",
        (time.perf_counter() - t0) * 1000,
        len(words),
        para_index,
        page_w,
        page_h,
    )
    return OcrResult(width=page_w, height=page_h, words=words, text=annotation.text if annotation else "")
