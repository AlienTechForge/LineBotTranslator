package com.linetranslate.bot.service.ocr;

import java.util.List;

public record RenderedImage(
        byte[] bytes,
        String contentType,
        int renderedBlockCount,
        int lowConfidenceBlockCount,
        List<OverlayRenderDecision> decisions,
        OverlayDegradationSummary degradation) {

    public RenderedImage {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Rendered image bytes are required");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("Rendered image content type is required");
        }
        bytes = bytes.clone();
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        degradation = degradation == null
                ? OverlayDegradationSummary.fromDecisions(decisions) : degradation;
        lowConfidenceBlockCount = degradation.count(OverlayDegradationReason.LOW_CONFIDENCE);
    }

    RenderedImage(Encoded encoded, int renderedBlockCount, int lowConfidenceBlockCount) {
        this(encoded.bytes(), encoded.contentType(), renderedBlockCount, lowConfidenceBlockCount,
                List.of(), OverlayDegradationSummary.single(OverlayDegradationReason.LOW_CONFIDENCE,
                        lowConfidenceBlockCount));
    }

    RenderedImage(Encoded encoded, int renderedBlockCount, int lowConfidenceBlockCount,
            List<OverlayRenderDecision> decisions) {
        this(encoded.bytes(), encoded.contentType(), renderedBlockCount, lowConfidenceBlockCount,
                decisions, OverlayDegradationSummary.fromDecisions(decisions));
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * Same bytes as {@link #bytes()}, kept for existing callers. They are PNG unless the output
     * had to be degraded to fit the size cap; {@link #contentType()} is authoritative.
     */
    public byte[] pngBytes() {
        return bytes();
    }

    /** Encoder output handed from the renderer: the bytes and the media type they are in. */
    record Encoded(byte[] bytes, String contentType) {
    }
}
