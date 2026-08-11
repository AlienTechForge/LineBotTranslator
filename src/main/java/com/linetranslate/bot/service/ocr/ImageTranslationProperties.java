package com.linetranslate.bot.service.ocr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Security and rendering limits for image translation. */
@Component
public record ImageTranslationProperties(
        long maxFileSizeBytes,
        int maxDimension,
        long maxPixels,
        float lowConfidenceThreshold,
        boolean overlayEnabled,
        double maxRegionAreaRatio,
        double maxTotalMaskRatio) {

    @org.springframework.beans.factory.annotation.Autowired
    public ImageTranslationProperties(
            @Value("${app.image-translation.max-file-size-bytes:10485760}") long maxFileSizeBytes,
            @Value("${app.image-translation.max-dimension:4096}") int maxDimension,
            @Value("${app.image-translation.max-pixels:16000000}") long maxPixels,
            @Value("${app.image-translation.low-confidence-threshold:0.60}") float lowConfidenceThreshold,
            @Value("${app.image-translation.overlay-enabled:true}") boolean overlayEnabled,
            @Value("${app.image-translation.max-region-area-ratio:0.12}") double maxRegionAreaRatio,
            @Value("${app.image-translation.max-total-mask-ratio:0.35}") double maxTotalMaskRatio) {
        if (maxFileSizeBytes <= 0
                || maxFileSizeBytes >= Integer.MAX_VALUE
                || maxDimension <= 0
                || maxPixels <= 0) {
            throw new IllegalArgumentException("Image translation limits must be positive");
        }
        if (lowConfidenceThreshold <= 0 || lowConfidenceThreshold > 1) {
            throw new IllegalArgumentException("OCR confidence threshold must be in (0, 1]");
        }
        if (maxRegionAreaRatio <= 0 || maxRegionAreaRatio > 1
                || maxTotalMaskRatio <= 0 || maxTotalMaskRatio > 1
                || maxRegionAreaRatio > maxTotalMaskRatio) {
            throw new IllegalArgumentException("Overlay area ratios are invalid");
        }
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.maxDimension = maxDimension;
        this.maxPixels = maxPixels;
        this.lowConfidenceThreshold = lowConfidenceThreshold;
        this.overlayEnabled = overlayEnabled;
        this.maxRegionAreaRatio = maxRegionAreaRatio;
        this.maxTotalMaskRatio = maxTotalMaskRatio;
    }

    public ImageTranslationProperties(long maxFileSizeBytes, int maxDimension, long maxPixels,
            float lowConfidenceThreshold) {
        this(maxFileSizeBytes, maxDimension, maxPixels, lowConfidenceThreshold, true, .12, .35);
    }

    public static ImageTranslationProperties defaults() {
        return new ImageTranslationProperties(10_485_760, 4_096, 16_000_000, 0.60f, true, .12, .35);
    }
}
