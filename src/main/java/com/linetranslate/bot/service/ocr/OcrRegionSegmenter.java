package com.linetranslate.bot.service.ocr;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** Splits reliable OCR paragraphs along visible row and column boundaries. */
@Component
public class OcrRegionSegmenter {
    private static final int MAX_COMPACT_CODEPOINTS = 3;
    private static final int MAX_DENSE_CHILDREN = 100;
    private static final Pattern PRICE_TOKEN = Pattern.compile(
            "^[\\p{Sc}\\s]*\\d[\\d,.'’]*(?:元|円|圓|원)?$");
    private static final Pattern SIZE_TOKEN = Pattern.compile("(?i)^(?:S|M|L|XL|XXL)$");

    public List<OcrRegion> segment(List<OcrRegion> input) {
        List<OcrRegion> result = new ArrayList<>();
        for (OcrRegion region : input == null ? List.<OcrRegion>of() : input) {
            List<OcrRegion> denseChildren = splitDenseParagraph(region);
            if (!denseChildren.isEmpty()) {
                result.addAll(denseChildren);
            } else if (isDiscreteLabelRow(region)) {
                result.addAll(splitDiscreteLabels(region));
            } else {
                result.add(region);
            }
        }
        List<OcrRegion> ordered = new ArrayList<>(result.size());
        for (int index = 0; index < result.size(); index++) {
            OcrRegion region = result.get(index);
            ordered.add(new OcrRegion(
                    region.id(), region.text(), region.polygon(), region.words(),
                    region.confidence(), region.confidenceKnown(), region.blockType(), region.languages(),
                    index, region.groupId(), region.compactLabel()));
        }
        return List.copyOf(ordered);
    }

    private static List<OcrRegion> splitDenseParagraph(OcrRegion region) {
        LocalFrame frame = LocalFrame.from(region);
        if (frame == null || region.words().size() < 3
                || region.words().stream().anyMatch(word -> word.text().isBlank()
                        || word.polygon().size() < 4)) {
            return List.of();
        }
        List<VisualLine> lines = visualLines(region.words(), frame);
        if (lines.isEmpty()) return List.of();

        Rectangle paragraph = frame.bounds();
        List<List<WordSegment>> segmentsByLine = new ArrayList<>(lines.size());
        for (VisualLine line : lines) {
            segmentsByLine.add(isStructuredPriceLine(line)
                    ? priceRowSegments(line) : wordSegments(line));
        }
        boolean structuredSingleLine = lines.size() == 1
                && isStructuredPriceLine(lines.get(0));
        if (lines.size() < 2 && !structuredSingleLine) return List.of();
        long columnarLines = segmentsByLine.stream().filter(segments -> segments.size() > 1).count();
        if (columnarLines == 0) return List.of();
        long structuredRows = lines.stream().filter(OcrRegionSegmenter::isStructuredPriceLine).count();
        boolean hasStructuredRows = structuredRows >= Math.max(1, (lines.size() + 1) / 2);
        if (!structuredSingleLine && !hasStructuredRows
                && !hasRepeatedColumnStructure(segmentsByLine, lines)) {
            return List.of();
        }
        int childCount = segmentsByLine.stream().mapToInt(List::size).sum();
        if (childCount < 2 || childCount > MAX_DENSE_CHILDREN) return List.of();

        List<OcrRegion> children = new ArrayList<>(childCount);
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            VisualLine line = lines.get(lineIndex);
            List<WordSegment> segments = segmentsByLine.get(lineIndex);
            int top = lineIndex == 0
                    ? paragraph.y
                    : midpoint(lines.get(lineIndex - 1).bottom(), line.top());
            int bottom = lineIndex == lines.size() - 1
                    ? paragraph.y + paragraph.height
                    : midpoint(line.bottom(), lines.get(lineIndex + 1).top());
            for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
                WordSegment segment = segments.get(segmentIndex);
                int left = segmentIndex == 0
                        ? paragraph.x
                        : midpoint(segments.get(segmentIndex - 1).right(), segment.left());
                int right = segmentIndex == segments.size() - 1
                        ? paragraph.x + paragraph.width
                        : midpoint(segment.right(), segments.get(segmentIndex + 1).left());
                String suffix = ".l" + (lineIndex + 1)
                        + (segments.size() > 1 ? ".c" + (segmentIndex + 1) : "");
                boolean compact = segments.size() > 1;
                children.add(child(region, frame, suffix, segment.words(), left, top, right, bottom,
                        compact));
            }
        }
        return List.copyOf(children);
    }

    private static boolean hasRepeatedColumnStructure(
            List<List<WordSegment>> segmentsByLine, List<VisualLine> lines) {
        for (int left = 0; left < segmentsByLine.size(); left++) {
            List<WordSegment> first = segmentsByLine.get(left);
            if (first.size() < 2) continue;
            for (int right = left + 1; right < segmentsByLine.size(); right++) {
                List<WordSegment> second = segmentsByLine.get(right);
                if (first.size() != second.size()) continue;
                int height = Math.min(lines.get(left).bounds().height, lines.get(right).bounds().height);
                int tolerance = Math.max(8, (int) Math.round(height * .50));
                boolean aligned = true;
                for (int column = 0; column < first.size(); column++) {
                    int firstCenter = midpoint(first.get(column).left(), first.get(column).right());
                    int secondCenter = midpoint(second.get(column).left(), second.get(column).right());
                    if (Math.abs(firstCenter - secondCenter) > tolerance) {
                        aligned = false;
                        break;
                    }
                }
                if (aligned) return true;
            }
        }
        return false;
    }

    private static boolean isStructuredPriceLine(VisualLine line) {
        List<WordBox> words = line.words().stream().sorted(Comparator.comparingInt(WordBox::left)).toList();
        return words.size() >= 2 && words.stream().skip(1).map(box -> box.word().text())
                .anyMatch(value -> PRICE_TOKEN.matcher(value).matches());
    }

    private static List<WordSegment> priceRowSegments(VisualLine line) {
        List<WordBox> words = line.words().stream().sorted(Comparator.comparingInt(WordBox::left)).toList();
        int structuredStart = -1;
        for (int index = 1; index < words.size(); index++) {
            String text = words.get(index).word().text();
            if (PRICE_TOKEN.matcher(text).matches() || SIZE_TOKEN.matcher(text).matches()) {
                structuredStart = index;
                break;
            }
        }
        if (structuredStart < 0) return wordSegments(line);
        List<WordSegment> segments = new ArrayList<>();
        segments.add(new WordSegment(List.copyOf(words.subList(0, structuredStart))));
        for (int index = structuredStart; index < words.size(); index++) {
            segments.add(new WordSegment(List.of(words.get(index))));
        }
        return List.copyOf(segments);
    }

    private static List<VisualLine> visualLines(List<OcrWord> words, LocalFrame frame) {
        List<WordBox> boxes = words.stream().map(word -> new WordBox(word, frame.bounds(word.polygon())))
                .sorted(Comparator.comparingInt(WordBox::centerY).thenComparingInt(WordBox::left))
                .toList();
        int medianHeight = median(boxes.stream().map(WordBox::height).toList());
        int centerTolerance = Math.max(3, (int) Math.round(medianHeight * .60));
        List<MutableLine> lines = new ArrayList<>();
        for (WordBox box : boxes) {
            MutableLine best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (MutableLine line : lines) {
                int distance = Math.abs(box.centerY() - line.centerY());
                if ((verticalOverlap(box.bounds(), line.bounds()) >= .45 || distance <= centerTolerance)
                        && distance < bestDistance) {
                    best = line;
                    bestDistance = distance;
                }
            }
            if (best == null) {
                lines.add(new MutableLine(box));
            } else {
                best.add(box);
            }
        }
        return lines.stream().map(MutableLine::freeze)
                .sorted(Comparator.comparingInt(VisualLine::top)).toList();
    }

    private static List<WordSegment> wordSegments(VisualLine line) {
        List<WordBox> words = line.words().stream().sorted(Comparator.comparingInt(WordBox::left)).toList();
        int medianHeight = median(words.stream().map(WordBox::height).toList());
        int splitGap = Math.max(6, (int) Math.ceil(medianHeight * .75));
        List<WordSegment> segments = new ArrayList<>();
        List<WordBox> current = new ArrayList<>();
        for (WordBox word : words) {
            if (!current.isEmpty()) {
                WordBox previous = current.get(current.size() - 1);
                int gap = word.left() - previous.right();
                if (gap >= splitGap) {
                    segments.add(new WordSegment(List.copyOf(current)));
                    current.clear();
                }
            }
            current.add(word);
        }
        if (!current.isEmpty()) segments.add(new WordSegment(List.copyOf(current)));
        return List.copyOf(segments);
    }

    private static List<OcrRegion> splitDiscreteLabels(OcrRegion region) {
        LocalFrame frame = LocalFrame.from(region);
        if (frame == null) return List.of(region);
        List<WordBox> orderedWords = region.words().stream()
                .map(word -> new WordBox(word, frame.bounds(word.polygon())))
                .sorted(Comparator.comparingInt(WordBox::left)).toList();
        List<OcrRegion> result = new ArrayList<>(orderedWords.size());
        for (int index = 0; index < orderedWords.size(); index++) {
            OcrWord word = orderedWords.get(index).word();
            List<OcrPoint> cell = cellPolygon(frame, index, orderedWords);
            result.add(new OcrRegion(
                    region.id() + ".s" + (index + 1), word.text(), cell, List.of(word),
                    word.confidence(), word.confidenceKnown(), region.blockType(), region.languages(),
                    0, region.groupId(), true));
        }
        return List.copyOf(result);
    }

    private static OcrRegion child(OcrRegion source, LocalFrame frame, String suffix, List<WordBox> boxes,
            int left, int top, int right, int bottom, boolean compact) {
        List<OcrWord> words = boxes.stream().map(WordBox::word).toList();
        boolean allWordConfidenceKnown = words.stream().allMatch(OcrWord::confidenceKnown);
        boolean confidenceKnown = allWordConfidenceKnown || source.confidenceKnown();
        float confidence = allWordConfidenceKnown
                ? words.stream().map(OcrWord::confidence).min(Float::compare).orElse(source.confidence())
                : source.confidence();
        return new OcrRegion(
                source.id() + suffix,
                joinWords(words),
                frame.polygon(left, top, Math.max(left + 1, right), Math.max(top + 1, bottom)),
                words,
                confidence,
                confidenceKnown,
                source.blockType(),
                source.languages(),
                0,
                source.groupId(),
                compact);
    }

    private static String joinWords(List<OcrWord> words) {
        StringBuilder result = new StringBuilder();
        for (OcrWord word : words) {
            if (!result.isEmpty() && needsSpace(result, word.text())) result.append(' ');
            result.append(word.text());
        }
        return result.toString();
    }

    private static boolean needsSpace(StringBuilder left, String right) {
        if (right.isEmpty()) return false;
        int previous = left.codePointBefore(left.length());
        int next = right.codePointAt(0);
        return !(isCjk(previous) && isCjk(next));
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static boolean isDiscreteLabelRow(OcrRegion region) {
        LocalFrame frame = LocalFrame.from(region);
        if (frame == null || region.words().size() < 2) {
            return false;
        }
        if (region.words().stream().anyMatch(word -> word.text().isBlank()
                || word.text().codePointCount(0, word.text().length()) > MAX_COMPACT_CODEPOINTS
                || word.polygon().size() < 4)) return false;
        boolean allSingleGlyph = region.words().stream()
                .allMatch(word -> word.text().codePointCount(0, word.text().length()) == 1);
        if (region.words().size() < 3 && !allSingleGlyph) return false;
        List<Rectangle> values = region.words().stream().map(word -> frame.bounds(word.polygon()))
                .sorted(Comparator.comparingInt(value -> value.x)).toList();
        int separated = 0;
        for (int i = 1; i < values.size(); i++) {
            int gap = values.get(i).x - (values.get(i - 1).x + values.get(i - 1).width);
            int glyphWidth = Math.min(values.get(i - 1).width, values.get(i).width);
            if (gap >= Math.max(3, glyphWidth / 2)) separated++;
        }
        return separated == values.size() - 1;
    }

    private static List<OcrPoint> cellPolygon(LocalFrame frame, int index, List<WordBox> words) {
        Rectangle paragraph = frame.bounds();
        WordBox current = words.get(index);
        int left = index == 0 ? paragraph.x
                : midpoint(words.get(index - 1).right(), current.left());
        int right = index == words.size() - 1 ? paragraph.x + paragraph.width
                : midpoint(current.right(), words.get(index + 1).left());
        return frame.polygon(left, paragraph.y, right, paragraph.y + paragraph.height);
    }

    private static double verticalOverlap(Rectangle left, Rectangle right) {
        int overlap = Math.max(0, Math.min(left.y + left.height, right.y + right.height)
                - Math.max(left.y, right.y));
        return (double) overlap / Math.max(1, Math.min(left.height, right.height));
    }

    private static int median(List<Integer> values) {
        List<Integer> sorted = values.stream().filter(value -> value > 0).sorted().toList();
        return sorted.isEmpty() ? 1 : sorted.get(sorted.size() / 2);
    }

    private static int midpoint(int left, int right) {
        return left + (right - left) / 2;
    }

    /** Maps OCR polygons to the paragraph's own reading axes before row/column analysis. */
    private record LocalFrame(
            double originX, double originY,
            double xAxisX, double xAxisY,
            double yAxisX, double yAxisY,
            double determinant, int width, int height) {
        private static LocalFrame from(OcrRegion region) {
            if (region == null || region.polygon().size() < 4) return null;
            OcrPoint origin = region.polygon().get(0);
            OcrPoint right = region.polygon().get(1);
            OcrPoint bottom = region.polygon().get(region.polygon().size() - 1);
            double xLength = Math.hypot(right.x() - origin.x(), right.y() - origin.y());
            double yLength = Math.hypot(bottom.x() - origin.x(), bottom.y() - origin.y());
            if (xLength <= 1 || yLength <= 1) return null;
            double xAxisX = (right.x() - origin.x()) / xLength;
            double xAxisY = (right.y() - origin.y()) / xLength;
            double yAxisX = (bottom.x() - origin.x()) / yLength;
            double yAxisY = (bottom.y() - origin.y()) / yLength;
            double determinant = xAxisX * yAxisY - xAxisY * yAxisX;
            if (Math.abs(determinant) < .20) return null;
            return new LocalFrame(origin.x(), origin.y(), xAxisX, xAxisY, yAxisX, yAxisY,
                    determinant, Math.max(1, (int) Math.round(xLength)),
                    Math.max(1, (int) Math.round(yLength)));
        }

        private Rectangle bounds() {
            return new Rectangle(0, 0, width, height);
        }

        private Rectangle bounds(List<OcrPoint> points) {
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (OcrPoint point : points) {
                double dx = point.x() - originX;
                double dy = point.y() - originY;
                double localX = (dx * yAxisY - dy * yAxisX) / determinant;
                double localY = (xAxisX * dy - xAxisY * dx) / determinant;
                minX = Math.min(minX, localX);
                minY = Math.min(minY, localY);
                maxX = Math.max(maxX, localX);
                maxY = Math.max(maxY, localY);
            }
            if (!Double.isFinite(minX)) return new Rectangle();
            int left = (int) Math.floor(minX);
            int top = (int) Math.floor(minY);
            int right = (int) Math.ceil(maxX);
            int bottom = (int) Math.ceil(maxY);
            return new Rectangle(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
        }

        private List<OcrPoint> polygon(int left, int top, int right, int bottom) {
            return List.of(point(left, top), point(right, top), point(right, bottom), point(left, bottom));
        }

        private OcrPoint point(double x, double y) {
            return new OcrPoint(
                    (int) Math.round(originX + xAxisX * x + yAxisX * y),
                    (int) Math.round(originY + xAxisY * x + yAxisY * y));
        }
    }

    private record WordBox(OcrWord word, Rectangle bounds) {
        int left() { return bounds.x; }
        int right() { return bounds.x + bounds.width; }
        int top() { return bounds.y; }
        int bottom() { return bounds.y + bounds.height; }
        int height() { return bounds.height; }
        int centerY() { return bounds.y + bounds.height / 2; }
    }

    private record VisualLine(List<WordBox> words, Rectangle bounds) {
        int top() { return bounds.y; }
        int bottom() { return bounds.y + bounds.height; }
    }

    private record WordSegment(List<WordBox> boxes) {
        List<WordBox> words() { return boxes; }
        int left() { return boxes.stream().mapToInt(WordBox::left).min().orElse(0); }
        int right() { return boxes.stream().mapToInt(WordBox::right).max().orElse(left() + 1); }
    }

    private static final class MutableLine {
        private final List<WordBox> words = new ArrayList<>();
        private Rectangle bounds;

        private MutableLine(WordBox first) {
            add(first);
        }

        private void add(WordBox word) {
            words.add(word);
            bounds = bounds == null ? new Rectangle(word.bounds()) : bounds.union(word.bounds());
        }

        private int centerY() { return bounds.y + bounds.height / 2; }
        private Rectangle bounds() { return bounds; }
        private VisualLine freeze() { return new VisualLine(List.copyOf(words), new Rectangle(bounds)); }
    }
}
