package com.linetranslate.bot.service.ocr;

import java.util.List;

/**
 * The regions the safety policy approved, with the coverage limits they were approved under and how
 * much of the total limit they already use. The renderer may enlarge an approved region (a cell
 * that takes the empty space under it), and that is charged against what is left here: a plan built
 * without these figures has no allowance and its regions are never enlarged.
 *
 * @param maxRegionRatio the single-region limit, as a share of the image, that applied to the plan
 * @param maxTotalRatio the total mask limit, as a share of the image, that applied to the plan
 * @param acceptedRatio the share of the image the accepted masks cover
 */
public record OverlaySafetyPlan(
        boolean safe,
        String reason,
        List<ImageRegionOverlay> overlays,
        int skipped,
        List<OverlayRenderDecision> decisions,
        double maxRegionRatio,
        double maxTotalRatio,
        double acceptedRatio) {
    public OverlaySafetyPlan {
        overlays = overlays == null ? List.of() : List.copyOf(overlays);
        skipped = Math.max(0, skipped);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        maxRegionRatio = finiteOrZero(maxRegionRatio);
        maxTotalRatio = finiteOrZero(maxTotalRatio);
        acceptedRatio = finiteOrZero(acceptedRatio);
    }

    public OverlaySafetyPlan(
            boolean safe, String reason, List<ImageRegionOverlay> overlays, int skipped,
            List<OverlayRenderDecision> decisions) {
        this(safe, reason, overlays, skipped, decisions, 0, 0, 0);
    }

    public OverlaySafetyPlan(boolean safe, String reason, List<ImageRegionOverlay> overlays, int skipped) {
        this(safe, reason, overlays, skipped, List.of());
    }

    /** Share of the total limit the renderer may still spend on enlarging regions; never negative. */
    public double remainingRatio() {
        return Math.max(0, maxTotalRatio - acceptedRatio);
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? Math.max(0, value) : 0;
    }
}
