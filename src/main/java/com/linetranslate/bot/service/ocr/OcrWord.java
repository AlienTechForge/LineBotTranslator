package com.linetranslate.bot.service.ocr;

import java.util.List;

public record OcrWord(
        String text,
        List<OcrPoint> polygon,
        float confidence,
        boolean confidenceKnown,
        List<OcrSymbol> symbols) {
    public OcrWord {
        text = text == null ? "" : text.strip();
        polygon = polygon == null ? List.of() : List.copyOf(polygon);
        confidence = Math.max(0, Math.min(1, confidence));
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
    }

    public OcrWord(String text, List<OcrPoint> polygon, float confidence, boolean confidenceKnown) {
        this(text, polygon, confidence, confidenceKnown, List.of());
    }
}
