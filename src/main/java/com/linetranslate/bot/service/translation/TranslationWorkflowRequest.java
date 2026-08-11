package com.linetranslate.bot.service.translation;

import java.time.Instant;
import java.util.List;

import com.linetranslate.bot.model.UserProfile;

public record TranslationWorkflowRequest(
        UserProfile userProfile,
        String sourceText,
        String requestedTargetLanguage,
        TranslationRequestKind kind,
        String imageUrl,
        Boolean imageStored,
        Instant startedAt,
        String requestedStylePresetId,
        String explicitSourceLanguage,
        List<ImageRegionTranslationInput> imageRegions) {

    public TranslationWorkflowRequest(
            UserProfile userProfile,
            String sourceText,
            String requestedTargetLanguage,
            TranslationRequestKind kind,
            String imageUrl,
            Boolean imageStored,
            Instant startedAt) {
        this(userProfile, sourceText, requestedTargetLanguage, kind, imageUrl,
                imageStored, startedAt, null, null, List.of());
    }

    public TranslationWorkflowRequest(
            UserProfile userProfile,
            String sourceText,
            String requestedTargetLanguage,
            TranslationRequestKind kind,
            String imageUrl,
            Boolean imageStored,
            Instant startedAt,
            String requestedStylePresetId) {
        this(userProfile, sourceText, requestedTargetLanguage, kind, imageUrl,
                imageStored, startedAt, requestedStylePresetId, null, List.of());
    }

    public TranslationWorkflowRequest(
            UserProfile userProfile, String sourceText, String requestedTargetLanguage,
            TranslationRequestKind kind, String imageUrl, Boolean imageStored, Instant startedAt,
            String requestedStylePresetId, String explicitSourceLanguage) {
        this(userProfile, sourceText, requestedTargetLanguage, kind, imageUrl, imageStored, startedAt,
                requestedStylePresetId, explicitSourceLanguage, List.of());
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
        explicitSourceLanguage = explicitSourceLanguage == null || explicitSourceLanguage.isBlank()
                ? null : explicitSourceLanguage.trim().toLowerCase(java.util.Locale.ROOT);
        if (explicitSourceLanguage != null && !explicitSourceLanguage.matches("[a-z]{2,3}(?:-[a-z0-9]{2,8})?")) {
            throw new IllegalArgumentException("Explicit source language is invalid");
        }
        imageRegions = imageRegions == null ? List.of() : List.copyOf(imageRegions);
        if (!imageRegions.isEmpty() && !kind.isImage()) {
            throw new IllegalArgumentException("Structured regions require an image translation request");
        }
        if (!kind.isImage()) {
            imageUrl = null;
            imageStored = null;
        }
    }
}
