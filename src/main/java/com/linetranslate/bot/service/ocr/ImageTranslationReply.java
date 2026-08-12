package com.linetranslate.bot.service.ocr;

import java.util.Optional;

import com.linetranslate.bot.service.translation.TranslationResponse;

/** Image translation output with an optional generated-artifact URL for LINE rendering. */
public record ImageTranslationReply(
        TranslationResponse response,
        Optional<String> renderedImageUrl) {

    public ImageTranslationReply {
        if (response == null) {
            throw new IllegalArgumentException("Image translation response is required");
        }
        renderedImageUrl = renderedImageUrl == null
                ? Optional.empty()
                : renderedImageUrl.filter(value -> !value.isBlank());
    }

    public static ImageTranslationReply text(TranslationResponse response) {
        return new ImageTranslationReply(response, Optional.empty());
    }
}
