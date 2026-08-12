package com.linetranslate.bot.service.ocr;

import java.util.List;

public record RenderedImage(
        byte[] pngBytes,
        int renderedBlockCount,
        int lowConfidenceBlockCount,
        List<OverlayRenderDecision> decisions,
        OverlayDegradationSummary degradation) {

    public RenderedImage {
        if (pngBytes == null || pngBytes.length == 0) {
            throw new IllegalArgumentException("Rendered PNG is required");
        }
        pngBytes = pngBytes.clone();
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        degradation = degradation == null
                ? OverlayDegradationSummary.fromDecisions(decisions) : degradation;
        lowConfidenceBlockCount = degradation.count(OverlayDegradationReason.LOW_CONFIDENCE);
    }

    public RenderedImage(byte[] pngBytes, int renderedBlockCount, int lowConfidenceBlockCount) {
        this(pngBytes, renderedBlockCount, lowConfidenceBlockCount, List.of(),
                OverlayDegradationSummary.single(OverlayDegradationReason.LOW_CONFIDENCE,
                        lowConfidenceBlockCount));
    }

    public RenderedImage(byte[] pngBytes, int renderedBlockCount, int lowConfidenceBlockCount,
            List<OverlayRenderDecision> decisions) {
        this(pngBytes, renderedBlockCount, lowConfidenceBlockCount, decisions,
                OverlayDegradationSummary.fromDecisions(decisions));
    }

    @Override
    public byte[] pngBytes() {
        return pngBytes.clone();
    }
}
