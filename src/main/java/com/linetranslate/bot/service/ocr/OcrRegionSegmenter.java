package com.linetranslate.bot.service.ocr;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Splits reliable OCR paragraphs along visible row and column boundaries. Before that, a short
 * wrapped tail that Vision reported as a block of its own is folded back into its paragraph by
 * {@link OcrTrailingFragmentMerger}, because qualification would otherwise judge it alone. The
 * merge is only kept when qualification, with the confidence threshold the pipeline applies,
 * would still translate the merged paragraph.
 */
@Component
public class OcrRegionSegmenter {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OcrRegionSegmenter.class);
    /** Only paragraphs this dense are worth reporting; smaller ones are not the layout problem. */
    private static final int DECISION_REPORT_MIN_WORDS = 8;
    /** Fewer glyphs than this cannot establish a column layout with any confidence. */
    private static final int MIN_VERTICAL_LAYOUT_WORDS = 6;
    private static final int MAX_COMPACT_CODEPOINTS = 3;
    private static final int MAX_DENSE_CHILDREN = 100;
    /** An item gap in a column is also at least this many times the typical gap between its glyphs. */
    static final double TRACKING_GAP_FACTOR = 2.5;
    /** The typical glyph gap of a column is only taken from at least this many gaps. */
    private static final int MIN_GAPS_FOR_TRACKING = 2;
    private static final Pattern PRICE_TOKEN = Pattern.compile(
            "^[\\p{Sc}\\s]*\\d[\\d,.'’]*(?:元|円|圓|원)?$");
    private static final Pattern SIZE_TOKEN = Pattern.compile("(?i)^(?:S|M|L|XL|XXL)$");

    private final OcrRegionQualificationPolicy qualificationPolicy;
    private final float confidenceThreshold;

    /** The deployment defaults, for callers that are not wired from {@link ImageTranslationProperties}. */
    public OcrRegionSegmenter() {
        this(new OcrRegionQualificationPolicy(), ImageTranslationProperties.defaults().lowConfidenceThreshold());
    }

    /** Judges a merge by the same policy and threshold the image pipeline qualifies regions with. */
    @Autowired
    public OcrRegionSegmenter(
            OcrRegionQualificationPolicy qualificationPolicy, ImageTranslationProperties properties) {
        this(qualificationPolicy, properties.lowConfidenceThreshold());
    }

    OcrRegionSegmenter(OcrRegionQualificationPolicy qualificationPolicy, float confidenceThreshold) {
        this.qualificationPolicy = qualificationPolicy;
        this.confidenceThreshold = confidenceThreshold;
    }

    public List<OcrRegion> segment(List<OcrRegion> input) {
        List<OcrRegion> result = new ArrayList<>();
        for (OcrRegion region : OcrTrailingFragmentMerger.merge(
                input == null ? List.<OcrRegion>of() : input, qualificationPolicy, confidenceThreshold)) {
            List<OcrRegion> denseChildren = splitDenseParagraph(region);
            if (!denseChildren.isEmpty()) {
                result.addAll(denseChildren);
            } else if (isDiscreteLabelRow(region)) {
                result.addAll(splitDiscreteLabels(region));
            } else {
                result.add(withColumnMode(region));
            }
        }
        List<OcrRegion> ordered = new ArrayList<>(result.size());
        for (int index = 0; index < result.size(); index++) {
            ordered.add(result.get(index).withReadingOrder(index));
        }
        return List.copyOf(ordered);
    }

    /**
     * A region kept whole is checked once for being a single column of upright vertical writing.
     * Only a region that is one keeps anything from this: any other comes back as it was.
     */
    private static OcrRegion withColumnMode(OcrRegion region) {
        WritingMode mode = WritingModeClassifier.columnMode(region);
        return mode == region.writingMode()
                ? region : region.withGeometry(region.polygon(), region.words(), mode);
    }

    private static List<OcrRegion> splitDenseParagraph(OcrRegion region) {
        LocalFrame frame = LocalFrame.from(region);
        // Vertical writing arrives from the page verdict with words that are runs of glyphs, and a
        // paragraph of two columns has two of them, so two words are enough to look for columns.
        boolean vertical = region.writingMode().vertical();
        if (frame == null || region.words().size() < (vertical ? 2 : 3)
                || region.words().stream().anyMatch(word -> word.text().isBlank()
                        || word.polygon().size() < 4)) {
            return List.of();
        }
        List<WordBox> readingOrder = region.words().stream()
                .map(word -> new WordBox(word, frame.bounds(word.polygon()))).toList();
        // Words of vertical writing may be runs of several glyphs, which the single-glyph test of
        // the column layout would refuse, and its lines are never rows: it only splits into columns.
        if (vertical || isVerticalColumnLayout(readingOrder)) {
            List<OcrRegion> columnChildren = splitVerticalColumns(region, frame, readingOrder);
            if (!columnChildren.isEmpty()) {
                boolean flow = columnChildren.stream().anyMatch(child -> !child.flowCells().isEmpty());
                reportDecision(region, columnChildren.size(), 0, 0, false, columnChildren.size(),
                        flow ? "split-vertical-flow" : "split-vertical-columns");
                return columnChildren;
            }
            if (vertical) {
                reportDecision(region, 0, 0, 0, false, 0, "kept-vertical-single-column");
                return List.of();
            }
        }
        List<VisualLine> lines = visualLines(region.words(), frame);
        if (lines.isEmpty()) return List.of();

        Rectangle paragraph = frame.bounds();
        if (isCompactSingleColumn(lines)) {
            List<OcrRegion> compactChildren = splitCompactSingleColumn(region, frame, lines, paragraph);
            reportDecision(region, lines.size(), 0, 0, false, compactChildren.size(),
                    "split-compact-single-column");
            return compactChildren;
        }
        List<List<WordSegment>> segmentsByLine = new ArrayList<>(lines.size());
        for (VisualLine line : lines) {
            segmentsByLine.add(isStructuredPriceLine(line)
                    ? priceRowSegments(line) : wordSegments(line));
        }
        boolean structuredSingleLine = lines.size() == 1
                && isStructuredPriceLine(lines.get(0));
        if (lines.size() < 2 && !structuredSingleLine) {
            reportDecision(region, lines.size(), 0, 0, false, 0, "kept-single-line");
            return List.of();
        }
        long columnarLines = segmentsByLine.stream().filter(segments -> segments.size() > 1).count();
        if (columnarLines == 0) {
            reportDecision(region, lines.size(), 0, 0, false, 0, "kept-no-columnar-line");
            return List.of();
        }
        long structuredRows = lines.stream().filter(OcrRegionSegmenter::isStructuredPriceLine).count();
        boolean hasStructuredRows = structuredRows >= Math.max(1, (lines.size() + 1) / 2);
        boolean repeatedColumns = hasRepeatedColumnStructure(segmentsByLine, lines);
        if (!structuredSingleLine && !hasStructuredRows && !repeatedColumns) {
            reportDecision(region, lines.size(), (int) columnarLines, structuredRows, false, 0,
                    "kept-no-column-structure");
            return List.of();
        }
        int childCount = segmentsByLine.stream().mapToInt(List::size).sum();
        if (childCount < 2 || childCount > MAX_DENSE_CHILDREN) {
            reportDecision(region, lines.size(), (int) columnarLines, structuredRows, repeatedColumns,
                    childCount, "kept-child-count-out-of-range");
            return List.of();
        }
        reportDecision(region, lines.size(), (int) columnarLines, structuredRows, repeatedColumns,
                childCount, "split-rows-and-columns");

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

    /**
     * Why a dense paragraph was or was not split. A paragraph that stays whole becomes one long
     * translation laid out horizontally, which is what destroys a column layout, so the blocking
     * condition needs to be visible. Counts and flags only; no source text reaches the log.
     */
    private static void reportDecision(OcrRegion region, int lineCount, int columnarLines,
            long structuredRows, boolean repeatedColumns, int childCount, String verdict) {
        int wordCount = region.words().size();
        if (wordCount < DECISION_REPORT_MIN_WORDS) return;
        Rectangle bounds = OverlaySafetyPolicy.polygon(region.polygon()).getBounds();
        String source = region.text();
        int sourceChars = source.codePointCount(0, source.length());
        int[] axis = axisNeighbourCounts(region.words());
        log.info("Dense paragraph split decision: verdict={}, words={}, lines={}, columnarLines={}, "
                        + "structuredRows={}, repeatedColumns={}, children={}, "
                        + "regionWidth={}, regionHeight={}, sourceChars={}, "
                        + "verticalNeighbours={}, horizontalNeighbours={}",
                verdict, wordCount, lineCount, columnarLines, structuredRows,
                repeatedColumns, childCount, bounds.width, bounds.height, sourceChars,
                axis[0], axis[1]);
    }

    /**
     * How many words sit closer to a vertical neighbour than to a horizontal one. Reading order
     * proved unreliable for deciding the axis, because Vision scans some vertical layouts
     * horizontally, so the answer has to come from geometry: a word in a top-to-bottom column has
     * its nearest neighbour above or below, not beside it.
     */
    private static int[] axisNeighbourCounts(List<OcrWord> words) {
        List<Rectangle> boxes = words.stream()
                .map(word -> OverlaySafetyPolicy.polygon(word.polygon()).getBounds()).toList();
        int vertical = 0;
        int horizontal = 0;
        for (int index = 0; index < boxes.size(); index++) {
            Rectangle box = boxes.get(index);
            int nearestVertical = Integer.MAX_VALUE;
            int nearestHorizontal = Integer.MAX_VALUE;
            for (int other = 0; other < boxes.size(); other++) {
                if (other == index) continue;
                Rectangle candidate = boxes.get(other);
                if (horizontalOverlap(box, candidate) >= .5) {
                    int gap = candidate.y >= box.y + box.height
                            ? candidate.y - (box.y + box.height)
                            : box.y - (candidate.y + candidate.height);
                    if (gap >= 0) nearestVertical = Math.min(nearestVertical, gap);
                }
                if (verticalOverlap(box, candidate) >= .5) {
                    int gap = candidate.x >= box.x + box.width
                            ? candidate.x - (box.x + box.width)
                            : box.x - (candidate.x + candidate.width);
                    if (gap >= 0) nearestHorizontal = Math.min(nearestHorizontal, gap);
                }
            }
            if (nearestVertical < nearestHorizontal) {
                vertical++;
            } else if (nearestHorizontal < nearestVertical) {
                horizontal++;
            }
        }
        return new int[] {vertical, horizontal};
    }


    /**
     * Whether the paragraph is laid out in top-to-bottom columns.
     *
     * <p>Reading order cannot answer this: Vision scans some vertical layouts horizontally, so
     * consecutive words step sideways across unrelated columns. Geometry can. In a vertical column
     * the nearest neighbour below a glyph is the next glyph of the same label and sits flush
     * against it, while in horizontal text the nearest neighbour below is the next line, a whole
     * line gap away. Measured on real pages: a vertical menu gives 2px below against 4px beside,
     * horizontal prose gives 31px below against 6px beside.
     *
     * <p>Vertical CJK also arrives one glyph per word, so a high single-glyph share is required as
     * a second, independent signal before splitting along the vertical axis.
     */
    private static boolean isVerticalColumnLayout(List<WordBox> boxes) {
        if (boxes.size() < MIN_VERTICAL_LAYOUT_WORDS) return false;
        long singleGlyph = boxes.stream()
                .map(box -> box.word().text())
                .filter(text -> text.codePointCount(0, text.length()) == 1)
                .count();
        if (singleGlyph * 5 < (long) boxes.size() * 4) return false;

        List<Integer> beside = new ArrayList<>();
        List<Integer> below = new ArrayList<>();
        for (WordBox box : boxes) {
            Integer nearestBeside = null;
            Integer nearestBelow = null;
            for (WordBox other : boxes) {
                if (other == box) continue;
                if (other.left() >= box.right()
                        && verticalOverlap(box.bounds(), other.bounds()) >= .5) {
                    int gap = other.left() - box.right();
                    nearestBeside = nearestBeside == null ? gap : Math.min(nearestBeside, gap);
                }
                if (other.top() >= box.bottom()
                        && horizontalOverlap(box.bounds(), other.bounds()) >= .5) {
                    int gap = other.top() - box.bottom();
                    nearestBelow = nearestBelow == null ? gap : Math.min(nearestBelow, gap);
                }
            }
            if (nearestBeside != null) beside.add(nearestBeside);
            if (nearestBelow != null) below.add(nearestBelow);
        }
        if (below.size() < 4 || beside.isEmpty()) return false;
        return medianAllowingZero(below) < medianAllowingZero(beside);
    }

    /** Gaps of zero are meaningful here, so unlike {@link #median} nothing is filtered out. */
    private static int medianAllowingZero(List<Integer> values) {
        List<Integer> sorted = values.stream().sorted().toList();
        return sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
    }

    /** Column-wise counterpart of visualLines, for paragraphs that read top-to-bottom. */
    private static List<VisualColumn> visualColumns(List<WordBox> boxes) {
        List<WordBox> sorted = boxes.stream()
                .sorted(Comparator.comparingInt(WordBox::centerX).thenComparingInt(WordBox::top))
                .toList();
        int medianWidth = median(sorted.stream().map(box -> box.bounds().width).toList());
        // Match against the column's first glyph rather than its growing bounds. Bounds widen with
        // every glyph added, so one mistaken match lets the column swallow its neighbour and then
        // the one after that. A column of vertical text holds its horizontal centre, so the anchor
        // stays valid, and half a glyph width keeps adjacent columns apart.
        int centerTolerance = Math.max(3, medianWidth / 2);
        List<MutableColumn> columns = new ArrayList<>();
        for (WordBox box : sorted) {
            MutableColumn best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (MutableColumn column : columns) {
                int distance = Math.abs(box.centerX() - column.anchorX());
                if (distance <= centerTolerance && distance < bestDistance) {
                    best = column;
                    bestDistance = distance;
                }
            }
            if (best == null) {
                columns.add(new MutableColumn(box));
            } else {
                best.add(box);
            }
        }
        return columns.stream().map(MutableColumn::freeze)
                .sorted(Comparator.comparingInt(VisualColumn::left)).toList();
    }

    /**
     * Splits a top-to-bottom paragraph into one region per item, or into one region per flow of
     * columns when the paragraph is vertical prose. Columns come first, then each column is cut at
     * its vertical gaps so a run of characters forming one label stays together.
     *
     * <p>Children are emitted in reading order, and that order is what the translation request and
     * the plain-text reply follow. Vertical CJK is read from the rightmost column to the leftmost,
     * while Latin stacked in a column keeps the left-to-right order of the page. The suffix counts
     * columns in the emitted order, so ids stay unique and follow the order they were emitted in.
     *
     * <p>Children of a paragraph that stands upright in the image are vertical writing, right to
     * left for CJK and left to right otherwise. A paragraph that was analysed in a turned frame is
     * not: its children keep the mode their own frame gives them.
     */
    private static List<OcrRegion> splitVerticalColumns(
            OcrRegion region, LocalFrame frame, List<WordBox> readingOrder) {
        List<VisualColumn> columns = visualColumns(readingOrder);
        if (columns.size() < 2) return List.of();
        boolean rightToLeft = isMostlyCjk(readingOrder);
        WritingMode mode = columnChildMode(region, rightToLeft);
        Rectangle paragraph = frame.bounds();
        int[] lefts = new int[columns.size()];
        int[] rights = new int[columns.size()];
        List<List<WordSegment>> items = new ArrayList<>(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            VisualColumn column = columns.get(index);
            lefts[index] = index == 0
                    ? paragraph.x
                    : midpoint(columns.get(index - 1).right(), column.left());
            rights[index] = index == columns.size() - 1
                    ? paragraph.x + paragraph.width
                    : midpoint(column.right(), columns.get(index + 1).left());
            items.add(verticalSegments(column));
        }
        // Visual column indices in reading order.
        List<Integer> order = new ArrayList<>(columns.size());
        for (int position = 0; position < columns.size(); position++) {
            order.add(rightToLeft ? columns.size() - 1 - position : position);
        }
        if (mode == WritingMode.VERTICAL_RL) {
            List<List<Integer>> runs = flowRuns(region, paragraph, columns, items, order);
            if (runs != null) {
                return flowChildren(region, frame, items, lefts, rights, runs);
            }
        }
        List<OcrRegion> children = new ArrayList<>();
        for (int position = 0; position < order.size(); position++) {
            // columnIndex is the visual position, which is what the cell bounds depend on.
            int columnIndex = order.get(position);
            List<WordSegment> column = items.get(columnIndex);
            for (int itemIndex = 0; itemIndex < column.size(); itemIndex++) {
                WordSegment item = column.get(itemIndex);
                int top = itemIndex == 0
                        ? paragraph.y
                        : midpoint(column.get(itemIndex - 1).bottom(), item.top());
                int bottom = itemIndex == column.size() - 1
                        ? paragraph.y + paragraph.height
                        : midpoint(item.bottom(), column.get(itemIndex + 1).top());
                children.add(child(region, frame, ".v" + (position + 1) + "." + (itemIndex + 1),
                        item.words(), lefts[columnIndex], top, rights[columnIndex], bottom, false,
                        mode, List.of()));
            }
        }
        return children.size() < 2 || children.size() > MAX_DENSE_CHILDREN
                ? List.of() : List.copyOf(children);
    }

    /**
     * The mode of the children of a column split: vertical writing when the paragraph stands
     * upright in the image (an axis-aligned paragraph, or one already known to be vertical), and
     * none, so that the frame decides, for a paragraph that was analysed in a turned frame.
     */
    private static WritingMode columnChildMode(OcrRegion region, boolean rightToLeft) {
        WritingMode source = region.writingMode();
        if (source != WritingMode.HORIZONTAL && !source.vertical()) return null;
        return rightToLeft ? WritingMode.VERTICAL_RL : WritingMode.VERTICAL_LR;
    }

    // --- flows of columns --------------------------------------------------------------------

    /** Columns and total glyphs a flow needs at the least. */
    static final int FLOW_MIN_COLUMNS = 2;
    static final int FLOW_MIN_GLYPHS = 8;
    /** A flow's typical column is at least this long; a menu of equal-length names is not one. */
    static final int FLOW_MIN_MEDIAN_COLUMN_GLYPHS = 10;
    /** Largest gap between columns, in median glyph widths. */
    static final double FLOW_MAX_GAP_GLYPHS = 1.0;
    /** How far a gap may stray from the median gap, relative to it. */
    static final double FLOW_GAP_TOLERANCE = .50;
    /** A price at the foot of a column makes it a list item, not a line of prose. */
    private static final Pattern TRAILING_PRICE = Pattern.compile(
            "\\p{Sc}*\\d[\\d,.'’]*(?:元|円|圓|원)?$");

    /**
     * The runs of columns, each a list of visual column indices in reading order, that form flows
     * of vertical prose, or null when the paragraph is not one. A run is a flow only if all of
     * these hold, and every run of the paragraph has to be one:
     * <ol>
     *   <li>at least two columns and eight glyphs;</li>
     *   <li>each column is one item, with no item gap inside it;</li>
     *   <li>every column but the run's last spans the paragraph's height, top and bottom within one
     *       median glyph height of it; the last needs only to be top-aligned;</li>
     *   <li>the pitch is regular: each gap between columns is at most one median glyph width and
     *       within 50% of the median gap;</li>
     *   <li>the median column holds at least ten glyphs;</li>
     *   <li>when the provider recorded breaks (known only for vertical writing it read as such),
     *       each column but the run's last ends in a wrap, and a paragraph end closes the run;</li>
     *   <li>no column ends in a price.</li>
     * </ol>
     */
    private static List<List<Integer>> flowRuns(OcrRegion region, Rectangle paragraph,
            List<VisualColumn> columns, List<List<WordSegment>> items, List<Integer> order) {
        for (List<WordSegment> column : items) {
            if (column.size() != 1) return null;
        }
        boolean breaks = region.writingMode().vertical() && recordsBreaks(region);
        List<List<Integer>> runs = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        for (int position = 0; position < order.size(); position++) {
            int index = order.get(position);
            current.add(index);
            if (!breaks) continue;
            OcrBreak after = lastBreak(columns.get(index));
            if (after == OcrBreak.PARAGRAPH_END) {
                runs.add(current);
                current = new ArrayList<>();
            } else if (position < order.size() - 1 && after != OcrBreak.WRAP) {
                return null;
            }
        }
        if (!current.isEmpty()) runs.add(current);
        for (List<Integer> run : runs) {
            if (!isFlow(run, paragraph, columns)) return null;
        }
        return runs;
    }

    private static boolean recordsBreaks(OcrRegion region) {
        return region.words().stream().anyMatch(
                word -> word.breakAfter() == OcrBreak.WRAP || word.breakAfter() == OcrBreak.PARAGRAPH_END);
    }

    /** The break recorded after the lowest word of a column. */
    private static OcrBreak lastBreak(VisualColumn column) {
        return column.words().stream().max(Comparator.comparingInt(WordBox::bottom))
                .map(box -> box.word().breakAfter()).orElse(OcrBreak.NONE);
    }

    private static boolean isFlow(List<Integer> run, Rectangle paragraph, List<VisualColumn> columns) {
        if (run.size() < FLOW_MIN_COLUMNS) return false;
        List<VisualColumn> members = run.stream().map(columns::get).toList();
        List<WordBox> words = members.stream().flatMap(column -> column.words().stream()).toList();
        List<Integer> glyphCounts = members.stream().map(column -> glyphs(column.words())).toList();
        if (glyphCounts.stream().mapToInt(Integer::intValue).sum() < FLOW_MIN_GLYPHS) return false;
        if (lowerMedian(glyphCounts) < FLOW_MIN_MEDIAN_COLUMN_GLYPHS) return false;

        int glyphHeight = median(words.stream()
                .map(box -> box.height() / Math.max(1, box.word().text().codePointCount(
                        0, box.word().text().length()))).toList());
        int glyphWidth = median(words.stream().map(box -> box.bounds().width).toList());
        for (int position = 0; position < members.size(); position++) {
            Rectangle bounds = members.get(position).bounds();
            boolean last = position == members.size() - 1;
            if (Math.abs(bounds.y - paragraph.y) > glyphHeight) return false;
            if (!last && Math.abs(bounds.y + bounds.height - (paragraph.y + paragraph.height)) > glyphHeight) {
                return false;
            }
        }
        // The columns of a run read right to left, so each one's left edge faces the next one.
        List<Integer> gaps = new ArrayList<>();
        for (int position = 0; position + 1 < members.size(); position++) {
            gaps.add(members.get(position).left() - members.get(position + 1).right());
        }
        int medianGap = lowerMedian(gaps);
        for (int gap : gaps) {
            if (gap > glyphWidth * FLOW_MAX_GAP_GLYPHS) return false;
            // One pixel of slack: the bounds are whole pixels, and a median of zero would otherwise
            // refuse every gap that is not exactly zero.
            if (Math.abs(gap - medianGap) > Math.max(1, Math.abs(medianGap) * FLOW_GAP_TOLERANCE)) return false;
        }
        for (VisualColumn column : members) {
            String joined = joinWords(column.words().stream().sorted(Comparator.comparingInt(WordBox::top))
                    .map(WordBox::word).toList(), null).replaceAll("\\s+", "");
            if (TRAILING_PRICE.matcher(joined).find()) return false;
        }
        return true;
    }

    private static int glyphs(List<WordBox> words) {
        return words.stream().mapToInt(box -> (int) box.word().text().codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint)).count()).sum();
    }

    /** The lower of the two middle values: the smaller one, when only two are known. */
    private static int lowerMedian(List<Integer> values) {
        List<Integer> sorted = values.stream().sorted().toList();
        return sorted.isEmpty() ? 0 : sorted.get((sorted.size() - 1) / 2);
    }

    /**
     * One region per run. The text reads the columns right to left, each from the top, and the
     * cells are the columns' own cells in the same order, reaching the paragraph's top and bottom.
     */
    private static List<OcrRegion> flowChildren(OcrRegion region, LocalFrame frame,
            List<List<WordSegment>> items, int[] lefts, int[] rights, List<List<Integer>> runs) {
        Rectangle paragraph = frame.bounds();
        List<OcrRegion> children = new ArrayList<>(runs.size());
        for (int number = 0; number < runs.size(); number++) {
            List<Integer> run = runs.get(number);
            List<WordBox> words = new ArrayList<>();
            List<List<OcrPoint>> cells = new ArrayList<>(run.size());
            int left = Integer.MAX_VALUE;
            int right = Integer.MIN_VALUE;
            for (int index : run) {
                words.addAll(items.get(index).get(0).words());
                cells.add(frame.polygon(lefts[index], paragraph.y, Math.max(lefts[index] + 1, rights[index]),
                        paragraph.y + paragraph.height));
                left = Math.min(left, lefts[index]);
                right = Math.max(right, rights[index]);
            }
            children.add(child(region, frame, ".f" + (number + 1), words, left, paragraph.y,
                    Math.max(left + 1, right), paragraph.y + paragraph.height, false,
                    WritingMode.VERTICAL_RL, cells));
        }
        return List.copyOf(children);
    }

    /**
     * Whether a strict majority of the paragraph's letters are CJK, judged over all of its words.
     * Common-script letters such as the prolonged sound mark in katakana are not evidence for
     * either script, so they are left out of the count. A tie, or text with no letters at all,
     * is not mostly CJK and keeps the page's left-to-right order.
     */
    private static boolean isMostlyCjk(List<WordBox> boxes) {
        return WritingModeClassifier.isMostlyCjk(boxes.stream().map(box -> box.word().text()).toList());
    }

    /**
     * Cuts a column into items at its vertical gaps. A gap is an item boundary when it is at least
     * three quarters of the median glyph height (and six pixels), and also at least two and a half
     * times the typical gap between neighbouring glyphs of the same column: a name tracked loosely,
     * with a wide gap between each of its glyphs, is one item, and the gap between two names has to
     * be wider than that. The typical gap is the lower median of the column's own gaps, and is only
     * taken when the column has at least two gaps to take it from.
     */
    private static List<WordSegment> verticalSegments(VisualColumn column) {
        List<WordBox> words = column.words().stream()
                .sorted(Comparator.comparingInt(WordBox::top)).toList();
        int medianHeight = median(words.stream().map(WordBox::height).toList());
        int splitGap = Math.max(6, (int) Math.ceil(medianHeight * .75));
        List<Integer> glyphGaps = new ArrayList<>();
        for (int index = 1; index < words.size(); index++) {
            glyphGaps.add(Math.max(0, words.get(index).top() - words.get(index - 1).bottom()));
        }
        if (glyphGaps.size() >= MIN_GAPS_FOR_TRACKING) {
            splitGap = Math.max(splitGap, (int) Math.ceil(TRACKING_GAP_FACTOR * lowerMedian(glyphGaps)));
        }
        List<WordSegment> segments = new ArrayList<>();
        List<WordBox> current = new ArrayList<>();
        for (WordBox word : words) {
            if (!current.isEmpty()) {
                WordBox previous = current.get(current.size() - 1);
                if (word.top() - previous.bottom() >= splitGap) {
                    segments.add(new WordSegment(List.copyOf(current)));
                    current.clear();
                }
            }
            current.add(word);
        }
        if (!current.isEmpty()) segments.add(new WordSegment(List.copyOf(current)));
        return List.copyOf(segments);
    }

    private static double horizontalOverlap(Rectangle left, Rectangle right) {
        int overlap = Math.max(0, Math.min(left.x + left.width, right.x + right.width)
                - Math.max(left.x, right.x));
        return (double) overlap / Math.max(1, Math.min(left.width, right.width));
    }

    private static boolean isCompactSingleColumn(List<VisualLine> lines) {
        if (lines.size() < 3 || lines.size() > 40
                || lines.stream().anyMatch(line -> line.words().size() != 1)) return false;
        List<Integer> lengths = lines.stream()
                .map(line -> line.words().get(0).word().text().codePointCount(
                        0, line.words().get(0).word().text().length()))
                .toList();
        int medianLength = median(lengths);
        int medianHeight = median(lines.stream().map(line -> line.bounds().height).toList());
        int medianGap = median(java.util.stream.IntStream.range(1, lines.size())
                .map(index -> lines.get(index).top() - lines.get(index - 1).bottom())
                .boxed().toList());
        return medianLength >= 2 && medianLength <= 10
                && medianGap <= Math.max(10, medianHeight);
    }

    private static List<OcrRegion> splitCompactSingleColumn(
            OcrRegion region, LocalFrame frame, List<VisualLine> lines, Rectangle paragraph) {
        List<OcrRegion> result = new ArrayList<>(lines.size());
        int contentLeft = lines.stream().flatMap(line -> line.words().stream())
                .mapToInt(WordBox::left).min().orElse(paragraph.x);
        for (int index = 0; index < lines.size(); index++) {
            VisualLine line = lines.get(index);
            int top = index == 0 ? paragraph.y
                    : midpoint(lines.get(index - 1).bottom(), line.top());
            int bottom = index == lines.size() - 1 ? paragraph.y + paragraph.height
                    : midpoint(line.bottom(), lines.get(index + 1).top());
            result.add(child(region, frame, ".l" + (index + 1), line.words(),
                    contentLeft, top, paragraph.x + paragraph.width, bottom, true));
        }
        return List.copyOf(result);
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

    static List<VisualLine> visualLines(List<OcrWord> words, LocalFrame frame) {
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
        return child(source, frame, suffix, boxes, left, top, right, bottom, compact, null, List.of());
    }

    private static OcrRegion child(OcrRegion source, LocalFrame frame, String suffix, List<WordBox> boxes,
            int left, int top, int right, int bottom, boolean compact, WritingMode mode,
            List<List<OcrPoint>> flowCells) {
        List<OcrWord> words = boxes.stream().map(WordBox::word).toList();
        boolean allWordConfidenceKnown = words.stream().allMatch(OcrWord::confidenceKnown);
        boolean confidenceKnown = allWordConfidenceKnown || source.confidenceKnown();
        float confidence = allWordConfidenceKnown
                ? words.stream().map(OcrWord::confidence).min(Float::compare).orElse(source.confidence())
                : source.confidence();
        return new OcrRegion(
                source.id() + suffix,
                joinWords(words, mode),
                frame.polygon(left, top, Math.max(left + 1, right), Math.max(top + 1, bottom)),
                words,
                confidence,
                confidenceKnown,
                source.blockType(),
                source.languages(),
                0,
                source.groupId(),
                compact,
                mode,
                flowCells);
    }

    /**
     * The words in order, with the script rule between them. Letters stacked one to a word in a
     * column of left-to-right vertical writing are one word, so "T", "E", "A" read "TEA".
     */
    private static String joinWords(List<OcrWord> words, WritingMode mode) {
        StringBuilder result = new StringBuilder();
        OcrWord previous = null;
        for (OcrWord word : words) {
            boolean stackedLetters = mode == WritingMode.VERTICAL_LR && previous != null
                    && isSingleLatinLetter(previous.text()) && isSingleLatinLetter(word.text());
            if (!stackedLetters) result.append(OcrWordJoiner.scriptSeparator(result, word.text()));
            result.append(word.text());
            previous = word;
        }
        return result.toString();
    }

    private static boolean isSingleLatinLetter(String value) {
        return value.codePointCount(0, value.length()) == 1
                && Character.isLetter(value.codePointAt(0))
                && Character.UnicodeScript.of(value.codePointAt(0)) == Character.UnicodeScript.LATIN;
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

    static int median(List<Integer> values) {
        List<Integer> sorted = values.stream().filter(value -> value > 0).sorted().toList();
        return sorted.isEmpty() ? 1 : sorted.get(sorted.size() / 2);
    }

    private static int midpoint(int left, int right) {
        return left + (right - left) / 2;
    }

    /** Maps OCR polygons to the paragraph's own reading axes before row/column analysis. */
    record LocalFrame(
            double originX, double originY,
            double xAxisX, double xAxisY,
            double yAxisX, double yAxisY,
            double determinant, int width, int height) {
        static LocalFrame from(OcrRegion region) {
            if (region == null || region.polygon().size() < 4) return null;
            // Upright vertical writing is analysed in the frame of the image, whatever corner the
            // provider started its polygon at, so its columns are columns and not lines.
            List<OcrPoint> polygon = region.writingMode().vertical()
                    ? region.uprightPolygon() : region.polygon();
            OcrPoint origin = polygon.get(0);
            OcrPoint right = polygon.get(1);
            OcrPoint bottom = polygon.get(polygon.size() - 1);
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

        Rectangle bounds() {
            return new Rectangle(0, 0, width, height);
        }

        Rectangle bounds(List<OcrPoint> points) {
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

        List<OcrPoint> polygon(int left, int top, int right, int bottom) {
            return List.of(point(left, top), point(right, top), point(right, bottom), point(left, bottom));
        }

        private OcrPoint point(double x, double y) {
            return new OcrPoint(
                    (int) Math.round(originX + xAxisX * x + yAxisX * y),
                    (int) Math.round(originY + xAxisY * x + yAxisY * y));
        }
    }

    record WordBox(OcrWord word, Rectangle bounds) {
        int left() { return bounds.x; }
        int right() { return bounds.x + bounds.width; }
        int top() { return bounds.y; }
        int bottom() { return bounds.y + bounds.height; }
        int height() { return bounds.height; }
        int centerY() { return bounds.y + bounds.height / 2; }
        int centerX() { return bounds.x + bounds.width / 2; }
    }

    record VisualLine(List<WordBox> words, Rectangle bounds) {
        int top() { return bounds.y; }
        int bottom() { return bounds.y + bounds.height; }
    }

    private record WordSegment(List<WordBox> boxes) {
        List<WordBox> words() { return boxes; }
        int left() { return boxes.stream().mapToInt(WordBox::left).min().orElse(0); }
        int right() { return boxes.stream().mapToInt(WordBox::right).max().orElse(left() + 1); }
        int top() { return boxes.stream().mapToInt(WordBox::top).min().orElse(0); }
        int bottom() { return boxes.stream().mapToInt(WordBox::bottom).max().orElse(top() + 1); }
    }

    private record VisualColumn(List<WordBox> words, Rectangle bounds) {
        int left() { return bounds.x; }
        int right() { return bounds.x + bounds.width; }
    }

    private static final class MutableColumn {
        private final List<WordBox> words = new ArrayList<>();
        private final int anchorX;
        private Rectangle bounds;

        private MutableColumn(WordBox first) {
            this.anchorX = first.centerX();
            add(first);
        }

        private void add(WordBox word) {
            words.add(word);
            bounds = bounds == null ? new Rectangle(word.bounds()) : bounds.union(word.bounds());
        }

        private int anchorX() { return anchorX; }
        private VisualColumn freeze() {
            return new VisualColumn(List.copyOf(words), new Rectangle(bounds));
        }
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
