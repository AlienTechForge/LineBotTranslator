package com.linetranslate.bot.service.ocr;

import com.linetranslate.bot.service.storage.ImageStorageResult;
import com.linetranslate.bot.service.translation.TranslationWorkflowResult;

public record ImageTranslationPipelineResult(
        ImageTranslationContext context,
        TranslationWorkflowResult translation,
        ImageStorageResult renderedImage,
        int lowConfidenceBlockCount) {

    public ImageTranslationPipelineResult(
            ImageTranslationContext context,
            TranslationWorkflowResult translation) {
        this(context, translation, ImageStorageResult.notStored(), 0);
    }

    public ImageTranslationPipelineResult {
        if (context == null || translation == null) {
            throw new IllegalArgumentException("Image translation result requires context and translation");
        }
        renderedImage = renderedImage == null ? ImageStorageResult.notStored() : renderedImage;
        lowConfidenceBlockCount = Math.max(0, lowConfidenceBlockCount);
    }
}
