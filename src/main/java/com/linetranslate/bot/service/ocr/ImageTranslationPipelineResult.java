package com.linetranslate.bot.service.ocr;

import com.linetranslate.bot.service.translation.TranslationWorkflowResult;

public record ImageTranslationPipelineResult(
        ImageTranslationContext context,
        TranslationWorkflowResult translation) {

    public ImageTranslationPipelineResult {
        if (context == null || translation == null) {
            throw new IllegalArgumentException("Image translation result requires context and translation");
        }
    }
}
