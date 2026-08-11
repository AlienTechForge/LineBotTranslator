package com.linetranslate.bot.service.ocr;

import java.time.Instant;

import com.linetranslate.bot.model.UserProfile;

public record ImageTranslationRequest(
        UserProfile userProfile,
        String messageId,
        Instant startedAt) {

    public ImageTranslationRequest {
        if (userProfile == null || userProfile.getUserId() == null
                || userProfile.getUserId().isBlank()) {
            throw new IllegalArgumentException("Image translation requires a user profile");
        }
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("Image translation requires a LINE message ID");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("Image translation requires a start time");
        }
        messageId = messageId.trim();
    }
}
