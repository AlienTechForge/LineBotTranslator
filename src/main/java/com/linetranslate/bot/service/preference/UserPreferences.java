package com.linetranslate.bot.service.preference;

import java.util.List;

import com.linetranslate.bot.service.translation.TranslationStylePreset;

/** Immutable effective preferences consumed outside the preferences Module. */
public record UserPreferences(
        String targetLanguage,
        String chineseTargetLanguage,
        String fallbackTargetLanguage,
        String model,
        List<String> recentLanguages,
        TranslationStylePreset translationStyle) {

    public UserPreferences(
            String targetLanguage,
            String chineseTargetLanguage,
            String fallbackTargetLanguage,
            String model,
            List<String> recentLanguages) {
        this(targetLanguage, chineseTargetLanguage, fallbackTargetLanguage, model,
                recentLanguages, TranslationStylePreset.defaultPreset());
    }

    public UserPreferences {
        recentLanguages = recentLanguages == null ? List.of() : List.copyOf(recentLanguages);
        translationStyle = translationStyle == null
                ? TranslationStylePreset.defaultPreset()
                : translationStyle;
    }
}
