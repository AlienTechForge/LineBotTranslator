package com.linetranslate.bot.service.ocr;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The overlay renderer's output: the encoded image, how it was drawn, and, when the encoding is
 * lossless, the raster it was encoded from.
 *
 * <p>Ownership: the record owns {@code bytes} and {@code raster} and does not copy them. The only
 * producer is {@link ImageTranslationOverlayRenderer} and the only consumer is the image pipeline,
 * which uploads the bytes and hands the raster to the preview step; neither writes to them. Holding
 * a {@code RenderedImage} keeps the raster (four bytes per pixel) reachable, so drop it once the
 * upload is done.
 *
 * @param raster the image exactly as {@code bytes} encodes it, or {@code null} when the output
 *               was re-encoded as JPEG or downscaled to fit the size cap, so that decoding
 *               {@code bytes} is the only way to see what was stored
 */
public record RenderedImage(
        byte[] bytes,
        String contentType,
        int renderedBlockCount,
        int lowConfidenceBlockCount,
        List<OverlayRenderDecision> decisions,
        OverlayDegradationSummary degradation,
        BufferedImage raster) {

    public RenderedImage {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Rendered image bytes are required");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("Rendered image content type is required");
        }
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        degradation = degradation == null
                ? OverlayDegradationSummary.fromDecisions(decisions) : degradation;
        lowConfidenceBlockCount = degradation.count(OverlayDegradationReason.LOW_CONFIDENCE);
    }

    RenderedImage(Encoded encoded, int renderedBlockCount, int lowConfidenceBlockCount) {
        this(encoded.bytes(), encoded.contentType(), renderedBlockCount, lowConfidenceBlockCount,
                List.of(), OverlayDegradationSummary.single(OverlayDegradationReason.LOW_CONFIDENCE,
                        lowConfidenceBlockCount), encoded.raster());
    }

    RenderedImage(Encoded encoded, int renderedBlockCount, int lowConfidenceBlockCount,
            List<OverlayRenderDecision> decisions) {
        this(encoded.bytes(), encoded.contentType(), renderedBlockCount, lowConfidenceBlockCount,
                decisions, OverlayDegradationSummary.fromDecisions(decisions), encoded.raster());
    }

    /**
     * Same bytes as {@link #bytes()}, kept for existing callers. They are PNG unless the output
     * had to be degraded to fit the size cap; {@link #contentType()} is authoritative.
     */
    public byte[] pngBytes() {
        return bytes;
    }

    /**
     * Encoder output handed from the renderer: the bytes, the media type they are in, and the
     * raster they losslessly encode ({@code null} when they do not).
     */
    record Encoded(byte[] bytes, String contentType, BufferedImage raster) {
    }
}
