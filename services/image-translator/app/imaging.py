"""Image decoding / encoding helpers."""

from __future__ import annotations

import base64
import io
from dataclasses import dataclass

import numpy as np
from PIL import Image, ImageOps

ALLOWED_FORMATS = {"JPEG": "jpg", "PNG": "png", "WEBP": "webp"}
ALLOWED_CONTENT_TYPES = {"image/jpeg", "image/png", "image/webp"}
MAX_PIXELS = 64_000_000
VISION_MAX_BYTES = 15 * 1024 * 1024  # Vision accepts files up to 20 MB (10 MB as a JSON request): stay clear


class ImageError(ValueError):
    """The upload is not a usable image."""


@dataclass
class LoadedImage:
    pil: Image.Image  # RGB, EXIF orientation applied, alpha flattened on white
    data: bytes  # bytes whose pixel grid matches `pil` (Vision / Claude / Mode B all see this)
    fmt: str  # JPEG | PNG | WEBP (format of `data`)
    ext: str

    @property
    def width(self) -> int:
        return self.pil.width

    @property
    def height(self) -> int:
        return self.pil.height

    def rgb(self) -> np.ndarray:
        return np.array(self.pil, dtype=np.uint8)


def encode_image(pil: Image.Image, fmt: str) -> bytes:
    buf = io.BytesIO()
    if fmt == "JPEG":
        pil.convert("RGB").save(buf, "JPEG", quality=95)
    elif fmt == "WEBP":
        pil.save(buf, "WEBP", lossless=True)
    else:
        pil.save(buf, "PNG")
    return buf.getvalue()


def load_image(data: bytes) -> LoadedImage:
    """Decode an upload. The returned `data` always matches the pixel grid of `pil`.

    When the file carries an EXIF rotation or an alpha channel we re-encode so that the
    coordinates Vision reports line up with the pixels we inpaint; otherwise the original
    bytes are passed through untouched.
    """
    try:
        im = Image.open(io.BytesIO(data))
        fmt = im.format or ""
        im.load()
    except Exception as exc:  # noqa: BLE001 - Pillow raises many types
        raise ImageError("the file is not a decodable image") from exc
    if fmt not in ALLOWED_FORMATS:
        raise ImageError(f"unsupported image format {fmt or 'unknown'} (use jpg, png or webp)")
    if im.width * im.height > MAX_PIXELS:
        raise ImageError(f"image is too large ({im.width}x{im.height} px)")

    orientation = im.getexif().get(274, 1)
    needs_rotation = orientation not in (0, 1)
    has_alpha = im.mode in ("RGBA", "LA", "PA") or "transparency" in im.info

    pil = ImageOps.exif_transpose(im) if needs_rotation else im
    if has_alpha:
        rgba = pil.convert("RGBA")
        flat = Image.new("RGB", rgba.size, (255, 255, 255))
        flat.paste(rgba, mask=rgba.getchannel("A"))
        pil = flat
    else:
        pil = pil.convert("RGB")

    if needs_rotation or has_alpha:
        out_fmt = "JPEG" if fmt == "JPEG" else "PNG"
        return LoadedImage(pil, encode_image(pil, out_fmt), out_fmt, ALLOWED_FORMATS[out_fmt])
    return LoadedImage(pil, data, fmt, ALLOWED_FORMATS[fmt])


def downscale(pil: Image.Image, max_side: int) -> Image.Image:
    w, h = pil.size
    longest = max(w, h)
    if longest <= max_side:
        return pil
    scale = max_side / longest
    return pil.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS)


def to_jpeg_base64(pil: Image.Image, max_side: int, quality: int = 90) -> tuple[str, tuple[int, int]]:
    """Downscale so the long side is <= max_side, encode as JPEG, return (base64, (w, h) sent)."""
    small = downscale(pil.convert("RGB"), max_side)
    buf = io.BytesIO()
    small.save(buf, "JPEG", quality=quality)
    return base64.standard_b64encode(buf.getvalue()).decode("ascii"), small.size


def bytes_for_vision(data: bytes, limit: int = VISION_MAX_BYTES) -> bytes:
    """`data` itself, or a JPEG of it when it is too big to send to Vision (a 4K PNG can be 20 MB+).

    Only the recognised text matters for the Mode B checks, so the re-encode costs nothing.
    """
    if len(data) <= limit:
        return data
    im = Image.open(io.BytesIO(data))
    buf = io.BytesIO()
    im.convert("RGB").save(buf, "JPEG", quality=92)
    return buf.getvalue()
