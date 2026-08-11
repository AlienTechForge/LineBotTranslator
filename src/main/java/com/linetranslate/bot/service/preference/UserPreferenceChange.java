package com.linetranslate.bot.service.preference;

public record UserPreferenceChange(
        UserPreferences previous,
        UserPreferences current) {
}
