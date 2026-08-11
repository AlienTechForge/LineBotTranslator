package com.linetranslate.bot.service.translation;

import java.time.Instant;

import com.linetranslate.bot.model.UserProfile;

public record TranslationWorkflowRequest(
        UserProfile userProfile,
        String sourceText,
        String requestedTargetLanguage,
        TranslationRequestKind kind,
        String imageUrl,
        Boolean imageStored,
        Instant startedAt,
        String requestedStylePresetId) {

    public TranslationWorkflowRequest(
            UserProfile userProfile,
            String sourceText,
            String requestedTargetLanguage,
            TranslationRequestKind kind,
            String imageUrl,
            Boolean imageStored,
            Instant startedAt) {
        this(userProfile, sourceText, requestedTargetLanguage, kind, imageUrl,
                imageStored, startedAt, null);
    }

    public TranslationWorkflowRequest {
        if (userProfile == null || userProfile.getUserId() == null || userProfile.getUserId().isBlank()) {
            throw new IllegalArgumentException("Translation workflow requires a user profile");
        }
        if (sourceText == null || sourceText.isBlank()) {
            throw new IllegalArgumentException("Translation workflow requires source text");
        }
        if (kind == null || startedAt == null) {
            throw new IllegalArgumentException("Translation workflow requires kind and start time");
        }
        sourceText = sourceText.trim();
        requestedTargetLanguage = requestedTargetLanguage == null
                || requestedTargetLanguage.isBlank()
                        ? null
                        : requestedTargetLanguage.trim();
        requestedStylePresetId = requestedStylePresetId == null
                || requestedStylePresetId.isBlank()
                        ? null
                        : requestedStylePresetId.trim();
        if (!kind.isImage()) {
            imageUrl = null;
            imageStored = null;
        }
    }
}
