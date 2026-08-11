package com.linetranslate.bot.service.ocr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Security and rendering limits for image translation. */
@Component
public record ImageTranslationProperties(
        long maxFileSizeBytes,
        int maxDimension,
        long maxPixels,
        float lowConfidenceThreshold) {

    public ImageTranslationProperties(
            @Value("${app.image-translation.max-file-size-bytes:10485760}") long maxFileSizeBytes,
            @Value("${app.image-translation.max-dimension:4096}") int maxDimension,
            @Value("${app.image-translation.max-pixels:16000000}") long maxPixels,
            @Value("${app.image-translation.low-confidence-threshold:0.60}") float lowConfidenceThreshold) {
        if (maxFileSizeBytes <= 0
                || maxFileSizeBytes >= Integer.MAX_VALUE
                || maxDimension <= 0
                || maxPixels <= 0) {
            throw new IllegalArgumentException("Image translation limits must be positive");
        }
        if (lowConfidenceThreshold <= 0 || lowConfidenceThreshold > 1) {
            throw new IllegalArgumentException("OCR confidence threshold must be in (0, 1]");
        }
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.maxDimension = maxDimension;
        this.maxPixels = maxPixels;
        this.lowConfidenceThreshold = lowConfidenceThreshold;
    }

    public static ImageTranslationProperties defaults() {
        return new ImageTranslationProperties(10_485_760, 4_096, 16_000_000, 0.60f);
    }
}
