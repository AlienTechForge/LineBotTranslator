package com.linetranslate.bot.service.translation;

import java.util.List;

public record ImageRegionTranslationInput(
        String regionId,
        String sourceText,
        String sourceLanguage,
        List<String> protectedTokens,
        boolean translatable,
        int readingOrder,
        ImageRegionLayout layout) {
    public static final int MAX_PROTECTED_TOKENS = 64;
    public static final int MAX_PROTECTED_TOKEN_LENGTH = 128;

    public ImageRegionTranslationInput {
        if (regionId == null || !regionId.matches("[A-Za-z0-9._:-]{1,128}")
                || sourceText == null || sourceText.isBlank() || sourceText.length() > 4_000) {
            throw new IllegalArgumentException("Structured translation region requires ID and source text");
        }
        sourceLanguage = sourceLanguage == null ? "und" : sourceLanguage;
        protectedTokens = protectedTokens == null ? List.of() : List.copyOf(protectedTokens);
        readingOrder = Math.max(0, readingOrder);
        layout = layout == null ? ImageRegionLayout.unspecified(regionId) : layout;
        if (protectedTokens.size() > MAX_PROTECTED_TOKENS || protectedTokens.stream()
                .anyMatch(token -> token == null || token.length() > MAX_PROTECTED_TOKEN_LENGTH)) {
            throw new IllegalArgumentException("Structured translation protected tokens are invalid");
        }
    }

    /**
     * Bounds derived tokens to this contract instead of failing the region: null and over-long
     * values are dropped (a value that long is a passage, not a token) and only the first
     * {@link #MAX_PROTECTED_TOKENS} are kept. Order is kept because tokens must reappear in order.
     */
    public static List<String> boundedProtectedTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return List.of();
        return tokens.stream()
                .filter(token -> token != null && token.length() <= MAX_PROTECTED_TOKEN_LENGTH)
                .limit(MAX_PROTECTED_TOKENS)
                .toList();
    }

    public ImageRegionTranslationInput(
            String regionId, String sourceText, String sourceLanguage, List<String> protectedTokens) {
        this(regionId, sourceText, sourceLanguage, protectedTokens, true, 0,
                ImageRegionLayout.unspecified(regionId));
    }

    public ImageRegionTranslationInput(
            String regionId, String sourceText, String sourceLanguage, List<String> protectedTokens,
            boolean translatable, int readingOrder) {
        this(regionId, sourceText, sourceLanguage, protectedTokens, translatable, readingOrder,
                ImageRegionLayout.unspecified(regionId));
    }
}
