"""Download one model file: the standard library only, so the Dockerfile can run it before the app is copied in.

    python modelfetch.py <url> <destination> [<sha256>]

`ensure()` is what the service calls the first time the LaMa inpainter is used (inpaint.py); the Docker
build calls this file directly and keeps the model in the image (`/app/models`). One lock keeps two
threads from downloading the same file twice, the bytes go to a temporary file next to the destination
and are renamed into place only when the download is complete (and, with a checksum, correct), so a half
file never looks like a model. Nothing is logged except sizes and the file name.
"""

from __future__ import annotations

import hashlib
import logging
import os
import sys
import threading
import time
import urllib.request
from pathlib import Path
from typing import Callable

log = logging.getLogger("lab.modelfetch")

CHUNK_BYTES = 1024 * 1024
TIMEOUT_S = 120.0  # per socket operation, not for the whole download
MIN_MODEL_BYTES = 1024 * 1024  # anything smaller is an error page, not a model
USER_AGENT = "image-translator-modelfetch/1"
RETRY_DELAYS = (1.0, 3.0)  # a network error is retried after these pauses (so three attempts in all)

_LOCK = threading.Lock()


class ModelDownloadError(RuntimeError):
    """The download failed or the file is not what was asked for."""


class ChecksumMismatch(ModelDownloadError):
    """The downloaded file does not have the SHA-256 that INPAINT_MODEL_SHA256 names."""


Opener = Callable[[str, float], object]  # (url, timeout) -> a response: .read(n), .headers, usable as a context manager


def _urlopen(url: str, timeout: float):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    return urllib.request.urlopen(request, timeout=timeout)  # noqa: S310 - the scheme is checked in ensure()


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(CHUNK_BYTES), b""):
            digest.update(chunk)
    return digest.hexdigest()


def ensure(url: str, dest: str | os.PathLike, sha256: str = "", *, opener: Opener | None = None) -> Path:
    """`dest` itself when the file is there; otherwise download `url` to it and return the path.

    `sha256` (hex, optional) is checked after a download, never on a file that was already in place.
    Raises ModelDownloadError (ChecksumMismatch for a wrong checksum) and leaves no partial file behind.
    """
    path = Path(dest)
    with _LOCK:
        if path.is_file() and path.stat().st_size > 0:
            return path
        if not url.lower().startswith(("https://", "http://")):
            raise ModelDownloadError("the model URL must start with https:// (or http://)")
        path.parent.mkdir(parents=True, exist_ok=True)
        part = path.with_name(f"{path.name}.part-{os.getpid()}")
        for attempt in range(len(RETRY_DELAYS) + 1):
            digest = hashlib.sha256()
            total = 0
            try:
                with (opener or _urlopen)(url, TIMEOUT_S) as response, open(part, "wb") as out:
                    expected = None
                    try:
                        expected = int(response.headers.get("Content-Length") or 0) or None
                    except (AttributeError, TypeError, ValueError):
                        expected = None
                    while True:
                        chunk = response.read(CHUNK_BYTES)
                        if not chunk:
                            break
                        out.write(chunk)
                        digest.update(chunk)
                        total += len(chunk)
                if expected is not None and total != expected:
                    raise ModelDownloadError(f"the download stopped after {total} of {expected} bytes")
                if total < MIN_MODEL_BYTES:
                    raise ModelDownloadError(f"the download is only {total} bytes: not a model file")
                wanted = sha256.strip().lower()
                if wanted and digest.hexdigest() != wanted:
                    raise ChecksumMismatch("the downloaded file does not match INPAINT_MODEL_SHA256")
                os.replace(part, path)
            except ModelDownloadError:
                raise
            except Exception as exc:  # noqa: BLE001 - urllib / socket / OS errors all mean "no model" (after the retries)
                if attempt < len(RETRY_DELAYS):
                    log.warning("stage=modelfetch attempt %d failed (%s), retrying", attempt + 1, type(exc).__name__)
                    time.sleep(RETRY_DELAYS[attempt])
                    continue
                # the cause is part of the message: a build log or a unit report must say whether it was DNS, a
                # certificate or a refused connection (the URL is not a secret)
                raise ModelDownloadError(f"{type(exc).__name__} while downloading the model: {exc}") from exc
            finally:
                try:
                    part.unlink()
                except OSError:
                    pass
            break
        log.info("stage=modelfetch file=%s bytes=%d", path.name, total)
        return path


def main(argv: list[str]) -> int:
    if len(argv) not in (3, 4):
        print("usage: modelfetch.py <url> <destination> [<sha256>]", file=sys.stderr)
        return 2
    try:
        path = ensure(argv[1], argv[2], argv[3] if len(argv) == 4 else "")
    except ModelDownloadError as exc:
        print(f"model download failed: {exc}", file=sys.stderr)
        return 1
    print(f"model ready: {path.name}, {path.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
