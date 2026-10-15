package com.linetranslate.bot.service.ocr;

import java.util.List;

/**
 * @param breakAfter what the provider recorded after the word; {@link OcrBreak#NONE} when it
 *                   recorded nothing or the word did not come from the provider
 */
public record OcrWord(
        String text,
        List<OcrPoint> polygon,
        float confidence,
        boolean confidenceKnown,
        List<OcrSymbol> symbols,
        OcrBreak breakAfter) {
    public OcrWord {
        text = text == null ? "" : text.strip();
        polygon = polygon == null ? List.of() : List.copyOf(polygon);
        confidence = Math.max(0, Math.min(1, confidence));
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
        breakAfter = breakAfter == null ? OcrBreak.NONE : breakAfter;
    }

    public OcrWord(String text, List<OcrPoint> polygon, float confidence, boolean confidenceKnown,
            List<OcrSymbol> symbols) {
        this(text, polygon, confidence, confidenceKnown, symbols, OcrBreak.NONE);
    }

    public OcrWord(String text, List<OcrPoint> polygon, float confidence, boolean confidenceKnown) {
        this(text, polygon, confidence, confidenceKnown, List.of());
    }
}
