package com.linetranslate.bot.service.ocr;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/** Splits only high-confidence, visibly separated compact labels. */
@Component
public class OcrRegionSegmenter {
    private static final int MAX_COMPACT_CODEPOINTS = 3;

    public List<OcrRegion> segment(List<OcrRegion> input) {
        List<OcrRegion> result = new ArrayList<>();
        for (OcrRegion region : input == null ? List.<OcrRegion>of() : input) {
            if (!isDiscreteLabelRow(region)) {
                result.add(region);
                continue;
            }
            List<OcrWord> orderedWords = region.words().stream()
                    .sorted(java.util.Comparator.comparingInt(word -> bounds(word.polygon()).x)).toList();
            int index = 0;
            for (OcrWord word : orderedWords) {
                List<OcrPoint> cell = cellPolygon(region, index, orderedWords);
                result.add(new OcrRegion(
                        region.id() + ".s" + (++index), word.text(), cell, List.of(word),
                        word.confidence(), word.confidenceKnown(), region.blockType(), region.languages(),
                        region.readingOrder() * 100 + index, region.id(), true));
            }
        }
        return List.copyOf(result);
    }

    private static boolean isDiscreteLabelRow(OcrRegion region) {
        if (region == null || region.orientation() != OcrOrientation.HORIZONTAL || region.words().size() < 2) {
            return false;
        }
        if (region.words().stream().anyMatch(word -> word.text().isBlank()
                || word.text().codePointCount(0, word.text().length()) > MAX_COMPACT_CODEPOINTS
                || word.polygon().size() < 4)) return false;
        boolean allSingleGlyph = region.words().stream()
                .allMatch(word -> word.text().codePointCount(0, word.text().length()) == 1);
        if (region.words().size() < 3 && !allSingleGlyph) {
            return false;
        }
        List<java.awt.Rectangle> bounds = region.words().stream()
                .map(word -> bounds(word.polygon())).sorted(java.util.Comparator.comparingInt(value -> value.x)).toList();
        int separated = 0;
        for (int i = 1; i < bounds.size(); i++) {
            int gap = bounds.get(i).x - (bounds.get(i - 1).x + bounds.get(i - 1).width);
            int glyphWidth = Math.min(bounds.get(i - 1).width, bounds.get(i).width);
            if (gap >= Math.max(3, glyphWidth / 2)) separated++;
        }
        return separated == bounds.size() - 1;
    }

    private static List<OcrPoint> cellPolygon(OcrRegion region, int index, List<OcrWord> words) {
        List<java.awt.Rectangle> ordered = words.stream().map(word -> bounds(word.polygon()))
                .sorted(java.util.Comparator.comparingInt(value -> value.x)).toList();
        java.awt.Rectangle paragraph = bounds(region.polygon());
        java.awt.Rectangle current = ordered.get(index);
        int left = index == 0 ? paragraph.x
                : (ordered.get(index - 1).x + ordered.get(index - 1).width + current.x) / 2;
        int right = index == ordered.size() - 1 ? paragraph.x + paragraph.width
                : (current.x + current.width + ordered.get(index + 1).x) / 2;
        return List.of(new OcrPoint(left, paragraph.y), new OcrPoint(right, paragraph.y),
                new OcrPoint(right, paragraph.y + paragraph.height),
                new OcrPoint(left, paragraph.y + paragraph.height));
    }

    private static java.awt.Rectangle bounds(List<OcrPoint> points) {
        int minX = points.stream().mapToInt(OcrPoint::x).min().orElse(0);
        int minY = points.stream().mapToInt(OcrPoint::y).min().orElse(0);
        int maxX = points.stream().mapToInt(OcrPoint::x).max().orElse(minX + 1);
        int maxY = points.stream().mapToInt(OcrPoint::y).max().orElse(minY + 1);
        return new java.awt.Rectangle(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
    }
}
