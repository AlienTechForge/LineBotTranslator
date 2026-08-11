package com.linetranslate.bot.service.ocr;

import java.util.List;

public record RenderedImage(
        byte[] pngBytes,
        int renderedBlockCount,
        int lowConfidenceBlockCount,
        List<OverlayRenderDecision> decisions) {

    public RenderedImage {
        if (pngBytes == null || pngBytes.length == 0) {
            throw new IllegalArgumentException("Rendered PNG is required");
        }
        pngBytes = pngBytes.clone();
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    public RenderedImage(byte[] pngBytes, int renderedBlockCount, int lowConfidenceBlockCount) {
        this(pngBytes, renderedBlockCount, lowConfidenceBlockCount, List.of());
    }

    @Override
    public byte[] pngBytes() {
        return pngBytes.clone();
    }
}
