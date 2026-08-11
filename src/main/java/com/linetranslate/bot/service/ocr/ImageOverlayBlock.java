package com.linetranslate.bot.service.ocr;

public record ImageOverlayBlock(OcrService.TextBlock source, String replacement) {

    public ImageOverlayBlock {
        if (source == null) {
            throw new IllegalArgumentException("OCR source block is required");
        }
        replacement = replacement == null ? "" : replacement.strip();
    }
}
