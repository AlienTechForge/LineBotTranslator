package com.linetranslate.bot.service.ocr;

import java.util.List;

public record OcrRegionDecision(
        OcrRegion region,
        OcrQualification qualification,
        String reason,
        List<String> protectedTokens) {
    public OcrRegionDecision {
        protectedTokens = protectedTokens == null ? List.of() : List.copyOf(protectedTokens);
    }
}
