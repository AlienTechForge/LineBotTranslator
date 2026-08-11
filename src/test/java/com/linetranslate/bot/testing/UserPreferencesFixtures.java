package com.linetranslate.bot.testing;

import java.util.List;

import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.service.preference.UserPreferences;

public final class UserPreferencesFixtures {

    private UserPreferencesFixtures() {
    }

    public static UserPreferences preferences(UserProfile profile) {
        return new UserPreferences(
                valueOr(profile.getPreferredLanguage(), "en"),
                valueOr(profile.getPreferredChineseTargetLanguage(), "en"),
                "en",
                valueOr(profile.getPreferredModel(), "openai/gpt-4o-mini"),
                profile.getRecentLanguages() == null ? List.of() : List.copyOf(profile.getRecentLanguages()));
    }

    /** Legacy-shaped test helper; maps the chosen provider pair to one OpenRouter slug. */
    public static UserPreferences preferences(String provider, String primaryModel, String secondaryModel) {
        String model = "gemini".equalsIgnoreCase(provider) ? secondaryModel : primaryModel;
        return new UserPreferences("en", "en", "en", model, List.of());
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
