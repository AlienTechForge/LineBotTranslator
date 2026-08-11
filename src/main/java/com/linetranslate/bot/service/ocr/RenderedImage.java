package com.linetranslate.bot.service.ocr;

public record RenderedImage(byte[] pngBytes, int renderedBlockCount, int lowConfidenceBlockCount) {

    public RenderedImage {
        if (pngBytes == null || pngBytes.length == 0) {
            throw new IllegalArgumentException("Rendered PNG is required");
        }
        pngBytes = pngBytes.clone();
    }

    @Override
    public byte[] pngBytes() {
        return pngBytes.clone();
    }
}
