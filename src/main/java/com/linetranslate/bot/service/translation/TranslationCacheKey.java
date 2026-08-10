package com.linetranslate.bot.service.translation;

import java.util.Locale;

import com.linetranslate.bot.service.ai.AiProviderRoute;

/**
 * A privacy-safe cache identity. It deliberately contains only a digest of the
 * source text so diagnostics cannot accidentally print user content.
 */
public record TranslationCacheKey(
        String sourceDigest,
        String targetLanguage,
        String provider,
        String model,
        String style,
        String glossaryVersion,
        String promptVersion) {

    public TranslationCacheKey {
        sourceDigest = required(sourceDigest, "source digest");
        targetLanguage = required(targetLanguage, "target language").toLowerCase(Locale.ROOT);
        provider = required(provider, "provider").toLowerCase(Locale.ROOT);
        model = required(model, "model");
        style = required(style, "style");
        glossaryVersion = required(glossaryVersion, "glossary version");
        promptVersion = required(promptVersion, "prompt version");
    }

    public static TranslationCacheKey of(
            String sourceDigest,
            String targetLanguage,
            AiProviderRoute route,
            TranslationCacheVariant variant) {
        return new TranslationCacheKey(
                sourceDigest,
                targetLanguage,
                route.providerName(),
                route.modelName(),
                variant.style(),
                variant.glossaryVersion(),
                variant.promptVersion());
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Translation cache key requires " + name);
        }
        return value.trim();
    }
}
