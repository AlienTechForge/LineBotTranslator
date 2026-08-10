package com.linetranslate.bot.service.translation;

/**
 * Versioned translation inputs that can change output without changing source
 * text or target language.
 */
public record TranslationCacheVariant(
        String style,
        String glossaryVersion,
        String promptVersion) {

    public TranslationCacheVariant {
        style = required(style, "style");
        glossaryVersion = required(glossaryVersion, "glossary version");
        promptVersion = required(promptVersion, "prompt version");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Translation cache requires " + name);
        }
        return value.trim();
    }
}
