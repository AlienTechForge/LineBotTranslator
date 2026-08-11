package com.linetranslate.bot.service.ocr;

import java.util.List;

public record OcrSymbol(String text, List<OcrPoint> polygon, float confidence, boolean confidenceKnown) {
    public OcrSymbol {
        text = text == null ? "" : text;
        polygon = polygon == null ? List.of() : List.copyOf(polygon);
        confidence = Math.max(0, Math.min(1, confidence));
    }
}
