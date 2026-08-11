package com.linetranslate.bot.testing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.service.preference.UserPreferences;

public final class UserPreferencesFixtures {

    private UserPreferencesFixtures() {
    }

    public static UserPreferences preferences(UserProfile profile) {
        String provider = valueOr(profile.getPreferredAiProvider(), "openai");
        Map<String, String> models = new LinkedHashMap<>();
        models.put("openai", valueOr(profile.getOpenaiPreferredModel(), "gpt-default"));
        models.put("gemini", valueOr(profile.getGeminiPreferredModel(), "gemini-default"));
        return new UserPreferences(
                valueOr(profile.getPreferredLanguage(), "en"),
                valueOr(profile.getPreferredChineseTargetLanguage(), "en"),
                "en",
                provider,
                models.get(provider),
                models,
                profile.getRecentLanguages() == null
                        ? List.of()
                        : List.copyOf(profile.getRecentLanguages()));
    }

    public static UserPreferences preferences(
            String provider,
            String openAiModel,
            String geminiModel) {
        return preferences(UserProfile.builder()
                .preferredAiProvider(provider)
                .openaiPreferredModel(openAiModel)
                .geminiPreferredModel(geminiModel)
                .build());
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
