package com.linetranslate.bot.service.translation;

import java.util.List;

public record ImageRegionTranslationInput(
        String regionId,
        String sourceText,
        String sourceLanguage,
        List<String> protectedTokens,
        boolean translatable,
        int readingOrder) {
    public ImageRegionTranslationInput {
        if (regionId == null || !regionId.matches("[A-Za-z0-9._:-]{1,128}")
                || sourceText == null || sourceText.isBlank() || sourceText.length() > 4_000) {
            throw new IllegalArgumentException("Structured translation region requires ID and source text");
        }
        sourceLanguage = sourceLanguage == null ? "und" : sourceLanguage;
        protectedTokens = protectedTokens == null ? List.of() : List.copyOf(protectedTokens);
        readingOrder = Math.max(0, readingOrder);
        if (protectedTokens.size() > 64 || protectedTokens.stream().anyMatch(token -> token == null || token.length() > 128)) {
            throw new IllegalArgumentException("Structured translation protected tokens are invalid");
        }
    }

    public ImageRegionTranslationInput(
            String regionId, String sourceText, String sourceLanguage, List<String> protectedTokens) {
        this(regionId, sourceText, sourceLanguage, protectedTokens, true, 0);
    }
}
