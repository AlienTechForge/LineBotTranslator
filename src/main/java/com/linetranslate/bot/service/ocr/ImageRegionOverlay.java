package com.linetranslate.bot.service.ocr;

public record ImageRegionOverlay(OcrRegion region, String replacement) {
    public ImageRegionOverlay {
        if (region == null) throw new IllegalArgumentException("OCR region is required");
        replacement = replacement == null ? "" : replacement.strip();
    }
}
