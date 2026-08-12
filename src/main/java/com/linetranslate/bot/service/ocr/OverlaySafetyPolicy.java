package com.linetranslate.bot.service.ocr;

import java.awt.BasicStroke;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/** Final authority before any source pixel can be modified. */
@Component
@Slf4j
public class OverlaySafetyPolicy {
    public OverlaySafetyPlan evaluate(
            List<ImageRegionOverlay> candidates,
            int imageWidth,
            int imageHeight,
            ImageTranslationProperties properties) {
        return evaluate(candidates, imageWidth, imageHeight, properties, OverlayContentMode.GENERAL);
    }

    public OverlaySafetyPlan evaluate(
            List<ImageRegionOverlay> candidates,
            int imageWidth,
            int imageHeight,
            ImageTranslationProperties properties,
            OverlayContentMode contentMode) {
        List<ImageRegionOverlay> requested = candidates == null ? List.of() : candidates;
        if (!properties.overlayEnabled()) return blocked(requested, "disabled");
        if (imageWidth <= 0 || imageHeight <= 0) return blocked(requested, "invalid-image");
        OverlayContentMode mode = contentMode == null ? OverlayContentMode.GENERAL : contentMode;
        double maxRegionRatio = mode == OverlayContentMode.DOCUMENT
                ? Math.max(properties.maxRegionAreaRatio(), .45)
                : properties.maxRegionAreaRatio();
        double maxTotalRatio = mode == OverlayContentMode.DOCUMENT
                ? Math.max(properties.maxTotalMaskRatio(), .90)
                : properties.maxTotalMaskRatio();
        double imageArea = (double) imageWidth * imageHeight;
        List<ImageRegionOverlay> accepted = new ArrayList<>();
        List<AcceptedMask> masks = new ArrayList<>();
        double total = 0;
        int skipped = 0;
        List<OverlayRenderDecision> decisions = new ArrayList<>();
        for (ImageRegionOverlay candidate : requested) {
            OcrRegion region = candidate.region();
            if (candidate.replacement().isBlank()) {
                skipped++;
                decisions.add(preserved(region.id(), "mapping-empty"));
                logPreserved(region.id(), "mapping-empty", mode, imageWidth, imageHeight, 0, total, maxTotalRatio);
                continue;
            }
            if (!region.validGeometry()) {
                skipped++;
                decisions.add(preserved(region.id(), "invalid-geometry"));
                logPreserved(region.id(), "invalid-geometry", mode, imageWidth, imageHeight,
                        0, total, maxTotalRatio);
                continue;
            }
            if (!region.confidenceKnown() || region.confidence() < properties.lowConfidenceThreshold()) {
                skipped++;
                decisions.add(preserved(region.id(), "untrusted-confidence"));
                logPreserved(region.id(), "untrusted-confidence", mode, imageWidth, imageHeight,
                        0, total, maxTotalRatio);
                continue;
            }
            java.awt.Rectangle sourceBounds = maskWithoutPadding(region).getBounds();
            if (sourceBounds.x < 0 || sourceBounds.y < 0
                    || sourceBounds.getMaxX() > imageWidth || sourceBounds.getMaxY() > imageHeight) {
                skipped++;
                decisions.add(preserved(region.id(), "outside-image"));
                logPreserved(region.id(), "outside-image", mode, imageWidth, imageHeight,
                        0, total, maxTotalRatio);
                continue;
            }
            Area mask = mask(region, imageWidth, imageHeight);
            Area paragraph = maskWithoutPadding(region);
            double paragraphRatio = region.area() / imageArea;
            double modifiedRatio = area(mask) / imageArea;
            if (paragraphRatio <= 0 || paragraphRatio > maxRegionRatio) {
                skipped++;
                decisions.add(preserved(region.id(), "region-coverage"));
                logPreserved(region.id(), "region-coverage", mode, imageWidth, imageHeight,
                        paragraphRatio, total, maxTotalRatio);
                continue;
            }
            boolean overlaps = false;
            for (AcceptedMask previous : masks) {
                Area intersection = new Area(previous.area());
                intersection.intersect(paragraph);
                double intersectionArea = area(intersection);
                double smallerArea = Math.min(area(previous.area()), area(paragraph));
                double intersectionRatio = smallerArea <= 0 ? 0 : intersectionArea / smallerArea;
                if (intersectionArea > 4 && intersectionRatio > .08) {
                    skipped++;
                    decisions.add(preserved(region.id(), "overlap"));
                    log.info("Overlay region preserved: region={}, reason=overlap, conflictsWith={}, "
                                    + "intersectionPixels={}, intersectionRatio={}, mode={}, image={}x{}",
                            region.id(), previous.regionId(), Math.round(intersectionArea),
                            intersectionRatio, mode, imageWidth, imageHeight);
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) continue;
            if (total + modifiedRatio > maxTotalRatio) {
                skipped++;
                decisions.add(preserved(region.id(), "total-coverage"));
                logPreserved(region.id(), "total-coverage", mode, imageWidth, imageHeight,
                        modifiedRatio, total, maxTotalRatio);
                continue;
            }
            total += modifiedRatio;
            masks.add(new AcceptedMask(region.id(), paragraph));
            accepted.add(candidate);
        }
        log.info("Overlay safety evaluated: mode={}, requested={}, accepted={}, preserved={}, "
                        + "totalMaskRatio={}, limit={}, image={}x{}",
                mode, requested.size(), accepted.size(), skipped, total, maxTotalRatio, imageWidth, imageHeight);
        return new OverlaySafetyPlan(true, "safe", accepted, skipped, decisions);
    }

    private static void logPreserved(String regionId, String reason, OverlayContentMode mode,
            int imageWidth, int imageHeight, double regionRatio, double acceptedRatio, double limit) {
        log.info("Overlay region preserved: region={}, reason={}, mode={}, regionRatio={}, "
                        + "acceptedRatio={}, limit={}, image={}x{}",
                regionId, reason, mode, regionRatio, acceptedRatio, limit, imageWidth, imageHeight);
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
        Polygon polygon = polygon(region.polygon());
        Area result = new Area(polygon);
        int padding = maskPadding(region);
        result.add(new Area(new BasicStroke(padding * 2f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_ROUND)
                .createStrokedShape(polygon)));
        return result;
    }

    static Area mask(OcrRegion region, int imageWidth, int imageHeight) {
        Area result = mask(region);
        result.intersect(new Area(new Rectangle(0, 0, imageWidth, imageHeight)));
        return result;
    }

    static Area sourceMask(OcrRegion region) {
        Area result = new Area();
        region.masks().forEach(points -> result.add(new Area(polygon(points))));
        return result;
    }

    static Polygon polygon(List<OcrPoint> points) {
        Polygon result = new Polygon();
        points.forEach(point -> result.addPoint(point.x(), point.y()));
        return result;
    }

    private static double area(Area area) {
        PathIterator iterator = area.getPathIterator(null, .25);
        double[] coordinates = new double[6];
        double startX = 0, startY = 0, lastX = 0, lastY = 0, signedTwiceArea = 0;
        while (!iterator.isDone()) {
            int segment = iterator.currentSegment(coordinates);
            if (segment == PathIterator.SEG_MOVETO) {
                startX = lastX = coordinates[0];
                startY = lastY = coordinates[1];
            } else if (segment == PathIterator.SEG_LINETO) {
                signedTwiceArea += lastX * coordinates[1] - coordinates[0] * lastY;
                lastX = coordinates[0];
                lastY = coordinates[1];
            } else if (segment == PathIterator.SEG_CLOSE) {
                signedTwiceArea += lastX * startY - startX * lastY;
            }
            iterator.next();
        }
        return Math.abs(signedTwiceArea) / 2d;
    }

    private static int maskPadding(OcrRegion region) {
        List<Integer> heights = region.words().stream()
                .map(OcrWord::polygon)
                .filter(points -> points.size() >= 4)
                .map(points -> (int) Math.round(Math.hypot(
                        points.get(points.size() - 1).x() - points.get(0).x(),
                        points.get(points.size() - 1).y() - points.get(0).y())))
                .filter(height -> height > 0)
                .sorted()
                .toList();
        int height = heights.isEmpty()
                ? Math.max(1, maskWithoutPadding(region).getBounds().height)
                : heights.get(heights.size() / 2);
        // Provider polygons often end exactly on the last antialiased source pixel.
        // Area.contains excludes the outer boundary, so retain one explicit pixel of slack.
        return Math.max(2, Math.min(8, 1 + (int) Math.ceil(height * .06)));
    }

    private static Area maskWithoutPadding(OcrRegion region) {
        return new Area(polygon(region.polygon()));
    }

    private record AcceptedMask(String regionId, Area area) {
    }
}
