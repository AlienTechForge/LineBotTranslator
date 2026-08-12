package com.linetranslate.bot.service.ocr;

import com.linetranslate.bot.service.storage.ImageStorageResult;
import com.linetranslate.bot.service.translation.TranslationWorkflowResult;

public record ImageTranslationPipelineResult(
        ImageTranslationContext context,
        TranslationWorkflowResult translation,
        ImageStorageResult renderedImage,
        int lowConfidenceBlockCount,
        ImageOverlayDisposition overlayDisposition,
        OverlayDegradationSummary degradation) {

    public ImageTranslationPipelineResult(
            ImageTranslationContext context,
            TranslationWorkflowResult translation) {
        this(context, translation, ImageStorageResult.notStored(), 0, ImageOverlayDisposition.UNAVAILABLE,
                OverlayDegradationSummary.none());
    }

    public ImageTranslationPipelineResult(
            ImageTranslationContext context,
            TranslationWorkflowResult translation,
            ImageStorageResult renderedImage,
            int lowConfidenceBlockCount) {
        this(context, translation, renderedImage, lowConfidenceBlockCount,
                renderedImage != null && renderedImage.stored()
                        ? ImageOverlayDisposition.GENERATED : ImageOverlayDisposition.UNAVAILABLE,
                OverlayDegradationSummary.single(OverlayDegradationReason.LOW_CONFIDENCE,
                        lowConfidenceBlockCount));
    }

    public ImageTranslationPipelineResult(
            ImageTranslationContext context, TranslationWorkflowResult translation,
            ImageStorageResult renderedImage, int lowConfidenceBlockCount,
            ImageOverlayDisposition overlayDisposition) {
        this(context, translation, renderedImage, lowConfidenceBlockCount, overlayDisposition,
                OverlayDegradationSummary.single(OverlayDegradationReason.LOW_CONFIDENCE,
                        lowConfidenceBlockCount));
    }

    public ImageTranslationPipelineResult {
        if (context == null || translation == null) {
            throw new IllegalArgumentException("Image translation result requires context and translation");
        }
        renderedImage = renderedImage == null ? ImageStorageResult.notStored() : renderedImage;
        lowConfidenceBlockCount = Math.max(0, lowConfidenceBlockCount);
        overlayDisposition = overlayDisposition == null ? ImageOverlayDisposition.UNAVAILABLE : overlayDisposition;
        degradation = degradation == null ? OverlayDegradationSummary.none() : degradation;
        lowConfidenceBlockCount = degradation.count(OverlayDegradationReason.LOW_CONFIDENCE);
    }
}
