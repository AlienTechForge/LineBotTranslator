package com.linetranslate.bot.service.ocr;

import java.awt.image.BufferedImage;

public record ValidatedImage(byte[] bytes, String contentType, BufferedImage image) {

    public ValidatedImage {
        if (bytes == null || bytes.length == 0 || contentType == null || image == null) {
            throw new IllegalArgumentException("Validated image fields are required");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
