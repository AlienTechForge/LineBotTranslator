package com.linetranslate.bot.service.ocr;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Security and delivery limits for image translation: what a LINE upload may be before it is sent to
 * the image-translator service, and how large the translated picture may be when it is handed back to
 * LINE.
 *
 * @param maxFileSizeBytes    the largest upload accepted from LINE
 * @param maxDimension        the longest side, in pixels, of an accepted upload
 * @param maxPixels           the most pixels an accepted upload may have
 * @param maxOutputBytes      hard cap on the delivered picture (LINE refuses an original above 10 MB)
 * @param deliveryTargetBytes size an opaque picture is brought under by JPEG when its PNG is larger;
 *                            a value at or above {@code maxOutputBytes} switches that step off
 */
@Component
public record ImageTranslationProperties(
        long maxFileSizeBytes,
        int maxDimension,
        long maxPixels,
        long maxOutputBytes,
        long deliveryTargetBytes) {

    @Autowired
    public ImageTranslationProperties(
            @Value("${app.image-translation.max-file-size-bytes:10485760}") long maxFileSizeBytes,
            @Value("${app.image-translation.max-dimension:4096}") int maxDimension,
            @Value("${app.image-translation.max-pixels:16000000}") long maxPixels,
            @Value("${app.image-translation.max-output-bytes:10485760}") long maxOutputBytes,
            @Value("${app.image-translation.delivery-target-bytes:2097152}") long deliveryTargetBytes) {
        if (maxFileSizeBytes <= 0
                || maxFileSizeBytes >= Integer.MAX_VALUE
                || maxDimension <= 0
                || maxPixels <= 0) {
            throw new IllegalArgumentException("Image translation limits must be positive");
        }
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("Translated image size cap must be positive");
        }
        if (deliveryTargetBytes <= 0) {
            throw new IllegalArgumentException("Translated image delivery target must be positive");
        }
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.maxDimension = maxDimension;
        this.maxPixels = maxPixels;
        this.maxOutputBytes = maxOutputBytes;
        this.deliveryTargetBytes = Math.min(deliveryTargetBytes, maxOutputBytes);
    }

    /** The limits of an upload alone, with the default delivery limits. */
    public ImageTranslationProperties(long maxFileSizeBytes, int maxDimension, long maxPixels) {
        this(maxFileSizeBytes, maxDimension, maxPixels, 10_485_760L, 2_097_152L);
    }

    public static ImageTranslationProperties defaults() {
        return new ImageTranslationProperties(10_485_760, 4_096, 16_000_000);
    }
}
