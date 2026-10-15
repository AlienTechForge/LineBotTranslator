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
        double maxTotalRatio = switch (mode) {
            case DOCUMENT -> Math.max(properties.maxTotalMaskRatio(), .90);
            case POSTER -> Math.max(properties.maxTotalMaskRatio(), properties.posterMaxTotalMaskRatio());
            case GENERAL -> properties.maxTotalMaskRatio();
        };
        double imageArea = (double) imageWidth * imageHeight;
        // Decisions are kept in candidate order whichever order they were reached in.
        OverlayRenderDecision[] decided = new OverlayRenderDecision[requested.size()];
        List<Eligible> eligible = new ArrayList<>();
        List<AcceptedMask> masks = new ArrayList<>();
        double total = 0;
        int skipped = 0;
        for (int index = 0; index < requested.size(); index++) {
            ImageRegionOverlay candidate = requested.get(index);
            OcrRegion region = candidate.region();
            if (candidate.replacement().isBlank()) {
                skipped++;
                decided[index] = preserved(region.id(), "mapping-empty");
                logPreserved(region.id(), "mapping-empty", mode, imageWidth, imageHeight, 0, total, maxTotalRatio);
                continue;
            }
            if (!region.validGeometry()) {
                skipped++;
                decided[index] = preserved(region.id(), "invalid-geometry");
                logPreserved(region.id(), "invalid-geometry", mode, imageWidth, imageHeight,
                        0, total, maxTotalRatio);
                continue;
            }
            if (!region.confidenceKnown() || region.confidence() < properties.lowConfidenceThreshold()) {
                skipped++;
                decided[index] = preserved(region.id(), "untrusted-confidence");
                logPreserved(region.id(), "untrusted-confidence", mode, imageWidth, imageHeight,
                        0, total, maxTotalRatio);
                continue;
            }
            java.awt.Rectangle sourceBounds = maskWithoutPadding(region).getBounds();
            if (sourceBounds.x < 0 || sourceBounds.y < 0
                    || sourceBounds.getMaxX() > imageWidth || sourceBounds.getMaxY() > imageHeight) {
                skipped++;
                decided[index] = preserved(region.id(), "outside-image");
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
                decided[index] = preserved(region.id(), "region-coverage");
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
                    decided[index] = preserved(region.id(), "overlap");
                    log.info("Overlay region preserved: region={}, reason=overlap, conflictsWith={}, "
                                    + "intersectionPixels={}, intersectionRatio={}, mode={}, image={}x{}",
                            region.id(), previous.regionId(), Math.round(intersectionArea),
                            intersectionRatio, mode, imageWidth, imageHeight);
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) continue;
            // Overlaps are settled first-come in reading order among the regions that pass their own
            // checks; the total limit below then chooses among those, so a region it gives up still
            // keeps the later region that overlapped it out, which only ever errs towards fewer masks.
            total += modifiedRatio;
            masks.add(new AcceptedMask(region.id(), paragraph));
            eligible.add(new Eligible(index, candidate, modifiedRatio));
        }

        boolean[] kept = new boolean[requested.size()];
        double keptTotal = total;
        if (total <= maxTotalRatio) {
            eligible.forEach(value -> kept[value.index()] = true);
        } else {
            keptTotal = keepWithinTotal(eligible, kept, maxTotalRatio, imageArea);
            long dropped = eligible.stream().filter(value -> !kept[value.index()]).count();
            log.info("Overlay coverage limit applied: mode={}, rule=smallest-first, eligible={}, kept={}, "
                            + "dropped={}, eligibleRatio={}, keptRatio={}, limit={}, image={}x{}",
                    mode, eligible.size(), eligible.size() - dropped, dropped, total, keptTotal,
                    maxTotalRatio, imageWidth, imageHeight);
        }
        List<ImageRegionOverlay> accepted = new ArrayList<>();
        for (Eligible value : eligible) {
            if (kept[value.index()]) {
                accepted.add(value.candidate());
                continue;
            }
            skipped++;
            String id = value.candidate().region().id();
            decided[value.index()] = preserved(id, "total-coverage");
            logPreserved(id, "total-coverage", mode, imageWidth, imageHeight,
                    value.modifiedRatio(), keptTotal, maxTotalRatio);
        }
        List<OverlayRenderDecision> decisions = new ArrayList<>();
        for (OverlayRenderDecision decision : decided) {
            if (decision != null) decisions.add(decision);
        }
        log.info("Overlay safety evaluated: mode={}, requested={}, accepted={}, preserved={}, "
                        + "totalMaskRatio={}, limit={}, image={}x{}",
                mode, requested.size(), accepted.size(), skipped, keptTotal, maxTotalRatio,
                imageWidth, imageHeight);
        return new OverlaySafetyPlan(
                true, "safe", accepted, skipped, decisions, maxRegionRatio, maxTotalRatio, keptTotal);
    }

    /**
     * Marks, in {@code kept}, the regions that fit under {@code limit}, and returns their total.
     * Every region costs the area of its mask against the limit, so taking the cheapest first
     * translates the most regions the limit allows (a prefix of the regions by area is the largest
     * set that fits), and the regions given up are the largest blocks, spread wherever they sit,
     * instead of whatever comes last in reading order, which on a poster is a whole section.
     * Equal areas keep reading order.
     */
    private static double keepWithinTotal(
            List<Eligible> eligible, boolean[] kept, double limit, double imageArea) {
        // Whole pixels, so that masks of one size tie however the outline integration rounds them.
        List<Eligible> cheapestFirst = eligible.stream()
                .sorted(java.util.Comparator.comparingLong(
                        value -> Math.round(value.modifiedRatio() * imageArea)))
                .toList();
        double total = 0;
        for (Eligible value : cheapestFirst) {
            if (total + value.modifiedRatio() > limit) break;
            total += value.modifiedRatio();
            kept[value.index()] = true;
        }
        return total;
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

    /** Source glyph polygons kept as separate areas; empty polygons are dropped. */
    static List<Area> sourceAreas(OcrRegion region) {
        List<Area> result = new ArrayList<>();
        region.masks().forEach(points -> {
            Area area = new Area(polygon(points));
            if (!area.isEmpty()) result.add(area);
        });
        return List.copyOf(result);
    }

    /**
     * Row-major flags for the pixels of {@code window} whose centre lies inside any of the areas.
     * A centre is inside the union of the areas exactly when it is inside one of them, so each
     * area is tested only within its own bounds. Testing every pixel against the union instead
     * walks every outline segment of every area per pixel, which grows with the square of the
     * area count.
     */
    static boolean[] coverage(List<Area> areas, Rectangle window) {
        boolean[] inside = new boolean[Math.max(0, window.width) * Math.max(0, window.height)];
        for (Area area : areas) {
            Rectangle reach = area.getBounds().intersection(window);
            for (int y = reach.y; y < reach.y + reach.height; y++) {
                int row = (y - window.y) * window.width - window.x;
                for (int x = reach.x; x < reach.x + reach.width; x++) {
                    if (!inside[row + x] && area.contains(x + .5, y + .5)) inside[row + x] = true;
                }
            }
        }
        return inside;
    }

    /** Pixels that may be painted with the sampled background to erase source glyphs. */
    static Area sourceCleanupMask(OcrRegion region, int imageWidth, int imageHeight) {
        Area result = new Area();
        sourceCleanupAreas(region, imageWidth, imageHeight).forEach(result::add);
        return result;
    }

    /** Keeps glyph cleanup areas separate so each can recover its own local background. */
    static List<Area> sourceCleanupAreas(OcrRegion region, int imageWidth, int imageHeight) {
        List<Area> result = new ArrayList<>();
        int padding = maskPadding(region);
        Area image = new Area(new Rectangle(0, 0, imageWidth, imageHeight));
        region.masks().forEach(points -> {
            Polygon word = polygon(points);
            Area glyph = new Area(word);
            glyph.add(new Area(new BasicStroke(
                    padding * 2f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_ROUND)
                    .createStrokedShape(word)));
            glyph.intersect(new Area(image));
            if (!glyph.isEmpty()) result.add(glyph);
        });
        return List.copyOf(result);
    }

    /** Unpadded region used only as the translated-text drawing boundary. */
    static Area layoutMask(OcrRegion region, int imageWidth, int imageHeight) {
        Area result = maskWithoutPadding(region);
        result.intersect(new Area(new Rectangle(0, 0, imageWidth, imageHeight)));
        return result;
    }

    static Polygon polygon(List<OcrPoint> points) {
        Polygon result = new Polygon();
        points.forEach(point -> result.addPoint(point.x(), point.y()));
        return result;
    }

    /** Pixel area of a shape, by integrating its outline. */
    static double area(Area area) {
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
        // Padding follows how tall a glyph is across its line. For upright vertical writing that is the
        // width of the column, the first edge of the upright box, not the length of the column.
        boolean vertical = region.writingMode().vertical();
        List<Integer> heights = region.words().stream()
                .map(OcrWord::polygon)
                .filter(points -> points.size() >= 4)
                .map(points -> (int) Math.round(vertical
                        ? OcrRegion.uprightWidth(points)
                        : Math.hypot(
                                points.get(points.size() - 1).x() - points.get(0).x(),
                                points.get(points.size() - 1).y() - points.get(0).y())))
                .filter(height -> height > 0)
                .sorted()
                .toList();
        int height = heights.isEmpty()
                ? Math.max(1, vertical
                        ? maskWithoutPadding(region).getBounds().width
                        : maskWithoutPadding(region).getBounds().height)
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

    /** A candidate that passed every check of its own: only the total limit can still refuse it. */
    private record Eligible(int index, ImageRegionOverlay candidate, double modifiedRatio) {
    }
}
