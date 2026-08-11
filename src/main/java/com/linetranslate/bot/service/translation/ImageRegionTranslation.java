package com.linetranslate.bot.service.translation;

public record ImageRegionTranslation(String regionId, String translatedText) {
    public ImageRegionTranslation {
        if (regionId == null || !regionId.matches("[A-Za-z0-9._:-]{1,128}")
                || translatedText == null || translatedText.isBlank() || translatedText.length() > 4_000) {
            throw new IllegalArgumentException("Structured translation output requires ID and text");
        }
        translatedText = translatedText.strip();
    }
}
