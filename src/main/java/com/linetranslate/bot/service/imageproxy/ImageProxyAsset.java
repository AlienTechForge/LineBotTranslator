package com.linetranslate.bot.service.imageproxy;

import java.util.Arrays;

/** Immutable PNG bytes exposed by the image proxy. */
public record ImageProxyAsset(byte[] original, byte[] preview) {

    public ImageProxyAsset {
        if (original == null || original.length == 0 || preview == null || preview.length == 0) {
            throw new IllegalArgumentException("Image proxy asset must contain original and preview bytes");
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
}
