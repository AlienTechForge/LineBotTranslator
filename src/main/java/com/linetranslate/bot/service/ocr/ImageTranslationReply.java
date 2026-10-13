package com.linetranslate.bot.service.ocr;

import java.util.Optional;

import com.linetranslate.bot.service.translation.TranslationResponse;

/**
 * Image translation output with an optional generated-artifact URL for LINE rendering, and an
 * optional separately stored preview of it for deployments without the image proxy.
 */
public record ImageTranslationReply(
        TranslationResponse response,
        Optional<String> renderedImageUrl,
        Optional<String> renderedPreviewUrl) {

    public ImageTranslationReply {
        if (response == null) {
            throw new IllegalArgumentException("Image translation response is required");
        }
        renderedImageUrl = renderedImageUrl == null
                ? Optional.empty()
                : renderedImageUrl.filter(value -> !value.isBlank());
        renderedPreviewUrl = renderedPreviewUrl == null || renderedImageUrl.isEmpty()
                ? Optional.empty()
                : renderedPreviewUrl.filter(value -> !value.isBlank());
    }

    public ImageTranslationReply(TranslationResponse response, Optional<String> renderedImageUrl) {
        this(response, renderedImageUrl, Optional.empty());
    }

    public static ImageTranslationReply text(TranslationResponse response) {
        return new ImageTranslationReply(response, Optional.empty());
    }
}
