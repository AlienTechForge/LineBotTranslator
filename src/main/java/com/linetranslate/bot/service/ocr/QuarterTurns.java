package com.linetranslate.bot.service.ocr;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.List;

/**
 * Exact quarter-turn maps between a page as the provider saw it and the same page upright.
 *
 * <p>{@code quarterTurns} is the dominant frame quarter {@code q} of the page, so its text runs at
 * {@code q} x 90 degrees clockwise, with y pointing down. Making the page upright turns it
 * counter-clockwise by {@code q} quarters, and {@link #restore} turns it back clockwise. With the
 * source {@code W x H}:
 *
 * <pre>
 *  q  polygon point (continuous)   raster pixel  dst &lt;- src(x, y)   upright size
 *  1  (x, y) -> (y, W - x)         dst(y, W-1-x)                    H x W
 *  2  (x, y) -> (W - x, H - y)     dst(W-1-x, H-1-y)                W x H
 *  3  (x, y) -> (H - y, x)         dst(H-1-y, x)                    H x W
 * </pre>
 *
 * Polygons use the continuous map, because their coordinates are pixel boundaries: a box
 * {@code [x0, x1]} maps to {@code [W - x1, W - x0]} and covers exactly the pixels the raster map
 * moves the box's pixels to. Both maps are bijections on integers, so a round trip is exact and an
 * untouched pixel comes back identical. The map is rigid, so a polygon keeps the roles of its
 * vertices: after the map, the line edge of a rotated region points along +x.
 */
final class QuarterTurns {

    private QuarterTurns() {
    }

    /** Whether {@code quarterTurns} names a turn this class performs (1, 2 or 3). */
    static boolean isTurn(int quarterTurns) {
        return quarterTurns >= 1 && quarterTurns <= 3;
    }

    // --- polygons ----------------------------------------------------------------------------

    /** Maps a point of the provider frame ({@code width x height}) into the upright frame. */
    static OcrPoint toUpright(OcrPoint point, int quarterTurns, int width, int height) {
        return switch (quarterTurns) {
            case 1 -> new OcrPoint(point.y(), width - point.x());
            case 2 -> new OcrPoint(width - point.x(), height - point.y());
            case 3 -> new OcrPoint(height - point.y(), point.x());
            default -> point;
        };
    }

    /** Maps a point of the upright frame back into the provider frame ({@code width x height}). */
    static OcrPoint fromUpright(OcrPoint point, int quarterTurns, int width, int height) {
        return switch (quarterTurns) {
            case 1 -> new OcrPoint(width - point.y(), point.x());
            case 2 -> new OcrPoint(width - point.x(), height - point.y());
            case 3 -> new OcrPoint(point.y(), height - point.x());
            default -> point;
        };
    }

    /**
     * Every polygon of every region (the region's, its flow cells', each word's and each symbol's)
     * moved into the upright frame. Ids, texts, confidences and the order of the vertices are
     * kept, and the writing mode is derived again from the moved polygon.
     */
    static List<OcrRegion> toUpright(List<OcrRegion> regions, int quarterTurns, int width, int height) {
        return regions.stream().map(region -> map(region, quarterTurns, width, height, true)).toList();
    }

    /** The exact inverse of {@link #toUpright(List, int, int, int)}. */
    static List<OcrRegion> fromUpright(List<OcrRegion> regions, int quarterTurns, int width, int height) {
        return regions.stream().map(region -> map(region, quarterTurns, width, height, false)).toList();
    }

    private static OcrRegion map(OcrRegion region, int quarterTurns, int width, int height, boolean forward) {
        List<OcrWord> words = region.words().stream()
                .map(word -> new OcrWord(word.text(), points(word.polygon(), quarterTurns, width, height, forward),
                        word.confidence(), word.confidenceKnown(), word.symbols().stream()
                                .map(symbol -> new OcrSymbol(symbol.text(),
                                        points(symbol.polygon(), quarterTurns, width, height, forward),
                                        symbol.confidence(), symbol.confidenceKnown()))
                                .toList(), word.breakAfter()))
                .toList();
        OcrRegion moved = region.withGeometry(
                points(region.polygon(), quarterTurns, width, height, forward), words, null);
        if (region.flowCells().isEmpty()) return moved;
        return new OcrRegion(moved.id(), moved.text(), moved.polygon(), moved.words(), moved.confidence(),
                moved.confidenceKnown(), moved.blockType(), moved.languages(), moved.readingOrder(),
                moved.groupId(), moved.compactLabel(), moved.writingMode(), region.flowCells().stream()
                        .map(cell -> points(cell, quarterTurns, width, height, forward)).toList());
    }

    private static List<OcrPoint> points(
            List<OcrPoint> polygon, int quarterTurns, int width, int height, boolean forward) {
        return polygon.stream().map(point -> forward
                ? toUpright(point, quarterTurns, width, height)
                : fromUpright(point, quarterTurns, width, height)).toList();
    }

    // --- rasters -----------------------------------------------------------------------------

    /**
     * A new raster holding {@code source} turned counter-clockwise by {@code quarterTurns}, of the
     * type {@code ImageTranslationOverlayRenderer} copies to (ARGB when the source has alpha, RGB
     * otherwise). The source is left as it is.
     */
    static BufferedImage toUpright(BufferedImage source, int quarterTurns) {
        return turn(source, quarterTurns, true);
    }

    /** The exact inverse of {@link #toUpright(BufferedImage, int)}: turns clockwise. */
    static BufferedImage restore(BufferedImage upright, int quarterTurns) {
        return turn(upright, quarterTurns, false);
    }

    /**
     * Row by row through {@code getRGB}, so any source raster type is read the same way, into the
     * backing array of an int raster. O(width x height) with no per-pixel object, which keeps a
     * 16 MP page to tens of milliseconds.
     */
    private static BufferedImage turn(BufferedImage source, int quarterTurns, boolean toUpright) {
        int width = source.getWidth();
        int height = source.getHeight();
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        if (!isTurn(quarterTurns)) {
            throw new IllegalArgumentException("A page is turned by 1, 2 or 3 quarters");
        }
        boolean swaps = quarterTurns != 2;
        int targetWidth = swaps ? height : width;
        int targetHeight = swaps ? width : height;
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, type);
        int[] pixels = ((DataBufferInt) target.getRaster().getDataBuffer()).getData();
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            source.getRGB(0, y, width, 1, row, 0, width);
            // quarters counter-clockwise from the source's point of view
            int turns = toUpright ? quarterTurns : 4 - quarterTurns;
            for (int x = 0; x < width; x++) {
                int targetX;
                int targetY;
                switch (turns) {
                    case 1 -> { targetX = y; targetY = width - 1 - x; }
                    case 2 -> { targetX = width - 1 - x; targetY = height - 1 - y; }
                    default -> { targetX = height - 1 - y; targetY = x; }
                }
                pixels[targetY * targetWidth + targetX] = row[x];
            }
        }
        return target;
    }
}
