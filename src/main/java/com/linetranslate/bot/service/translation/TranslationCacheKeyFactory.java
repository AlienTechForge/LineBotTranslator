package com.linetranslate.bot.service.translation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.linetranslate.bot.service.ai.AiProviderRoute;

final class TranslationCacheKeyFactory {

    private TranslationCacheKeyFactory() {
    }

    static TranslationCacheKey create(
            String sourceText,
            String targetLanguage,
            AiProviderRoute route,
            TranslationCacheVariant variant) {
        return TranslationCacheKey.of(digest(sourceText), targetLanguage, route, variant);
    }

    private static String digest(String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            throw new IllegalArgumentException("Translation cache requires source text");
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(sourceText.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
