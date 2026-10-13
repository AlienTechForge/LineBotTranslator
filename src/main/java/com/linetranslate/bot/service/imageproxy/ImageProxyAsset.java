package com.linetranslate.bot.service.imageproxy;

import java.util.Arrays;

/** Immutable image bytes exposed by the image proxy; the preview shares the original's type. */
public record ImageProxyAsset(byte[] original, byte[] preview, String contentType) {

    public ImageProxyAsset {
        if (original == null || original.length == 0 || preview == null || preview.length == 0) {
            throw new IllegalArgumentException("Image proxy asset must contain original and preview bytes");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("Image proxy asset must declare its content type");
        }
        original = Arrays.copyOf(original, original.length);
        preview = Arrays.copyOf(preview, preview.length);
    }

    @Override
    public byte[] original() {
        return Arrays.copyOf(original, original.length);
    }

    @Override
    public byte[] preview() {
        return Arrays.copyOf(preview, preview.length);
    }

    /** Original length without the defensive copy, for cache weighing. */
    int originalLength() {
        return original.length;
    }

    /** Preview length without the defensive copy, for cache weighing. */
    int previewLength() {
        return preview.length;
    }
}
