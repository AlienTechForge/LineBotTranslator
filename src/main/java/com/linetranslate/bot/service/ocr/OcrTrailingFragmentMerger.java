package com.linetranslate.bot.service.ocr;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.linetranslate.bot.service.ocr.OcrRegionSegmenter.LocalFrame;
import com.linetranslate.bot.service.ocr.OcrRegionSegmenter.VisualLine;
import com.linetranslate.bot.service.ocr.OcrRegionSegmenter.WordBox;

/**
 * Folds the short tail of a wrapped sentence back into the paragraph it was cut from.
 *
 * <p>Vision sometimes reports the last line of a paragraph, a couple of characters that wrapped
 * onto a line of their own, as a block of its own. Qualification judges a region by itself, and two
 * characters carry too little evidence to be trusted, so the tail is dropped: the translation
 * replaces the paragraph and the orphan stays on the image in the source language. The fix has to
 * come before qualification, from geometry, because qualification only sees one region at a time.
 *
 * <p>All measurements are taken in the host paragraph's own reading frame, so a slightly rotated
 * page is judged the same way as an upright one, and a fragment is located by the points of its
 * words and polygon rather than by the order of its polygon's vertices. A fragment is a wrap, not
 * a new block, only when every one of these holds:
 * <ul>
 *   <li>It is short, written in CJK (or is closing punctuation alone) and ends a sentence, so a
 *       label, a heading without a full stop and a list item never qualify.</li>
 *   <li>The host is a horizontal paragraph of at least two lines whose last line runs to the
 *       right edge of the block, give or take the width of the fragment: the fragment did not fit
 *       there, which is why it wrapped.</li>
 *   <li>It sits directly below the host's last line, within {@value #MAX_GAP_LINES} line heights
 *       (the larger of the glyph height and the line pitch), on the same left edge within
 *       {@value #MAX_LEFT_OFFSET_GLYPHS} of a glyph, inside the host's column.</li>
 *   <li>It is one line of the same type size, within {@value #MAX_GLYPH_HEIGHT_DIFFERENCE} of the
 *       host's glyph height.</li>
 *   <li>No other region sits between the host's last line and the fragment in the host's column,
 *       so a heading or caption in between is never skipped over.</li>
 *   <li>Its confidence is known at every level qualification reads, down to the symbols, so
 *       folding it in cannot make the whole paragraph untrusted.</li>
 * </ul>
 * The host is found by geometry among all regions, not by list position, because Vision does not
 * promise to list the tail next to its paragraph.
 *
 * <p>Every candidate gets one log line with the verdict and the measured ratios. Counts and
 * ratios only; no source text reaches the log.
 */
final class OcrTrailingFragmentMerger {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OcrRegionSegmenter.class);

    /** A shorter host cannot be told from a label, so nothing is folded into it. */
    static final int MIN_HOST_CODEPOINTS = 20;
    static final int MAX_FRAGMENT_CODEPOINTS = 4;
    /** Largest gap below the host's last line, in line heights. */
    static final double MAX_GAP_LINES = 1.5;
    /** Largest overlap with the host's last line, in glyph heights; Vision boxes touch. */
    static final double MAX_OVERLAP_GLYPHS = .25;
    /** Largest distance between the fragment's left edge and the host's, in glyph heights. */
    static final double MAX_LEFT_OFFSET_GLYPHS = .5;
    /** Largest relative difference between the fragment's glyph height and the host's. */
    static final double MAX_GLYPH_HEIGHT_DIFFERENCE = .25;
    /** A fragment is one line: its height may not exceed this many of its own glyph heights. */
    static final double MAX_FRAGMENT_LINES = 1.6;
    /** Hosts farther away than this many glyph heights are not worth reporting on. */
    private static final int REPORT_REACH_GLYPHS = 4;

    private static final String LIST_MARKERS = "•‣◦∙·・●○◎■□▪▫◆◇▶▷►▸▹※*＊-–—−‐+➢➤✓✔";
    private static final String SENTENCE_ENDINGS = "。．｡.!?！？…";

    private OcrTrailingFragmentMerger() {
    }

    static List<OcrRegion> merge(List<OcrRegion> input) {
        int count = input.size();
        boolean[] absorbed = new boolean[count];
        OcrRegion[] replacement = new OcrRegion[count];
        for (int index = 0; index < count; index++) {
            OcrRegion fragment = input.get(index);
            if (absorbed[index] || replacement[index] != null || !looksLikeFragment(fragment)) continue;
            Measure best = null;
            for (int other = 0; other < count; other++) {
                if (other == index || absorbed[other] || replacement[other] != null) continue;
                Measure candidate = assess(input, other, index);
                if (candidate != null && (best == null || candidate.betterThan(best))) best = candidate;
            }
            if (best == null) continue;
            report(fragment, best);
            if (best.merge) {
                replacement[best.hostIndex] = absorb(input.get(best.hostIndex), fragment);
                absorbed[index] = true;
            }
        }
        List<OcrRegion> merged = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (absorbed[index]) continue;
            merged.add(replacement[index] != null ? replacement[index] : input.get(index));
        }
        return List.copyOf(merged);
    }

    // --- what a fragment looks like by itself -------------------------------------------------

    private static boolean looksLikeFragment(OcrRegion region) {
        if (region == null || region.compactLabel() || region.text().isBlank()
                || region.polygon().size() < 4 || !region.validGeometry()) {
            return false;
        }
        String text = region.text();
        if (text.codePointCount(0, text.length()) > MAX_FRAGMENT_CODEPOINTS) return false;
        boolean cjk = text.codePoints().anyMatch(OcrWordJoiner::isCjk);
        boolean punctuationOnly = text.codePoints().allMatch(OcrTrailingFragmentMerger::isClosing);
        return (cjk || punctuationOnly) && endsSentence(text) && !startsWithListMarker(text);
    }

    private static boolean endsSentence(String text) {
        int end = text.length();
        while (end > 0 && isCloser(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return end > 0 && isSentenceEnding(text.codePointBefore(end));
    }

    private static boolean isSentenceEnding(int codePoint) {
        return SENTENCE_ENDINGS.indexOf(codePoint) >= 0;
    }

    /** Brackets and quotes that close a sentence after its full stop. */
    private static boolean isCloser(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.END_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || codePoint == '"' || codePoint == '\'';
    }

    private static boolean isClosing(int codePoint) {
        return isSentenceEnding(codePoint) || isCloser(codePoint);
    }

    /** A bullet or a number opens an item of its own, so what follows it is not a wrapped tail. */
    private static boolean startsWithListMarker(String text) {
        int first = text.codePointAt(0);
        int type = Character.getType(first);
        return LIST_MARKERS.indexOf(first) >= 0 || Character.isDigit(first)
                || type == Character.OTHER_NUMBER || type == Character.LETTER_NUMBER;
    }

    // --- whether a particular paragraph is the one the fragment was cut from ------------------

    /**
     * Measures the fragment against one candidate host. Null when the region is not a candidate at
     * all (not above the fragment, or in another column), so unrelated pairs are neither reported
     * nor considered.
     */
    private static Measure assess(List<OcrRegion> regions, int hostIndex, int fragmentIndex) {
        OcrRegion host = regions.get(hostIndex);
        OcrRegion fragment = regions.get(fragmentIndex);
        if (host == null || host.polygon().size() < 4) return null;
        Rectangle hostBox = OverlaySafetyPolicy.polygon(host.polygon()).getBounds();
        Rectangle fragmentBox = OverlaySafetyPolicy.polygon(fragment.polygon()).getBounds();
        int distance = fragmentBox.y - (hostBox.y + hostBox.height);
        int reach = REPORT_REACH_GLYPHS * Math.max(fragmentBox.height, typicalWordHeight(host, hostBox));
        boolean below = fragmentBox.y + fragmentBox.height / 2 > hostBox.y + hostBox.height / 2;
        boolean sameColumn = fragmentBox.x < hostBox.x + hostBox.width
                && hostBox.x < fragmentBox.x + fragmentBox.width;
        if (!below || !sameColumn || distance > reach || distance < -fragmentBox.height) return null;

        Measure measure = new Measure(hostIndex, distance, host, hostBox);
        if (host.compactLabel()) return measure.keep("kept-compact-label");
        if (host.orientation() != OcrOrientation.HORIZONTAL) {
            return measure.keep("kept-host-not-horizontal");
        }
        if (host.text().codePointCount(0, host.text().length()) < MIN_HOST_CODEPOINTS) {
            return measure.keep("kept-host-too-short");
        }
        LocalFrame frame = LocalFrame.from(host);
        if (frame == null || host.words().isEmpty()
                || host.words().stream().anyMatch(word -> word.polygon().size() < 4)) {
            return measure.keep("kept-host-geometry-unusable");
        }
        List<VisualLine> lines = OcrRegionSegmenter.visualLines(host.words(), frame);
        measure.hostLines = lines.size();
        if (lines.size() < 2) return measure.keep("kept-host-single-line");
        if (languagesConflict(host, fragment)) return measure.keep("kept-language-conflict");
        if (fragment.text().codePoints().anyMatch(OcrWordJoiner::isCjk)
                && host.text().codePoints().noneMatch(OcrWordJoiner::isCjk)) {
            return measure.keep("kept-script-mismatch");
        }
        if (!host.confidenceKnown() || hasUnknownConfidence(fragment)) {
            // An unknown confidence cannot be checked against the threshold, and folding it into
            // the host would make the whole paragraph untrusted.
            return measure.keep("kept-confidence-unknown");
        }
        if (hasSymbols(fragment) && !hasSymbols(host)) {
            // Qualification averages symbol confidence over the whole region, so a host without
            // symbols would be judged by the fragment's two alone.
            return measure.keep("kept-symbols-unmatched");
        }

        VisualLine last = lines.get(lines.size() - 1);
        int glyph = OcrRegionSegmenter.median(last.words().stream().map(WordBox::height).toList());
        int pitch = OcrRegionSegmenter.median(java.util.stream.IntStream.range(1, lines.size())
                .map(line -> lines.get(line).top() - lines.get(line - 1).top())
                .boxed().toList());
        int lineHeight = Math.max(glyph, pitch);
        int contentLeft = lines.stream().mapToInt(line -> line.bounds().x).min().orElse(0);
        int contentRight = lines.stream()
                .mapToInt(line -> line.bounds().x + line.bounds().width).max().orElse(0);
        int lastLeft = last.bounds().x;
        int lastRight = last.bounds().x + last.bounds().width;

        Rectangle ink = frame.bounds(inkPoints(fragment));
        boolean hasLetters = fragment.text().codePoints().anyMatch(Character::isLetter);
        int fragmentGlyph = hasLetters ? fragmentGlyphHeight(fragment, frame, ink) : glyph;
        double gap = ink.y - last.bottom();
        double leftOffset = Math.min(Math.abs(ink.x - contentLeft), Math.abs(ink.x - lastLeft));
        double shortfall = contentRight - lastRight;
        measure.lineHeight = lineHeight;
        measure.gapRatio = gap / lineHeight;
        measure.leftOffsetRatio = leftOffset / glyph;
        measure.heightRatio = hasLetters ? (double) fragmentGlyph / glyph : Double.NaN;
        measure.shortfallRatio = shortfall / glyph;

        if (gap > MAX_GAP_LINES * lineHeight || gap < -MAX_OVERLAP_GLYPHS * glyph) {
            return measure.keep("kept-gap-out-of-range");
        }
        if (leftOffset > MAX_LEFT_OFFSET_GLYPHS * glyph) return measure.keep("kept-left-edge-misaligned");
        if (hasLetters && Math.abs(measure.heightRatio - 1) > MAX_GLYPH_HEIGHT_DIFFERENCE) {
            return measure.keep("kept-glyph-height-differs");
        }
        if (ink.height > MAX_FRAGMENT_LINES * fragmentGlyph) {
            return measure.keep("kept-fragment-not-single-line");
        }
        if (ink.x + ink.width > contentRight + MAX_LEFT_OFFSET_GLYPHS * glyph) {
            return measure.keep("kept-outside-column");
        }
        // The fragment wrapped because it did not fit after the last line, so the room left there
        // is smaller than the fragment, plus a glyph for punctuation that hangs or is pushed out.
        if (shortfall > Math.max(2.0 * glyph, ink.width + glyph)) return measure.keep("kept-last-line-short");
        // Something else sits between the last line and the fragment, so the fragment is the line
        // after that block and not the one after the paragraph, however small the gaps are.
        if (blockBetween(regions, hostIndex, fragmentIndex, frame, last.bottom(), ink.y,
                Math.min(contentLeft, ink.x), Math.max(contentRight, ink.x + ink.width))) {
            return measure.keep("kept-block-between");
        }
        return measure.accept();
    }

    /**
     * Whether any other region lies between the host's last line and the fragment: its vertical
     * centre falls in the gap and it overlaps the column, which is the host's text widened to the
     * fragment. A block off to the side, in another column, is not in the way.
     */
    private static boolean blockBetween(List<OcrRegion> regions, int hostIndex, int fragmentIndex,
            LocalFrame frame, int gapTop, int gapBottom, int columnLeft, int columnRight) {
        for (int index = 0; index < regions.size(); index++) {
            OcrRegion other = regions.get(index);
            if (index == hostIndex || index == fragmentIndex || other == null
                    || other.polygon().size() < 4) {
                continue;
            }
            Rectangle box = frame.bounds(other.polygon());
            int centre = box.y + box.height / 2;
            if (centre > gapTop && centre < gapBottom
                    && box.x < columnRight && columnLeft < box.x + box.width) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUnknownConfidence(OcrRegion region) {
        return !region.confidenceKnown()
                || region.words().stream().anyMatch(word -> !word.confidenceKnown()
                        || word.symbols().stream().anyMatch(symbol -> !symbol.confidenceKnown()));
    }

    private static boolean hasSymbols(OcrRegion region) {
        return region.words().stream().anyMatch(word -> !word.symbols().isEmpty());
    }

    private static int typicalWordHeight(OcrRegion region, Rectangle fallback) {
        List<Integer> heights = region.words().stream()
                .filter(word -> word.polygon().size() >= 4)
                .map(word -> OverlaySafetyPolicy.polygon(word.polygon()).getBounds().height)
                .toList();
        return heights.isEmpty() ? fallback.height : OcrRegionSegmenter.median(heights);
    }

    /**
     * The points that locate the fragment's ink: its words when they all have a box, otherwise its
     * polygon. Taken as a set, so the order the provider listed the vertices in does not matter.
     */
    private static List<OcrPoint> inkPoints(OcrRegion fragment) {
        if (!hasUsableWords(fragment)) return fragment.polygon();
        List<OcrPoint> points = new ArrayList<>();
        fragment.words().forEach(word -> points.addAll(word.polygon()));
        return points;
    }

    private static boolean hasUsableWords(OcrRegion region) {
        return !region.words().isEmpty()
                && region.words().stream().allMatch(word -> word.polygon().size() >= 4);
    }

    private static int fragmentGlyphHeight(OcrRegion fragment, LocalFrame frame, Rectangle ink) {
        if (!hasUsableWords(fragment)) return Math.max(1, ink.height);
        return OcrRegionSegmenter.median(fragment.words().stream()
                .map(word -> frame.bounds(word.polygon()).height).toList());
    }

    /**
     * Whether the two regions are plainly in different languages. Chinese and Japanese share Han,
     * and Vision reports a kanji-heavy Japanese paragraph as either, so a disagreement between the
     * two says nothing about whether a tail belongs to its paragraph.
     */
    private static boolean languagesConflict(OcrRegion host, OcrRegion fragment) {
        String left = languageFamily(host);
        String right = languageFamily(fragment);
        if (left == null || right == null || left.equals(right)) return false;
        return !(isHanLanguage(left) && isHanLanguage(right));
    }

    private static String languageFamily(OcrRegion region) {
        if (region.languages().isEmpty()) return null;
        String code = region.languages().get(0).code().toLowerCase(Locale.ROOT);
        int subtag = code.indexOf('-');
        String family = subtag < 0 ? code : code.substring(0, subtag);
        return family.equals("und") ? null : family;
    }

    private static boolean isHanLanguage(String family) {
        return family.equals("ja") || family.equals("zh");
    }

    // --- the merged region --------------------------------------------------------------------

    /**
     * The host with the fragment folded in: text appended in reading order, words and masks
     * covering the fragment's glyphs so the source line is erased, and a polygon that spans both
     * in the host's own frame, so a rotated paragraph stays rotated.
     */
    private static OcrRegion absorb(OcrRegion host, OcrRegion fragment) {
        LocalFrame frame = LocalFrame.from(host);
        Rectangle area = frame.bounds()
                .union(frame.bounds(fragment.polygon()))
                .union(frame.bounds(inkPoints(fragment)));
        List<OcrWord> words = new ArrayList<>(host.words());
        if (hasUsableWords(fragment)) {
            words.addAll(fragment.words());
        } else {
            // Without a box of its own the fragment would not be in the cleanup mask.
            words.add(new OcrWord(fragment.text(), fragment.polygon(), fragment.confidence(), true));
        }
        int hostChars = host.text().codePointCount(0, host.text().length());
        int fragmentChars = fragment.text().codePointCount(0, fragment.text().length());
        // Weighted by length, like a paragraph score over its glyphs: two characters must not be
        // able to push a long paragraph under the confidence threshold on their own.
        float confidence = (host.confidence() * hostChars + fragment.confidence() * fragmentChars)
                / (hostChars + fragmentChars);
        return new OcrRegion(host.id(), joinText(host.text(), fragment.text()),
                frame.polygon(area.x, area.y, area.x + area.width, area.y + area.height),
                words, confidence, true, host.blockType(), host.languages(), host.readingOrder(),
                host.groupId(), false);
    }

    private static String joinText(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) return left + right;
        // Closing punctuation attaches to the text it follows, in any script. Between two words
        // the joiner decides, so Hangul keeps its space while Han and Kana join directly.
        if (isClosing(right.codePointAt(0))) return left + right;
        return left + OcrWordJoiner.scriptSeparator(left, right) + right;
    }

    // --- reporting ----------------------------------------------------------------------------

    private static void report(OcrRegion fragment, Measure measure) {
        String source = fragment.text();
        int fragmentChars = source.codePointCount(0, source.length());
        log.info("Trailing fragment merge decision: verdict={}, hostWords={}, hostLines={}, "
                        + "fragmentWords={}, fragmentChars={}, gapRatio={}, leftOffsetRatio={}, "
                        + "heightRatio={}, shortfallRatio={}, lineHeight={}, "
                        + "hostWidth={}, hostHeight={}",
                measure.verdict, measure.hostWords, measure.hostLines, fragment.words().size(),
                fragmentChars, ratio(measure.gapRatio),
                ratio(measure.leftOffsetRatio), ratio(measure.heightRatio),
                ratio(measure.shortfallRatio), measure.lineHeight,
                measure.hostBox.width, measure.hostBox.height);
    }

    private static String ratio(double value) {
        return Double.isNaN(value) ? "n/a" : String.format(Locale.ROOT, "%.2f", value);
    }

    /** What was measured between the fragment and one candidate host, and the decision. */
    private static final class Measure {
        final int hostIndex;
        final int distance;
        final int hostWords;
        final Rectangle hostBox;
        int hostLines;
        int lineHeight;
        double gapRatio = Double.NaN;
        double leftOffsetRatio = Double.NaN;
        double heightRatio = Double.NaN;
        double shortfallRatio = Double.NaN;
        String verdict;
        boolean merge;

        Measure(int hostIndex, int distance, OcrRegion host, Rectangle hostBox) {
            this.hostIndex = hostIndex;
            this.distance = distance;
            this.hostWords = host.words().size();
            this.hostBox = hostBox;
        }

        Measure keep(String reason) {
            verdict = reason;
            merge = false;
            return this;
        }

        Measure accept() {
            verdict = "merged";
            merge = true;
            return this;
        }

        /** A host that takes the fragment beats one that does not; then the nearer one wins. */
        boolean betterThan(Measure other) {
            if (merge != other.merge) return merge;
            return Math.abs(distance) < Math.abs(other.distance);
        }
    }
}
