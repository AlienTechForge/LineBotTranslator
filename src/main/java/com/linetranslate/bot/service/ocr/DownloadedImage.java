package com.linetranslate.bot.service.ocr;

import java.util.Arrays;

/** Immutable image bytes downloaded from LINE. */
public record DownloadedImage(byte[] bytes, String contentType) {

    public DownloadedImage {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Downloaded image cannot be empty");
        }
        bytes = Arrays.copyOf(bytes, bytes.length);
        contentType = contentType == null || contentType.isBlank()
                ? "image/jpeg"
                : contentType.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
