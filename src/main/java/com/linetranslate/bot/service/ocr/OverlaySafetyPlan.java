package com.linetranslate.bot.service.ocr;

import java.util.List;

public record OverlaySafetyPlan(
        boolean safe,
        String reason,
        List<ImageRegionOverlay> overlays,
        int skipped,
        List<OverlayRenderDecision> decisions) {
    public OverlaySafetyPlan {
        overlays = overlays == null ? List.of() : List.copyOf(overlays);
        skipped = Math.max(0, skipped);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    public OverlaySafetyPlan(boolean safe, String reason, List<ImageRegionOverlay> overlays, int skipped) {
        this(safe, reason, overlays, skipped, List.of());
    }
}
