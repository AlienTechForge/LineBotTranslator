package com.linetranslate.bot.service.ocr;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable OCR unit. Polygon vertex order is preserved from the provider; after a rotated page is
 * brought upright the polygon is the provider polygon moved by a rigid quarter turn
 * ({@link QuarterTurns}), so the vertex roles, vertex 0 to 1 along the line and vertex 0 to 3 across
 * it, are the ones the provider gave.
 *
 * @param writingMode how the lines of the region run in the image; the frame default when absent
 * @param flowCells   the cells a flowing region spans in reading order, in image coordinates; empty
 *                    means the polygon is the single cell
 */
public record OcrRegion(
        String id,
        String text,
        List<OcrPoint> polygon,
        List<OcrWord> words,
        float confidence,
        boolean confidenceKnown,
        OcrBlockType blockType,
        List<OcrDetectedLanguage> languages,
        int readingOrder,
        String groupId,
        boolean compactLabel,
        WritingMode writingMode,
        List<List<OcrPoint>> flowCells) {

    public OcrRegion {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("OCR region ID is required");
        }
        text = text == null ? "" : text.strip();
        polygon = polygon == null ? List.of() : List.copyOf(polygon);
        words = words == null ? List.of() : List.copyOf(words);
        confidence = Math.max(0, Math.min(1, confidence));
        blockType = blockType == null ? OcrBlockType.UNKNOWN : blockType;
        languages = languages == null ? List.of() : List.copyOf(languages);
        readingOrder = Math.max(0, readingOrder);
        groupId = groupId == null || groupId.isBlank() ? id : groupId;
        writingMode = writingMode == null ? WritingMode.ofFrame(polygon) : writingMode;
        flowCells = flowCells == null ? List.of() : flowCells.stream().map(List::copyOf).toList();
    }

    public OcrRegion(
            String id, String text, List<OcrPoint> polygon, List<OcrWord> words,
            float confidence, boolean confidenceKnown, OcrBlockType blockType,
            List<OcrDetectedLanguage> languages, int readingOrder, String groupId,
            boolean compactLabel) {
        this(id, text, polygon, words, confidence, confidenceKnown, blockType,
                languages, readingOrder, groupId, compactLabel, null, List.of());
    }

    public OcrRegion(
            String id, String text, List<OcrPoint> polygon, List<OcrWord> words,
            float confidence, boolean confidenceKnown, OcrBlockType blockType,
            List<OcrDetectedLanguage> languages, int readingOrder) {
        this(id, text, polygon, words, confidence, confidenceKnown, blockType,
                languages, readingOrder, id, false);
    }

    public boolean validGeometry() {
        return polygon.size() >= 4 && area() > 1;
    }

    public double area() {
        if (polygon.size() < 3) return 0;
        long sum = 0;
        for (int i = 0; i < polygon.size(); i++) {
            OcrPoint a = polygon.get(i);
            OcrPoint b = polygon.get((i + 1) % polygon.size());
            sum += (long) a.x() * b.y() - (long) b.x() * a.y();
        }
        return Math.abs(sum) / 2.0;
    }

    public List<List<OcrPoint>> masks() {
        List<List<OcrPoint>> masks = new ArrayList<>();
        for (OcrWord word : words) {
            boolean completeSymbolGeometry = !word.symbols().isEmpty()
                    && word.symbols().stream().allMatch(symbol -> symbol.polygon().size() >= 4);
            if (completeSymbolGeometry) {
                word.symbols().stream().map(OcrSymbol::polygon).forEach(masks::add);
            } else if (word.polygon().size() >= 4) {
                masks.add(word.polygon());
            }
        }
        return masks.isEmpty() ? List.of(polygon) : List.copyOf(masks);
    }

    public double rotationDegrees() {
        if (polygon.size() < 2) return 0;
        OcrPoint first = polygon.get(0), second = polygon.get(1);
        return Math.toDegrees(Math.atan2(second.y() - first.y(), second.x() - first.x()));
    }

    /**
     * The polygon turned so that vertex 0 is the corner nearest the top-left of the image, which is
     * the reading frame of upright vertical writing. Vision gives a vertical column the frame of a
     * page turned a quarter (the line edge points down the image, and the top of the column is its
     * right side), so the provider order {@code (top-right, bottom-right, bottom-left, top-left)}
     * becomes {@code (top-left, top-right, bottom-right, bottom-left)}. A polygon already upright
     * is returned as it is. Meant for {@code VERTICAL_*} regions; a turned page is brought upright
     * by {@link QuarterTurns} instead.
     */
    public List<OcrPoint> uprightPolygon() {
        return uprightPolygon(polygon);
    }

    /** {@link #uprightPolygon()} of any polygon, by the quarter its first edge points at. */
    static List<OcrPoint> uprightPolygon(List<OcrPoint> polygon) {
        if (polygon.size() < 4) return polygon;
        OcrPoint first = polygon.get(0);
        OcrPoint second = polygon.get(1);
        double degrees = Math.toDegrees(Math.atan2(second.y() - first.y(), second.x() - first.x()));
        int quarter = (int) Math.floorMod(Math.round(degrees / 90), 4L);
        if (quarter == 0) return polygon;
        List<OcrPoint> upright = new ArrayList<>(polygon.size());
        for (int index = 0; index < polygon.size(); index++) {
            upright.add(polygon.get(Math.floorMod(index - quarter, polygon.size())));
        }
        return List.copyOf(upright);
    }

    /**
     * Length of the first edge of the upright polygon: how wide the shape is across a column of
     * vertical writing, whichever way the provider listed its corners.
     */
    static double uprightWidth(List<OcrPoint> polygon) {
        List<OcrPoint> upright = uprightPolygon(polygon);
        if (upright.size() < 2) return 0;
        return Math.hypot(upright.get(1).x() - upright.get(0).x(), upright.get(1).y() - upright.get(0).y());
    }

    /** The same region with other geometry; {@code null} derives the mode from the new polygon. */
    OcrRegion withGeometry(List<OcrPoint> polygon, List<OcrWord> words, WritingMode writingMode) {
        return new OcrRegion(id, text, polygon, words, confidence, confidenceKnown, blockType,
                languages, readingOrder, groupId, compactLabel, writingMode, flowCells);
    }

    /** The same region at another position in reading order. */
    OcrRegion withReadingOrder(int order) {
        return new OcrRegion(id, text, polygon, words, confidence, confidenceKnown, blockType,
                languages, order, groupId, compactLabel, writingMode, flowCells);
    }
}
