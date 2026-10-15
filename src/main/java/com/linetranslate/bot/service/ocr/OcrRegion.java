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
     * The line direction in the three buckets this class started with.
     *
     * @deprecated use {@link #writingMode()}, which tells the quarter turns apart. Kept, with its
     *             exact bands, for callers and tests that pin them.
     */
    @Deprecated
    public OcrOrientation orientation() {
        double normalized = Math.abs(rotationDegrees()) % 180;
        if (normalized <= 7 || normalized >= 173) return OcrOrientation.HORIZONTAL;
        if (normalized >= 83 && normalized <= 97) return OcrOrientation.VERTICAL;
        return OcrOrientation.ROTATED;
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
