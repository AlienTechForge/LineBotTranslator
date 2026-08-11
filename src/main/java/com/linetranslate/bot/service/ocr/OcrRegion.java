package com.linetranslate.bot.service.ocr;

import java.util.List;

/** Immutable OCR unit. Polygon vertex order is preserved from the provider. */
public record OcrRegion(
        String id,
        String text,
        List<OcrPoint> polygon,
        List<OcrWord> words,
        float confidence,
        boolean confidenceKnown,
        OcrBlockType blockType,
        List<OcrDetectedLanguage> languages,
        int readingOrder) {

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
        List<List<OcrPoint>> wordMasks = words.stream()
                .map(OcrWord::polygon)
                .filter(value -> value.size() >= 4)
                .toList();
        return wordMasks.isEmpty() ? List.of(polygon) : wordMasks;
    }

    public double rotationDegrees() {
        if (polygon.size() < 2) return 0;
        OcrPoint first = polygon.get(0), second = polygon.get(1);
        return Math.toDegrees(Math.atan2(second.y() - first.y(), second.x() - first.x()));
    }

    public OcrOrientation orientation() {
        double normalized = Math.abs(rotationDegrees()) % 180;
        if (normalized <= 7 || normalized >= 173) return OcrOrientation.HORIZONTAL;
        if (normalized >= 83 && normalized <= 97) return OcrOrientation.VERTICAL;
        return OcrOrientation.ROTATED;
    }
}
