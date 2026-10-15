package com.linetranslate.bot.service.ocr;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import com.linetranslate.bot.service.ocr.OcrRegionSegmenter.LocalFrame;
import com.linetranslate.bot.service.ocr.PageOrientation.Verdict;

/**
 * Tells a page that was photographed or scanned a quarter turn off from an upright one, from the
 * provider's paragraphs alone: geometry and script, no pixels and no second OCR call.
 *
 * <p>Vision reports every box in the reading frame of its text: the edge from vertex 0 to vertex 1
 * is the direction the text reads in, whichever way the page is turned, so the quarter that edge
 * points at is the quarter the text is turned by. The frame alone cannot separate a page turned 90
 * degrees from upright vertical writing, whose columns have the same frame, so the verdict is
 * drawn in tiers of evidence and anything undecided is {@link Verdict#AMBIGUOUS}, which causes no
 * action:
 * <ol>
 *   <li>A quarter of 180 or 270 degrees is always a rotated page: the top of a vertical column is
 *       its right side, so vertical writing is never presented at those angles.</li>
 *   <li>At 90 degrees, text that is not mostly CJK is a rotated page: upright vertical writing at
 *       paragraph scale exists only for CJK.</li>
 *   <li>The frames of the symbols themselves, only while {@link #SYMBOL_FRAME_FOLLOWS_GLYPH} says
 *       the provider is known to give an upright glyph an upright frame. Unverified, so off.</li>
 *   <li>Where punctuation and small kana sit inside their line: low in a horizontal line, high in
 *       a vertical one.</li>
 * </ol>
 *
 * <p>Conservative by construction: the page is judged on the reliable text only, text weight
 * decides rather than a count of regions, regions off every quarter count against dominance, and a
 * single label never turns a page. Every threshold is a constant here so the tests pin it.
 */
final class PageOrientationDetector {

    /** How far a region's line may be from a quarter turn and still vote for that quarter. */
    static final double QUARTER_TOLERANCE_DEG = 10;
    /** Share of the reliable text weight that must share one quarter for the page to have one. */
    static final double MIN_DOMINANT_SHARE = .80;
    /** A region this confident is reliable; the bar {@link OverlayContentClassifier} uses. */
    static final float RELIABLE_CONFIDENCE = .80f;
    /** Less reliable weight (letters and digits) than this is too little to judge a page by. */
    static final int MIN_RELIABLE_WEIGHT = 20;
    /** The leading quarter needs this many voting regions, or one of at least the weight below. */
    static final int MIN_VOTING_REGIONS = 2;
    static final int SINGLE_REGION_WEIGHT = 40;
    /** At 90 degrees, text with at most this share of CJK among its scripted letters is rotated. */
    static final double MAX_ROTATED_CJK_SHARE = .50;
    /**
     * Whether a symbol's own box follows the glyph, so that an upright glyph in a vertical column
     * has an upright box while its line is turned. Not verified against real provider output yet
     * (the opt-in {@code OcrGeometryDumpTests} measures it), so it ships off and tier 3 does not act.
     */
    static final boolean SYMBOL_FRAME_FOLLOWS_GLYPH = false;
    static final double SYMBOL_TOLERANCE_DEG = 20;
    static final int MIN_SYMBOL_SAMPLES = 8;
    static final double MIN_GLYPH_SHARE = .60;
    static final int MIN_PUNCTUATION_SAMPLES = 3;
    /** A single paragraph of a mixed page is judged on fewer samples than a whole page. */
    static final int MIN_PARAGRAPH_SYMBOL_SAMPLES = 5;
    static final int MIN_PARAGRAPH_PUNCTUATION_SAMPLES = 2;
    /** At or below this the punctuation sits high in its line, at or above the next one low in it. */
    static final double HIGH_PUNCTUATION_OFFSET = .40;
    static final double LOW_PUNCTUATION_OFFSET = .60;
    /** A rotated page is worth reading again when less than this share of its text was reliable. */
    static final double RETRY_RELIABLE_SHARE = .80;
    /** Total weight a page needs for its quarter to count when too little of it was reliable. */
    static final int RELAXED_TOTAL_WEIGHT = 40;
    /** What horizontal CJK typesetting puts low in the em box and vertical typesetting puts high. */
    private static final String PUNCTUATION_AND_SMALL_KANA =
            "、。，．︑︒︐ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ";

    private PageOrientationDetector() {
    }

    static PageOrientation detect(List<OcrRegion> regions) {
        return detect(regions, SYMBOL_FRAME_FOLLOWS_GLYPH);
    }

    static PageOrientation detect(List<OcrRegion> regions, boolean symbolFrameFollowsGlyph) {
        List<OcrRegion> all = regions == null ? List.of() : regions;
        List<Vote> votes = all.stream().map(PageOrientationDetector::vote).toList();
        List<Vote> reliable = votes.stream().filter(Vote::reliable).toList();
        int totalWeight = votes.stream().mapToInt(Vote::weight).sum();
        int reliableWeight = reliable.stream().mapToInt(Vote::weight).sum();
        double reliableShare = totalWeight == 0 ? -1 : (double) reliableWeight / totalWeight;
        Page page = new Page(all.size(), reliable.size(), reliableShare);

        Tally tally = Tally.of(reliable);
        boolean relaxed = false;
        if (reliableWeight < MIN_RELIABLE_WEIGHT) {
            // Too little reliable text to judge by. Text that Vision read poorly still shows which
            // way the page runs, so when nearly all of it runs one way the page is judged on all of it.
            Tally everything = Tally.of(votes);
            if (totalWeight < RELAXED_TOTAL_WEIGHT || !everything.leads()
                    || everything.leadingQuarter() == 0
                    || everything.dominantShare() < MIN_DOMINANT_SHARE) {
                return page.verdict(Verdict.INSUFFICIENT, tally, Evidence.none(), false);
            }
            tally = everything;
            relaxed = true;
        } else if (!tally.leads()) {
            return page.verdict(Verdict.INSUFFICIENT, tally, Evidence.none(), false);
        }

        int quarter = tally.leadingQuarter();
        List<Vote> leading = tally.votes().stream().filter(vote -> vote.quarter() == quarter).toList();
        Evidence evidence = Evidence.of(leading, quarter == 1);
        if (tally.dominantShare() < MIN_DOMINANT_SHARE) {
            return page.verdict(Verdict.MIXED, tally, evidence, false);
        }
        if (quarter == 0) {
            return page.verdict(Verdict.UPRIGHT, tally, evidence, false);
        }
        Verdict verdict = quarter == 1 ? turnedQuarter(evidence, symbolFrameFollowsGlyph) : Verdict.ROTATED;
        boolean retry = verdict == Verdict.ROTATED && (relaxed || reliableShare < RETRY_RELIABLE_SHARE);
        return page.verdict(verdict, tally, evidence, retry);
    }

    /** The tiers for a page whose text runs down the image: rotated page or vertical writing. */
    private static Verdict turnedQuarter(Evidence evidence, boolean symbolFrameFollowsGlyph) {
        if (evidence.cjkShare() <= MAX_ROTATED_CJK_SHARE) return Verdict.ROTATED;
        if (symbolFrameFollowsGlyph && evidence.symbolSamples() >= MIN_SYMBOL_SAMPLES) {
            if (evidence.uprightGlyphShare() >= MIN_GLYPH_SHARE) return Verdict.VERTICAL_WRITING;
            if (evidence.rotatedGlyphShare() >= MIN_GLYPH_SHARE) return Verdict.ROTATED;
        }
        if (evidence.punctuationSamples() >= MIN_PUNCTUATION_SAMPLES) {
            if (evidence.punctuationOffset() >= LOW_PUNCTUATION_OFFSET) return Verdict.ROTATED;
            if (evidence.punctuationOffset() <= HIGH_PUNCTUATION_OFFSET) return Verdict.VERTICAL_WRITING;
        }
        return Verdict.AMBIGUOUS;
    }

    /**
     * Marks the regions of a page that was found to be upright vertical writing, with no change to
     * their geometry: a page whose verdict is {@link Verdict#VERTICAL_WRITING} gives every region
     * whose line edge points down the image ({@code VERTICAL_RL}), and a page whose text has no
     * dominant quarter ({@link Verdict#MIXED}) gives that mode only to the paragraphs that carry
     * the evidence themselves, from their own symbols and punctuation and with fewer samples than a
     * page needs. A region that is mostly not CJK is left alone either way: vertical writing at
     * this scale is Chinese or Japanese, and any other text running down the image is turned.
     * Every other verdict, and every other region, comes back as it is.
     */
    static List<OcrRegion> markVerticalWriting(
            List<OcrRegion> regions, PageOrientation orientation, boolean symbolFrameFollowsGlyph) {
        Verdict verdict = orientation.verdict();
        if (verdict != Verdict.VERTICAL_WRITING && verdict != Verdict.MIXED) return regions;
        List<OcrRegion> result = new ArrayList<>(regions.size());
        for (OcrRegion region : regions) {
            Vote vote = vote(region);
            boolean mark = vote.quarter() == 1 && !region.writingMode().vertical()
                    && runsAsVerticalWriting(vote, verdict, symbolFrameFollowsGlyph);
            result.add(mark
                    ? region.withGeometry(region.polygon(), region.words(), WritingMode.VERTICAL_RL)
                    : region);
        }
        return List.copyOf(result);
    }

    private static boolean runsAsVerticalWriting(Vote vote, Verdict verdict, boolean symbolFrameFollowsGlyph) {
        Evidence evidence = Evidence.of(List.of(vote), true);
        if (evidence.cjkShare() <= MAX_ROTATED_CJK_SHARE) return false;
        if (verdict == Verdict.VERTICAL_WRITING) return true;
        if (!vote.reliable()) return false;
        if (symbolFrameFollowsGlyph && evidence.symbolSamples() >= MIN_PARAGRAPH_SYMBOL_SAMPLES
                && evidence.uprightGlyphShare() >= MIN_GLYPH_SHARE) {
            return true;
        }
        return evidence.punctuationSamples() >= MIN_PARAGRAPH_PUNCTUATION_SAMPLES
                && evidence.punctuationOffset() <= HIGH_PUNCTUATION_OFFSET;
    }

    /**
     * The reliable text weight of {@code regions}: the letters and digits of the regions at or above
     * the reliability bar. Two readings of one page compare by this.
     */
    static int reliableWeight(List<OcrRegion> regions) {
        return regions == null ? 0 : regions.stream().map(PageOrientationDetector::vote)
                .filter(Vote::reliable).mapToInt(Vote::weight).sum();
    }

    // --- what the page looks like as a whole ---------------------------------------------------

    private record Page(int regions, int reliableRegions, double reliableShare) {

        PageOrientation verdict(Verdict verdict, Tally tally, Evidence evidence, boolean retryAdvised) {
            boolean weighed = tally.weight() > 0;
            return new PageOrientation(verdict, tally.hasLeader() ? tally.leadingQuarter() : -1,
                    regions, reliableRegions,
                    weighed ? tally.dominantShare() : -1,
                    weighed ? (double) tally.skewedWeight() / tally.weight() : -1,
                    reliableShare, evidence.cjkShare(), evidence.symbolSamples(),
                    evidence.uprightGlyphShare(), evidence.rotatedGlyphShare(),
                    evidence.punctuationSamples(), evidence.punctuationOffset(), retryAdvised);
        }
    }

    // --- votes -------------------------------------------------------------------------------

    /**
     * One region's say: its text weight, and the quarter its line edge points at, or -1 when it is
     * off every quarter by more than the tolerance (or has no edge) and counts against dominance only.
     */
    private record Vote(OcrRegion region, int weight, int quarter, boolean reliable) {
    }

    private static Vote vote(OcrRegion region) {
        int weight = weight(region.text());
        boolean reliable = region.confidenceKnown() && region.confidence() >= RELIABLE_CONFIDENCE;
        int quarter = -1;
        if (region.polygon().size() >= 2) {
            double degrees = region.rotationDegrees();
            long nearest = Math.round(degrees / 90);
            if (Math.abs(degrees - 90 * nearest) <= QUARTER_TOLERANCE_DEG) {
                quarter = (int) Math.floorMod(nearest, 4L);
            }
        }
        return new Vote(region, weight, quarter, reliable);
    }

    /** Letters and digits; punctuation and spaces say nothing about which way a page runs. */
    private static int weight(String text) {
        return (int) text.codePoints().filter(Character::isLetterOrDigit).count();
    }

    /** The text weight each quarter holds, over one set of votes. */
    private static final class Tally {
        private final List<Vote> votes;
        private final int[] weights = new int[4];
        private final int[] voters = new int[4];
        private final int[] heaviest = new int[4];
        private int skewedWeight;
        private int weight;
        private int leadingQuarter;

        private Tally(List<Vote> votes) {
            this.votes = votes;
        }

        static Tally of(List<Vote> votes) {
            Tally tally = new Tally(votes);
            for (Vote vote : votes) {
                tally.weight += vote.weight();
                if (vote.quarter() < 0) {
                    tally.skewedWeight += vote.weight();
                    continue;
                }
                tally.weights[vote.quarter()] += vote.weight();
                if (vote.weight() > 0) tally.voters[vote.quarter()]++;
                tally.heaviest[vote.quarter()] = Math.max(tally.heaviest[vote.quarter()], vote.weight());
            }
            // A tie goes to the lowest quarter, so a page split evenly is never turned.
            for (int quarter = 1; quarter < 4; quarter++) {
                if (tally.weights[quarter] > tally.weights[tally.leadingQuarter]) tally.leadingQuarter = quarter;
            }
            return tally;
        }

        List<Vote> votes() {
            return votes;
        }

        int weight() {
            return weight;
        }

        int skewedWeight() {
            return skewedWeight;
        }

        int leadingQuarter() {
            return leadingQuarter;
        }

        boolean hasLeader() {
            return weights[leadingQuarter] > 0;
        }

        /** The leader has several voting regions, or one heavy enough to stand for a page. */
        boolean leads() {
            return hasLeader()
                    && (voters[leadingQuarter] >= MIN_VOTING_REGIONS
                            || heaviest[leadingQuarter] >= SINGLE_REGION_WEIGHT);
        }

        /** Weight of the leading quarter over the weight of every region, skewed ones included. */
        double dominantShare() {
            return weight == 0 ? 0 : (double) weights[leadingQuarter] / weight;
        }
    }

    // --- evidence about the regions of the leading quarter -------------------------------------

    private record Evidence(double cjkShare, int symbolSamples, double uprightGlyphShare,
            double rotatedGlyphShare, int punctuationSamples, double punctuationOffset) {

        static Evidence none() {
            return new Evidence(-1, 0, -1, -1, 0, -1);
        }

        /**
         * The script of the leading regions always; the symbol frames and the punctuation only for
         * a page that runs down the image, the one case they are needed for.
         */
        static Evidence of(List<Vote> leading, boolean runsDown) {
            long scripted = 0;
            long cjk = 0;
            int[] glyphs = new int[3];
            List<Double> offsets = new ArrayList<>();
            for (Vote vote : leading) {
                OcrRegion region = vote.region();
                for (int codePoint : region.text().codePoints().toArray()) {
                    if (!Character.isLetter(codePoint)) continue;
                    Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
                    if (script == Character.UnicodeScript.COMMON || script == Character.UnicodeScript.INHERITED) {
                        continue;
                    }
                    scripted++;
                    if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                            || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
                            || script == Character.UnicodeScript.BOPOMOFO) {
                        cjk++;
                    }
                }
                if (runsDown) {
                    countGlyphFrames(region, glyphs);
                    punctuationOffsets(region, offsets);
                }
            }
            double cjkShare = scripted == 0 ? 0 : (double) cjk / scripted;
            if (!runsDown) return new Evidence(cjkShare, 0, -1, -1, 0, -1);
            return new Evidence(cjkShare, glyphs[2],
                    glyphs[2] == 0 ? -1 : (double) glyphs[0] / glyphs[2],
                    glyphs[2] == 0 ? -1 : (double) glyphs[1] / glyphs[2],
                    offsets.size(), offsets.isEmpty() ? -1 : median(offsets));
        }
    }

    /**
     * Counts, into {@code glyphs}, the symbols of a region whose own box runs along +x ([0]) or
     * +y ([1]), and all symbols whose box is within tolerance of either ([2], the sample size).
     * A small box has a noisy angle, hence the wide tolerance.
     */
    private static void countGlyphFrames(OcrRegion region, int[] glyphs) {
        for (OcrWord word : region.words()) {
            for (OcrSymbol symbol : word.symbols()) {
                if (symbol.polygon().size() != 4) continue;
                OcrPoint first = symbol.polygon().get(0);
                OcrPoint second = symbol.polygon().get(1);
                if (first.equals(second)) continue;
                double degrees = Math.toDegrees(Math.atan2(second.y() - first.y(), second.x() - first.x()));
                long nearest = Math.round(degrees / 90);
                if (Math.abs(degrees - 90 * nearest) > SYMBOL_TOLERANCE_DEG) continue;
                int quarter = (int) Math.floorMod(nearest, 4L);
                glyphs[2]++;
                if (quarter == 0) glyphs[0]++;
                else if (quarter == 1) glyphs[1]++;
            }
        }
    }

    /**
     * For each punctuation or small-kana symbol of a region, where its centre lies in the band of
     * its own line, measured in the region's provider frame, whose local y runs from the top of the
     * line to its bottom: 0 at the top, 1 at the bottom. The line band is the extent along local y
     * of the other symbols that share the symbol's rows (they overlap it by at least half of the
     * narrower of the two). A mark with no such neighbour has no band and is not counted.
     */
    private static void punctuationOffsets(OcrRegion region, List<Double> offsets) {
        LocalFrame frame = LocalFrame.from(region);
        if (frame == null) return;
        List<Rectangle> marks = new ArrayList<>();
        List<Rectangle> glyphs = new ArrayList<>();
        for (OcrWord word : region.words()) {
            for (OcrSymbol symbol : word.symbols()) {
                if (symbol.polygon().size() < 4 || symbol.text().isBlank()) continue;
                Rectangle box = frame.bounds(symbol.polygon());
                (isPunctuationOrSmallKana(symbol.text()) ? marks : glyphs).add(box);
            }
        }
        for (Rectangle mark : marks) {
            int top = Integer.MAX_VALUE;
            int bottom = Integer.MIN_VALUE;
            for (Rectangle glyph : glyphs) {
                int overlap = Math.min(mark.y + mark.height, glyph.y + glyph.height) - Math.max(mark.y, glyph.y);
                if (overlap > 0 && overlap * 2 >= Math.min(mark.height, glyph.height)) {
                    top = Math.min(top, glyph.y);
                    bottom = Math.max(bottom, glyph.y + glyph.height);
                }
            }
            if (bottom > top) {
                offsets.add((mark.y + mark.height / 2.0 - top) / (bottom - top));
            }
        }
    }

    private static boolean isPunctuationOrSmallKana(String text) {
        return !text.isEmpty() && text.codePoints().allMatch(
                codePoint -> PUNCTUATION_AND_SMALL_KANA.indexOf(codePoint) >= 0);
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }
}
