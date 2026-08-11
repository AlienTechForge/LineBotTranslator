package com.linetranslate.bot.service.preference;

import java.util.List;
import java.util.Map;

/** Immutable effective preferences consumed outside the preferences Module. */
public record UserPreferences(
        String targetLanguage,
        String chineseTargetLanguage,
        String fallbackTargetLanguage,
        String provider,
        String model,
        Map<String, String> modelsByProvider,
        List<String> recentLanguages) {

    public UserPreferences {
        modelsByProvider = Map.copyOf(modelsByProvider);
        recentLanguages = List.copyOf(recentLanguages);
    }

    public String modelFor(String providerName) {
        return modelsByProvider.get(providerName);
    }
}
