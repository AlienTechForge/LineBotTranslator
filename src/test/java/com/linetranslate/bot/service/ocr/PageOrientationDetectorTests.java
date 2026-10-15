package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.linetranslate.bot.service.ocr.PageOrientation.Verdict;
import com.linetranslate.bot.service.ocr.RotatedPageFixtures.Marks;
import com.linetranslate.bot.service.ocr.RotatedPageFixtures.SymbolFrame;

class PageOrientationDetectorTests {

    private static final String LATIN = "The quick brown fox jumps over";
    private static final String CJK = "日本語の文章を読みます、そして書きます。";

    private static final int LATIN_WEIGHT = 25;

    // --- the thresholds are the ones the design names ----------------------------------------

    @Test
    void pinsTheThresholds() {
        assertThat(PageOrientationDetector.QUARTER_TOLERANCE_DEG).isEqualTo(10);
        assertThat(PageOrientationDetector.MIN_DOMINANT_SHARE).isEqualTo(.80);
        assertThat(PageOrientationDetector.RELIABLE_CONFIDENCE).isEqualTo(.80f);
        assertThat(PageOrientationDetector.MIN_RELIABLE_WEIGHT).isEqualTo(20);
        assertThat(PageOrientationDetector.MIN_VOTING_REGIONS).isEqualTo(2);
        assertThat(PageOrientationDetector.SINGLE_REGION_WEIGHT).isEqualTo(40);
        assertThat(PageOrientationDetector.MAX_ROTATED_CJK_SHARE).isEqualTo(.50);
        assertThat(PageOrientationDetector.RETRY_RELIABLE_SHARE).isEqualTo(.80);
        assertThat(PageOrientationDetector.RELAXED_TOTAL_WEIGHT).isEqualTo(40);
    }

    @Test
    void symbolFramesAreNotTrustedUntilTheDumpHasMeasuredThem() {
        assertThat(PageOrientationDetector.SYMBOL_FRAME_FOLLOWS_GLYPH).isFalse();
    }

    // --- a rotated page --------------------------------------------------------------------------

    @Test
    void sixLatinParagraphsAtEachNonZeroQuarterMakeARotatedPage() {
        for (int turns = 1; turns <= 3; turns++) {
            PageOrientation orientation = PageOrientationDetector.detect(latinPage(turns, 6));

            assertThat(orientation.verdict()).as("turns=%d", turns).isEqualTo(Verdict.ROTATED);
            assertThat(orientation.quarterTurns()).isEqualTo(turns);
            assertThat(orientation.dominantShare()).isEqualTo(1d);
            assertThat(orientation.reliableShare()).isEqualTo(1d);
            assertThat(orientation.retryAdvised()).isFalse();
            assertThat(orientation.regions()).isEqualTo(6);
            assertThat(orientation.reliableRegions()).isEqualTo(6);
        }
    }

    @Test
    void anUprightPageIsUpright() {
        PageOrientation orientation = PageOrientationDetector.detect(latinPage(0, 6));

        assertThat(orientation.verdict()).isEqualTo(Verdict.UPRIGHT);
        assertThat(orientation.quarterTurns()).isZero();
        assertThat(orientation.retryAdvised()).isFalse();
    }

    @Test
    void aQuarterOfTheWeightUprightMakesTheRotatedPageMixed() {
        for (int turns = 1; turns <= 3; turns++) {
            List<OcrRegion> regions = new ArrayList<>(latinPage(turns, 6));
            regions.addAll(upright("u", 2, 100));

            PageOrientation orientation = PageOrientationDetector.detect(regions);

            assertThat(orientation.verdict()).as("turns=%d", turns).isEqualTo(Verdict.MIXED);
            assertThat(orientation.dominantShare()).isEqualTo(.75);
        }
    }

    @Test
    void theDominantShareIsAtLeastEightyPercentNotMoreThan() {
        // 4 of 5 equal paragraphs is exactly 80 percent; 4 of 6 is below it.
        List<OcrRegion> exact = new ArrayList<>(latinPage(1, 4));
        exact.addAll(upright("u", 1, 100));
        List<OcrRegion> below = new ArrayList<>(latinPage(1, 4));
        below.addAll(upright("u", 2, 100));

        assertThat(PageOrientationDetector.detect(exact).verdict()).isEqualTo(Verdict.ROTATED);
        assertThat(PageOrientationDetector.detect(below).verdict()).isEqualTo(Verdict.MIXED);
    }

    // --- weight, not a count of regions -----------------------------------------------------------

    @Test
    void oneLongRotatedParagraphOutweighsThreeUprightLabels() {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, 1);
        RotatedPageFixtures upright = new RotatedPageFixtures(400, 400, 0);
        List<OcrRegion> regions = new ArrayList<>();
        regions.add(page.latin("long", "A long paragraph that runs the whole width of the page here", 10, 10, .95f, 0));
        regions.add(upright.latin("l1", "OK", 10, 100, .95f, 1));
        regions.add(upright.latin("l2", "No", 10, 140, .95f, 2));
        regions.add(upright.latin("l3", "Go", 10, 180, .95f, 3));

        PageOrientation orientation = PageOrientationDetector.detect(regions);

        assertThat(orientation.verdict()).isEqualTo(Verdict.ROTATED);
        assertThat(orientation.quarterTurns()).isEqualTo(1);
    }

    @Test
    void oneRotatedLabelDoesNotTurnALongUprightParagraph() {
        RotatedPageFixtures turned = new RotatedPageFixtures(400, 400, 1);
        RotatedPageFixtures upright = new RotatedPageFixtures(400, 400, 0);
        List<OcrRegion> regions = List.of(
                upright.latin("long", "A long paragraph that runs the whole width of the page here", 10, 10, .95f, 0),
                turned.latin("label", "abc", 10, 100, .95f, 1));

        PageOrientation orientation = PageOrientationDetector.detect(regions);

        assertThat(orientation.verdict()).isEqualTo(Verdict.UPRIGHT);
        assertThat(orientation.quarterTurns()).isZero();
    }

    @Test
    void aSingleRegionNeedsAWeightOfFortyToStandForAPage() {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, 1);
        // 30 letters: enough reliable weight to judge by, but one region and under 40.
        OcrRegion thirty = page.latin("thirty", "abcdefghij abcdefghij abcdefghij", 10, 10, .95f, 0);
        OcrRegion fifty = page.latin("fifty", "abcdefghij abcdefghij abcdefghij abcdefghij abcdefghij", 10, 10, .95f, 0);
        OcrRegion fifteen = page.latin("fifteen", "abcde abcde abcde", 10, 10, .95f, 0);

        assertThat(PageOrientationDetector.detect(List.of(thirty)).verdict()).isEqualTo(Verdict.INSUFFICIENT);
        assertThat(PageOrientationDetector.detect(List.of(fifteen)).verdict()).isEqualTo(Verdict.INSUFFICIENT);
        assertThat(PageOrientationDetector.detect(List.of(fifty)).verdict()).isEqualTo(Verdict.ROTATED);
    }

    @Test
    void twoRegionsAreEnoughWhenNeitherIsHeavy() {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, 3);
        List<OcrRegion> two = List.of(
                page.latin("a", "abcdefghij abcdefghij", 10, 10, .95f, 0),
                page.latin("b", "abcdefghij abcdefghij", 10, 60, .95f, 1));

        assertThat(PageOrientationDetector.detect(two).verdict()).isEqualTo(Verdict.ROTATED);
    }

    @Test
    void noRegionsAndPunctuationOnlyRegionsAreInsufficient() {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, 1);

        assertThat(PageOrientationDetector.detect(List.of()).verdict()).isEqualTo(Verdict.INSUFFICIENT);
        assertThat(PageOrientationDetector.detect(null).verdict()).isEqualTo(Verdict.INSUFFICIENT);
        assertThat(PageOrientationDetector.detect(List.of(
                page.latin("dots", "........", 10, 10, .95f, 0))).verdict()).isEqualTo(Verdict.INSUFFICIENT);
    }

    @Test
    void onlyReliableTextDecidesAPageWithEnoughOfIt() {
        // Six reliable rotated paragraphs and two unreliable upright ones: the unreliable ones are
        // left out of the vote, so the page is judged on the six.
        List<OcrRegion> regions = new ArrayList<>(latinPage(1, 6));
        RotatedPageFixtures upright = new RotatedPageFixtures(400, 400, 0);
        regions.add(upright.latin("u1", LATIN, 10, 300, .5f, 6));
        regions.add(upright.latin("u2", LATIN, 10, 340, .5f, 7));

        PageOrientation orientation = PageOrientationDetector.detect(regions);

        assertThat(orientation.verdict()).isEqualTo(Verdict.ROTATED);
        assertThat(orientation.reliableRegions()).isEqualTo(6);
        assertThat(orientation.dominantShare()).isEqualTo(1d);
    }

    @Test
    void aRegionWithUnknownConfidenceIsNotReliable() {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, 1);
        OcrRegion known = page.latin("known", LATIN, 10, 10, .95f, 0);
        OcrRegion unknown = new OcrRegion("unknown", known.text(), known.polygon(), known.words(),
                0f, false, OcrBlockType.TEXT, List.of(), 1);

        assertThat(PageOrientationDetector.detect(List.of(known, unknown)).reliableRegions()).isEqualTo(1);
    }

    // --- skewed text counts against dominance only -----------------------------------------------

    @Test
    void textElevenDegreesOffAQuarterCountsInTheDenominatorOnly() {
        // 90 + 11 degrees is off the quarter by more than the tolerance, 90 + 9 is not.
        List<OcrRegion> oneSkewed = new ArrayList<>(latinPage(1, 5));
        oneSkewed.add(RotatedPageFixtures.atAngle("skewed", LATIN, 101, 200, 10, .95f, 5));
        List<OcrRegion> twoSkewed = new ArrayList<>(oneSkewed);
        twoSkewed.add(RotatedPageFixtures.atAngle("skewed2", LATIN, 101, 260, 10, .95f, 6));
        List<OcrRegion> withinTolerance = new ArrayList<>(latinPage(1, 5));
        withinTolerance.add(RotatedPageFixtures.atAngle("near", LATIN, 99, 200, 10, .95f, 5));
        withinTolerance.add(RotatedPageFixtures.atAngle("near2", LATIN, 99, 260, 10, .95f, 6));

        PageOrientation one = PageOrientationDetector.detect(oneSkewed);
        PageOrientation two = PageOrientationDetector.detect(twoSkewed);
        PageOrientation near = PageOrientationDetector.detect(withinTolerance);

        assertThat(one.verdict()).isEqualTo(Verdict.ROTATED);
        assertThat(one.dominantShare()).isEqualTo(5d / 6);
        assertThat(one.skewedShare()).isEqualTo(1d / 6);
        assertThat(two.verdict()).isEqualTo(Verdict.MIXED);
        assertThat(two.skewedShare()).isEqualTo(2d / 7);
        assertThat(near.verdict()).as("9 degrees still votes for the quarter").isEqualTo(Verdict.ROTATED);
        assertThat(near.skewedShare()).isZero();
    }

    @Test
    void aPageOfSkewedTextIsNeverTurned() {
        List<OcrRegion> regions = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            regions.add(RotatedPageFixtures.atAngle("s" + index, LATIN, 25, 10 + index * 40, 10, .95f, index));
        }

        PageOrientation orientation = PageOrientationDetector.detect(regions);

        assertThat(orientation.verdict()).isEqualTo(Verdict.INSUFFICIENT);
        assertThat(orientation.skewedShare()).isEqualTo(1d);
    }

    // --- CJK at 90 degrees: rotated page or upright vertical writing ---------------------------

    @Test
    void cjkAtNinetyDegreesWithoutEvidenceIsAmbiguous() {
        PageOrientation orientation = PageOrientationDetector.detect(cjkPage(1, Marks.NONE, SymbolFrame.FOLLOWS_LINE));

        assertThat(orientation.verdict()).isEqualTo(Verdict.AMBIGUOUS);
        assertThat(orientation.quarterTurns()).isEqualTo(1);
        assertThat(orientation.cjkShare()).isEqualTo(1d);
        assertThat(orientation.retryAdvised()).isFalse();
    }

    @Test
    void cjkAtTheOtherQuartersIsAlwaysARotatedPage() {
        assertThat(PageOrientationDetector.detect(cjkPage(2, Marks.NONE, SymbolFrame.FOLLOWS_LINE)).verdict())
                .isEqualTo(Verdict.ROTATED);
        assertThat(PageOrientationDetector.detect(cjkPage(3, Marks.NONE, SymbolFrame.FOLLOWS_LINE)).verdict())
                .isEqualTo(Verdict.ROTATED);
    }

    @Test
    void cjkAtNinetyDegreesWithPunctuationInTheLowerBandIsARotatedPage() {
        PageOrientation orientation = PageOrientationDetector.detect(cjkPage(1, Marks.LOW, SymbolFrame.FOLLOWS_LINE));

        assertThat(orientation.verdict()).isEqualTo(Verdict.ROTATED);
        assertThat(orientation.punctuationSamples()).isGreaterThanOrEqualTo(PageOrientationDetector.MIN_PUNCTUATION_SAMPLES);
        assertThat(orientation.punctuationOffset()).isGreaterThanOrEqualTo(PageOrientationDetector.LOW_PUNCTUATION_OFFSET);
    }

    @Test
    void cjkAtNinetyDegreesWithPunctuationInTheUpperBandIsVerticalWriting() {
        PageOrientation orientation = PageOrientationDetector.detect(cjkPage(1, Marks.HIGH, SymbolFrame.FOLLOWS_LINE));

        assertThat(orientation.verdict()).isEqualTo(Verdict.VERTICAL_WRITING);
        assertThat(orientation.punctuationOffset()).isLessThanOrEqualTo(PageOrientationDetector.HIGH_PUNCTUATION_OFFSET);
    }

    @Test
    void tooFewPunctuationMarksLeaveCjkAmbiguous() {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, 1);
        List<OcrRegion> regions = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            // Only the first two paragraphs carry a mark: two samples, under the minimum of three.
            String text = index < 2 ? "日本語の文章を読みます。" : "日本語の文章を読みます";
            regions.add(page.cjk("p" + index, text, 10, 10 + index * 40,
                    Marks.LOW, SymbolFrame.FOLLOWS_LINE, index));
        }

        PageOrientation orientation = PageOrientationDetector.detect(regions);

        assertThat(orientation.punctuationSamples()).isEqualTo(2);
        assertThat(orientation.verdict()).isEqualTo(Verdict.AMBIGUOUS);
    }

    @Test
    void punctuationInTheMiddleOfTheBandDecidesNothing() {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, 1);
        List<OcrRegion> regions = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            // Marks centred in the line, as zh-Hant typesets them.
            OcrRegion region = page.cjk("p" + index, "日本語の文章を読みます、そして書きます。",
                    10, 10 + index * 40, Marks.NONE, SymbolFrame.FOLLOWS_LINE, index);
            regions.add(withMarksAtBandOffset(region, page, 10, 10 + index * 40, 7));
        }

        PageOrientation orientation = PageOrientationDetector.detect(regions);

        assertThat(orientation.punctuationSamples()).isGreaterThanOrEqualTo(3);
        assertThat(orientation.punctuationOffset()).isBetween(.4, .6);
        assertThat(orientation.verdict()).isEqualTo(Verdict.AMBIGUOUS);
    }

    @Test
    void nonCjkTextAtNinetyDegreesIsARotatedPageWithoutAnyOtherEvidence() {
        // Hangul counts as CJK, Latin and Cyrillic do not.
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, 1);
        List<OcrRegion> cyrillic = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            cyrillic.add(page.paragraph("c" + index, "Быстрая коричневая лиса прыгает", "ru", 10, 10 + index * 40,
                    .95f, index, Marks.NONE, SymbolFrame.FOLLOWS_LINE));
        }

        PageOrientation orientation = PageOrientationDetector.detect(cyrillic);

        assertThat(orientation.verdict()).isEqualTo(Verdict.ROTATED);
        assertThat(orientation.cjkShare()).isZero();
    }

    @Test
    void textThatIsExactlyHalfCjkIsStillARotatedPageAndOneLetterMoreIsNot() {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, 1);
        List<OcrRegion> half = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            half.add(page.cjk("c" + index, "日本語の文章を読みます", 10, 10 + index * 40,
                    Marks.NONE, SymbolFrame.FOLLOWS_LINE, index));
            half.add(page.latin("l" + index, "abcdefghijk", 10, 150 + index * 40, .95f, 3 + index));
        }
        List<OcrRegion> more = new ArrayList<>(half);
        more.add(page.cjk("one", "日", 10, 300, Marks.NONE, SymbolFrame.FOLLOWS_LINE, 6));

        PageOrientation atHalf = PageOrientationDetector.detect(half);
        PageOrientation overHalf = PageOrientationDetector.detect(more);

        assertThat(atHalf.cjkShare()).isEqualTo(.5);
        assertThat(atHalf.verdict()).isEqualTo(Verdict.ROTATED);
        assertThat(overHalf.cjkShare()).isGreaterThan(.5);
        assertThat(overHalf.verdict()).isEqualTo(Verdict.AMBIGUOUS);
    }

    // --- tier 3: the frames of the symbols, only behind the constant -----------------------------

    @Test
    void symbolFramesDoNotActWhileTheConstantIsOff() {
        List<OcrRegion> uprightGlyphs = cjkPage(1, Marks.NONE, SymbolFrame.UPRIGHT_IN_IMAGE);
        List<OcrRegion> glyphsOnTheLine = cjkPage(1, Marks.NONE, SymbolFrame.FOLLOWS_LINE);

        assertThat(PageOrientationDetector.detect(uprightGlyphs).verdict()).isEqualTo(Verdict.AMBIGUOUS);
        assertThat(PageOrientationDetector.detect(glyphsOnTheLine).verdict()).isEqualTo(Verdict.AMBIGUOUS);
        assertThat(PageOrientationDetector.detect(uprightGlyphs, false).verdict()).isEqualTo(Verdict.AMBIGUOUS);
        // The shares are measured either way, so the log shows what the provider does.
        PageOrientation measured = PageOrientationDetector.detect(uprightGlyphs, false);
        assertThat(measured.symbolSamples()).isGreaterThan(8);
        assertThat(measured.uprightGlyphShare()).isEqualTo(1d);
        assertThat(measured.rotatedGlyphShare()).isZero();
    }

    @Test
    void withSymbolFramesTrustedUprightGlyphBoxesMeanVerticalWriting() {
        PageOrientation orientation = PageOrientationDetector.detect(
                cjkPage(1, Marks.NONE, SymbolFrame.UPRIGHT_IN_IMAGE), true);

        assertThat(orientation.verdict()).isEqualTo(Verdict.VERTICAL_WRITING);
        assertThat(orientation.uprightGlyphShare()).isEqualTo(1d);
    }

    @Test
    void withSymbolFramesTrustedGlyphBoxesOnTheLineMeanARotatedPage() {
        PageOrientation orientation = PageOrientationDetector.detect(
                cjkPage(1, Marks.NONE, SymbolFrame.FOLLOWS_LINE), true);

        assertThat(orientation.verdict()).isEqualTo(Verdict.ROTATED);
        assertThat(orientation.rotatedGlyphShare()).isEqualTo(1d);
        assertThat(orientation.uprightGlyphShare()).isZero();
    }

    @Test
    void symbolFramesNeedEightSamplesAndFallBackToThePunctuation() {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, 1);
        List<OcrRegion> few = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            // One symbol per paragraph carries a box: six samples, under the minimum of eight.
            OcrRegion full = page.cjk("p" + index, "日本語の文章を読みます", 10, 10 + index * 40,
                    Marks.NONE, SymbolFrame.UPRIGHT_IN_IMAGE, index);
            few.add(withSymbolCount(full, 1));
        }
        List<OcrRegion> withMarks = cjkPage(1, Marks.LOW, SymbolFrame.UPRIGHT_IN_IMAGE);

        assertThat(PageOrientationDetector.detect(few, true).verdict()).isEqualTo(Verdict.AMBIGUOUS);
        // Upright boxes would say vertical writing, but the marks are low in the line: tier 3 comes
        // first and wins when it has enough samples.
        assertThat(PageOrientationDetector.detect(withMarks, true).verdict()).isEqualTo(Verdict.VERTICAL_WRITING);
        assertThat(PageOrientationDetector.detect(withMarks, false).verdict()).isEqualTo(Verdict.ROTATED);
    }

    // --- retry advice ------------------------------------------------------------------------------

    @Test
    void retryIsAdvisedExactlyForARotatedPageWhoseReliableShareIsBelowEightyPercent() {
        PageOrientation good = PageOrientationDetector.detect(rotatedWithUnreliable(8, 0));
        PageOrientation fiveSixths = PageOrientationDetector.detect(rotatedWithUnreliable(5, 1));
        PageOrientation exactlyEighty = PageOrientationDetector.detect(rotatedWithUnreliable(4, 1));
        PageOrientation threeQuarters = PageOrientationDetector.detect(rotatedWithUnreliable(6, 2));

        assertThat(good.verdict()).isEqualTo(Verdict.ROTATED);
        assertThat(good.retryAdvised()).isFalse();
        assertThat(fiveSixths.retryAdvised()).isFalse();
        assertThat(exactlyEighty.reliableShare()).isEqualTo(.8);
        assertThat(exactlyEighty.retryAdvised()).as("0.80 is not below 0.80").isFalse();
        assertThat(threeQuarters.verdict()).isEqualTo(Verdict.ROTATED);
        assertThat(threeQuarters.reliableShare()).isEqualTo(.75);
        assertThat(threeQuarters.retryAdvised()).isTrue();
    }

    @Test
    void anUprightOrMixedPageIsNeverAdvisedARetry() {
        List<OcrRegion> poorUpright = new ArrayList<>(upright("a", 4, 100));
        poorUpright.addAll(poorlyRead(upright("b", 4, 400)));
        List<OcrRegion> mixed = new ArrayList<>(latinPage(1, 4));
        mixed.addAll(upright("m", 2, 300));

        assertThat(PageOrientationDetector.detect(poorUpright).verdict()).isEqualTo(Verdict.UPRIGHT);
        assertThat(PageOrientationDetector.detect(poorUpright).retryAdvised()).isFalse();
        assertThat(PageOrientationDetector.detect(mixed).verdict()).isEqualTo(Verdict.MIXED);
        assertThat(PageOrientationDetector.detect(mixed).retryAdvised()).isFalse();
    }

    @Test
    void textReadSoPoorlyThatNothingIsReliableStillShowsWhichWayThePageRuns() {
        // 4 x 25 letters, all at 0.5: no reliable weight at all, but all of it runs the same way.
        List<OcrRegion> poor = poorlyRead(latinPage(1, 4));

        PageOrientation orientation = PageOrientationDetector.detect(poor);

        assertThat(orientation.verdict()).isEqualTo(Verdict.ROTATED);
        assertThat(orientation.quarterTurns()).isEqualTo(1);
        assertThat(orientation.retryAdvised()).isTrue();
        assertThat(orientation.reliableShare()).isZero();
    }

    @Test
    void poorlyReadTextThatDoesNotAgreeOrIsTooLittleDecidesNothing() {
        List<OcrRegion> split = new ArrayList<>(poorlyRead(latinPage(1, 2)));
        split.addAll(poorlyRead(upright("u", 2, 300)));
        List<OcrRegion> tooLittle = poorlyRead(latinPage(1, 1));
        List<OcrRegion> poorUpright = poorlyRead(upright("u", 4, 10));

        assertThat(PageOrientationDetector.detect(split).verdict()).isEqualTo(Verdict.INSUFFICIENT);
        assertThat(PageOrientationDetector.detect(tooLittle).verdict()).isEqualTo(Verdict.INSUFFICIENT);
        assertThat(PageOrientationDetector.detect(poorUpright).verdict()).isEqualTo(Verdict.INSUFFICIENT);
    }

    // --- helpers ---------------------------------------------------------------------------------

    @Test
    void reliableWeightCountsLettersAndDigitsOfReliableRegionsOnly() {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, 0);
        List<OcrRegion> regions = List.of(
                page.latin("a", "ab 12, c!", 10, 10, .95f, 0),
                page.latin("b", "abcdefgh", 10, 50, .5f, 1));

        assertThat(PageOrientationDetector.reliableWeight(regions)).isEqualTo(5);
        assertThat(PageOrientationDetector.reliableWeight(null)).isZero();
    }

    private static List<OcrRegion> latinPage(int turns, int paragraphs) {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, turns);
        List<OcrRegion> regions = new ArrayList<>();
        for (int index = 0; index < paragraphs; index++) {
            regions.add(page.latin("p" + index, LATIN, 10, 10 + index * 40, .95f, index));
        }
        assertThat(PageOrientationDetector.reliableWeight(regions)).isEqualTo(paragraphs * LATIN_WEIGHT);
        return regions;
    }

    private static List<OcrRegion> upright(String prefix, int paragraphs, int top) {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, 0);
        List<OcrRegion> regions = new ArrayList<>();
        for (int index = 0; index < paragraphs; index++) {
            regions.add(page.latin(prefix + index, LATIN, 10, top + index * 40, .95f, 20 + index));
        }
        return regions;
    }

    private static List<OcrRegion> cjkPage(int turns, Marks marks, SymbolFrame frame) {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, turns);
        List<OcrRegion> regions = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            regions.add(page.cjk("c" + index, CJK, 10, 10 + index * 40, marks, frame, index));
        }
        return regions;
    }

    /** {@code reliable} well-read rotated paragraphs and {@code unreliable} poorly read ones, all the same way up. */
    private static List<OcrRegion> rotatedWithUnreliable(int reliable, int unreliable) {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 600, 1);
        List<OcrRegion> regions = new ArrayList<>();
        for (int index = 0; index < reliable + unreliable; index++) {
            regions.add(page.latin("p" + index, LATIN, 10, 10 + index * 40,
                    index < reliable ? .95f : .5f, index));
        }
        return regions;
    }

    private static List<OcrRegion> poorlyRead(List<OcrRegion> regions) {
        return regions.stream().map(region -> new OcrRegion(region.id(), region.text(), region.polygon(),
                region.words(), .5f, true, region.blockType(), region.languages(), region.readingOrder())).toList();
    }

    /** The same paragraph with its marks moved to a given offset below the top of the line band. */
    private static OcrRegion withMarksAtBandOffset(
            OcrRegion region, RotatedPageFixtures page, int x, int y, int offset) {
        List<OcrWord> words = new ArrayList<>();
        int cursor = x;
        for (OcrWord word : region.words()) {
            List<OcrSymbol> symbols = new ArrayList<>();
            for (OcrSymbol symbol : word.symbols()) {
                boolean mark = symbol.text().equals("、") || symbol.text().equals("。");
                int top = mark ? y + offset : y;
                int height = mark ? 6 : RotatedPageFixtures.GLYPH_HEIGHT;
                symbols.add(new OcrSymbol(symbol.text(), List.of(
                        page.turn(cursor, top), page.turn(cursor + RotatedPageFixtures.GLYPH_WIDTH, top),
                        page.turn(cursor + RotatedPageFixtures.GLYPH_WIDTH, top + height),
                        page.turn(cursor, top + height)), symbol.confidence(), true));
                cursor += RotatedPageFixtures.GLYPH_WIDTH;
            }
            words.add(new OcrWord(word.text(), word.polygon(), word.confidence(), true, symbols));
        }
        return new OcrRegion(region.id(), region.text(), region.polygon(), words, region.confidence(), true,
                region.blockType(), region.languages(), region.readingOrder());
    }

    private static OcrRegion withSymbolCount(OcrRegion region, int keep) {
        List<OcrWord> words = region.words().stream()
                .map(word -> new OcrWord(word.text(), word.polygon(), word.confidence(), true,
                        word.symbols().subList(0, Math.min(keep, word.symbols().size()))))
                .toList();
        return new OcrRegion(region.id(), region.text(), region.polygon(), words, region.confidence(), true,
                region.blockType(), region.languages(), region.readingOrder());
    }
}
