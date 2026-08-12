package com.linetranslate.bot.service.ocr;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record OverlayDegradationSummary(Map<OverlayDegradationReason, Integer> counts) {
    public OverlayDegradationSummary {
        EnumMap<OverlayDegradationReason, Integer> normalized = new EnumMap<>(OverlayDegradationReason.class);
        if (counts != null) counts.forEach((reason, count) -> {
            if (reason != null && count != null && count > 0) normalized.put(reason, count);
        });
        counts = Map.copyOf(normalized);
    }

    public static OverlayDegradationSummary none() {
        return new OverlayDegradationSummary(Map.of());
    }

    public static OverlayDegradationSummary single(OverlayDegradationReason reason, int count) {
        return count <= 0 ? none() : new OverlayDegradationSummary(Map.of(reason, count));
    }

    public static OverlayDegradationSummary fromDecisions(List<OverlayRenderDecision> decisions) {
        EnumMap<OverlayDegradationReason, Integer> values = new EnumMap<>(OverlayDegradationReason.class);
        for (OverlayRenderDecision decision : decisions == null ? List.<OverlayRenderDecision>of() : decisions) {
            if (decision.status() == OverlayRenderStatus.RENDERED) continue;
            values.merge(classify(decision.reason()), 1, Integer::sum);
        }
        return new OverlayDegradationSummary(values);
    }

    public int count(OverlayDegradationReason reason) {
        return counts.getOrDefault(reason, 0);
    }

    public int total() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public OverlayDegradationSummary merge(OverlayDegradationSummary other) {
        EnumMap<OverlayDegradationReason, Integer> values = new EnumMap<>(OverlayDegradationReason.class);
        counts.forEach(values::put);
        if (other != null) other.counts.forEach((reason, count) -> values.merge(reason, count, Integer::sum));
        return new OverlayDegradationSummary(values);
    }

    private static OverlayDegradationReason classify(String value) {
        String reason = value == null ? "" : value;
        if (reason.contains("confidence") || reason.equals("invalid-candidate")) return OverlayDegradationReason.LOW_CONFIDENCE;
        if (reason.contains("font")) return OverlayDegradationReason.FONT;
        if (reason.contains("coverage")) return OverlayDegradationReason.COVERAGE;
        if (reason.contains("geometry") || reason.contains("outside") || reason.contains("mask")) return OverlayDegradationReason.GEOMETRY;
        if (reason.contains("overlap")) return OverlayDegradationReason.OVERLAP;
        if (reason.contains("text-fit")) return OverlayDegradationReason.TEXT_FIT;
        if (reason.contains("mapping")) return OverlayDegradationReason.MAPPING;
        if (reason.contains("disabled")) return OverlayDegradationReason.DISABLED;
        if (reason.contains("storage")) return OverlayDegradationReason.STORAGE;
        return OverlayDegradationReason.OTHER;
    }
}
