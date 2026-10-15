"""Google Cloud Vision wrapper: word boxes for Mode A, full text for Mode B verification."""

from __future__ import annotations

import logging
import time
from functools import lru_cache

from .config import REQUEST_TIMEOUT_S, get_settings
from .models import ModeError, OcrResult, Word

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


def ocr_words(image_bytes: bytes, width: int, height: int) -> OcrResult:
    """Run document_text_detection and flatten the hierarchy to a word list (ids w0001...)."""
    if get_settings().mock:
        from . import mock

        return mock.vision_words(width, height)

    t0 = time.perf_counter()
    response = _annotate(image_bytes)
    words: list[Word] = []
    page_w, page_h = width, height
    para_index = 0
    pages = response.full_text_annotation.pages if response.full_text_annotation else []
    for page_no, page in enumerate(pages):
        if page_no == 0 and page.width and page.height:
            page_w, page_h = int(page.width), int(page.height)
        for block in page.blocks:
            for paragraph in block.paragraphs:
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
                    words.append(
                        Word(
                            id=f"w{len(words) + 1:04d}",
                            text=text,
                            polygon=vertices[:4],
                            confidence=float(word.confidence),
                            brk=brk,
                            para=para_index,
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
    return OcrResult(width=page_w, height=page_h, words=words)


def ocr_text(image_bytes: bytes) -> str:
    """Full OCR text of an image (Mode B verification). The text is returned, never logged."""
    if get_settings().mock:
        return "MOCK OCR TEXT (LAB_MOCK=1)"
    t0 = time.perf_counter()
    response = _annotate(image_bytes)
    text = response.full_text_annotation.text if response.full_text_annotation else ""
    log.info("stage=vision-verify ms=%d chars=%d", (time.perf_counter() - t0) * 1000, len(text))
    return text
