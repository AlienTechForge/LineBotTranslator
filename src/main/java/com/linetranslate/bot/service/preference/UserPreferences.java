package com.linetranslate.bot.service.preference;

import java.util.List;

/** Immutable effective preferences consumed outside the preferences Module. */
public record UserPreferences(
        String targetLanguage,
        String chineseTargetLanguage,
        String fallbackTargetLanguage,
        String model,
        List<String> recentLanguages) {

    public UserPreferences {
        recentLanguages = recentLanguages == null ? List.of() : List.copyOf(recentLanguages);
    }
}
