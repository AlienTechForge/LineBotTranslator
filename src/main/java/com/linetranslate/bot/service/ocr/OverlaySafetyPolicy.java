package com.linetranslate.bot.service.ocr;

import java.awt.Polygon;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/** Final authority before any source pixel can be modified. */
@Component
public class OverlaySafetyPolicy {
    public OverlaySafetyPlan evaluate(
            List<ImageRegionOverlay> candidates,
            int imageWidth,
            int imageHeight,
            ImageTranslationProperties properties) {
        List<ImageRegionOverlay> requested = candidates == null ? List.of() : candidates;
        if (!properties.overlayEnabled()) return blocked(requested, "disabled");
        if (imageWidth <= 0 || imageHeight <= 0) return blocked(requested, "invalid-image");
        double imageArea = (double) imageWidth * imageHeight;
        List<ImageRegionOverlay> accepted = new ArrayList<>();
        List<Area> masks = new ArrayList<>();
        double total = 0;
        int skipped = 0;
        List<OverlayRenderDecision> decisions = new ArrayList<>();
        for (ImageRegionOverlay candidate : requested) {
            OcrRegion region = candidate.region();
            if (candidate.replacement().isBlank() || !region.validGeometry()
                    || !region.confidenceKnown()
                    || region.confidence() < properties.lowConfidenceThreshold()) {
                skipped++;
                decisions.add(preserved(region.id(), "invalid-candidate"));
                continue;
            }
            Area mask = mask(region);
            java.awt.Rectangle bounds = mask.getBounds();
            if (bounds.x < 0 || bounds.y < 0 || bounds.getMaxX() > imageWidth || bounds.getMaxY() > imageHeight) {
                skipped++;
                decisions.add(preserved(region.id(), "outside-image"));
                continue;
            }
            double ratio = region.masks().stream().mapToDouble(OverlaySafetyPolicy::polygonArea).sum() / imageArea;
            if (ratio <= 0 || ratio > properties.maxRegionAreaRatio()) {
                skipped++;
                decisions.add(preserved(region.id(), "region-coverage"));
                continue;
            }
            for (Area previous : masks) {
                Area intersection = new Area(previous);
                intersection.intersect(mask);
                if (!intersection.isEmpty()) return blocked(requested, "overlap");
            }
            total += ratio;
            if (total > properties.maxTotalMaskRatio()) {
                return blocked(requested, "total-coverage");
            }
            masks.add(mask);
            accepted.add(candidate);
        }
        return new OverlaySafetyPlan(true, "safe", accepted, skipped, decisions);
    }

    private static OverlayRenderDecision preserved(String id, String reason) {
        return new OverlayRenderDecision(id, OverlayRenderStatus.PRESERVED, reason);
    }

    private static OverlaySafetyPlan blocked(List<ImageRegionOverlay> candidates, String reason) {
        return new OverlaySafetyPlan(false, reason, List.of(), candidates.size(), candidates.stream()
                .map(candidate -> new OverlayRenderDecision(
                        candidate.region().id(), OverlayRenderStatus.REJECTED, reason)).toList());
    }

    static Area mask(OcrRegion region) {
        Area result = new Area();
        region.masks().forEach(points -> result.add(new Area(polygon(points))));
        return result;
    }

    static Polygon polygon(List<OcrPoint> points) {
        Polygon result = new Polygon();
        points.forEach(point -> result.addPoint(point.x(), point.y()));
        return result;
    }

    private static double polygonArea(List<OcrPoint> points) {
        if (points.size() < 3) return 0;
        long sum = 0;
        for (int i = 0; i < points.size(); i++) {
            OcrPoint a = points.get(i), b = points.get((i + 1) % points.size());
            sum += (long) a.x() * b.y() - (long) b.x() * a.y();
        }
        return Math.abs(sum) / 2d;
    }
}
