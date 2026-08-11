package com.linetranslate.bot.service.ocr;

import java.util.Locale;

public record OcrDetectedLanguage(String code, float confidence) {
    public OcrDetectedLanguage {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("OCR language code is required");
        }
        code = code.trim().toLowerCase(Locale.ROOT);
        confidence = Math.max(0, Math.min(1, confidence));
    }
}
