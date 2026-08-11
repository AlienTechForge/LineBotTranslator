package com.linetranslate.bot.service.ocr;

public record OverlayRenderDecision(String regionId, OverlayRenderStatus status, String reason) {
    public OverlayRenderDecision {
        if (regionId == null || regionId.isBlank() || status == null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Overlay render decision is invalid");
        }
    }
}
