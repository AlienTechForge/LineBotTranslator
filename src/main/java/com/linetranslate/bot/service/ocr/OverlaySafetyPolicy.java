package com.linetranslate.bot.service.ocr;

import java.awt.BasicStroke;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
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
            if (candidate.replacement().isBlank()) {
                skipped++;
                decisions.add(preserved(region.id(), "mapping-empty"));
                continue;
            }
            if (!region.validGeometry()) {
                skipped++;
                decisions.add(preserved(region.id(), "invalid-geometry"));
                continue;
            }
            if (!region.confidenceKnown() || region.confidence() < properties.lowConfidenceThreshold()) {
                skipped++;
                decisions.add(preserved(region.id(), "untrusted-confidence"));
                continue;
            }
            java.awt.Rectangle sourceBounds = maskWithoutPadding(region).getBounds();
            if (sourceBounds.x < 0 || sourceBounds.y < 0
                    || sourceBounds.getMaxX() > imageWidth || sourceBounds.getMaxY() > imageHeight) {
                skipped++;
                decisions.add(preserved(region.id(), "outside-image"));
                continue;
            }
            Area mask = mask(region, imageWidth, imageHeight);
            Area paragraph = maskWithoutPadding(region);
            double paragraphRatio = region.area() / imageArea;
            double modifiedRatio = area(mask) / imageArea;
            if (paragraphRatio <= 0 || paragraphRatio > properties.maxRegionAreaRatio()) {
                skipped++;
                decisions.add(preserved(region.id(), "region-coverage"));
                continue;
            }
            for (Area previous : masks) {
                Area intersection = new Area(previous);
                intersection.intersect(paragraph);
                if (!intersection.isEmpty()) return blocked(requested, "overlap");
            }
            total += modifiedRatio;
            if (total > properties.maxTotalMaskRatio()) {
                return blocked(requested, "total-coverage");
            }
            masks.add(paragraph);
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
}
