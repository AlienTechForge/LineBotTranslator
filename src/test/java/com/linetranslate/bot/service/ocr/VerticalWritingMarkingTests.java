package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.linetranslate.bot.service.ocr.PageOrientation.Verdict;
import com.linetranslate.bot.service.ocr.RotatedPageFixtures.Marks;
import com.linetranslate.bot.service.ocr.RotatedPageFixtures.SymbolFrame;

/**
 * How the detector's verdict names upright vertical writing on the regions of a page, without
 * moving anything: the whole page when it was found to be vertical writing, and the paragraphs
 * that carry the evidence themselves when the page has no dominant way up.
 */
class VerticalWritingMarkingTests {

    private static final String CJK = "日本語の文章を読みます、そして書きます。";
    private static final String LATIN = "The quick brown fox jumps over";

    @Test
    void pinsTheSamplesAParagraphNeedsOnItsOwn() {
        assertThat(PageOrientationDetector.MIN_PARAGRAPH_SYMBOL_SAMPLES).isEqualTo(5);
        assertThat(PageOrientationDetector.MIN_PARAGRAPH_PUNCTUATION_SAMPLES).isEqualTo(2);
    }

    @Test
    void aPageOfVerticalWritingMarksEveryCjkRegionThatRunsDownTheImage() {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, 1);
        List<OcrRegion> regions = cjkParagraphs(page, Marks.HIGH, 6);
        PageOrientation orientation = PageOrientationDetector.detect(regions);
        assertThat(orientation.verdict()).isEqualTo(Verdict.VERTICAL_WRITING);
        OcrRegion turnedLatin = page.latin("latin", LATIN, 10, 300, .95f, 6);
        OcrRegion upright = new RotatedPageFixtures(400, 400, 0).latin("upright", LATIN, 10, 340, .95f, 7);
        List<OcrRegion> all = new ArrayList<>(regions);
        all.add(turnedLatin);
        all.add(upright);

        List<OcrRegion> marked = PageOrientationDetector.markVerticalWriting(all, orientation, false);

        assertThat(marked).hasSize(8);
        assertThat(marked.subList(0, 6)).allSatisfy(region ->
                assertThat(region.writingMode()).isEqualTo(WritingMode.VERTICAL_RL));
        // Latin running down the image is turned, not vertical writing, and nothing upright is touched.
        assertThat(marked.get(6)).isEqualTo(turnedLatin);
        assertThat(marked.get(6).writingMode()).isEqualTo(WritingMode.ROTATED_90);
        assertThat(marked.get(7)).isEqualTo(upright);
        // Only the mode changes: the geometry stays as the provider gave it.
        for (int index = 0; index < 6; index++) {
            assertThat(marked.get(index).polygon()).isEqualTo(regions.get(index).polygon());
            assertThat(marked.get(index).words()).isEqualTo(regions.get(index).words());
            assertThat(marked.get(index).rotationDegrees()).isCloseTo(90, org.assertj.core.data.Offset.offset(.01));
        }
    }

    @Test
    void anAmbiguousOrRotatedOrUprightPageIsLeftAsItIs() {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, 1);
        List<OcrRegion> ambiguous = cjkParagraphs(page, Marks.NONE, 6);
        List<OcrRegion> rotated = cjkParagraphs(page, Marks.LOW, 6);

        for (List<OcrRegion> regions : List.of(ambiguous, rotated)) {
            PageOrientation orientation = PageOrientationDetector.detect(regions);
            assertThat(orientation.verdict()).isIn(Verdict.AMBIGUOUS, Verdict.ROTATED);
            assertThat(PageOrientationDetector.markVerticalWriting(regions, orientation, false))
                    .isEqualTo(regions);
        }
        List<OcrRegion> latin = new ArrayList<>();
        RotatedPageFixtures flat = new RotatedPageFixtures(400, 400, 0);
        for (int index = 0; index < 6; index++) latin.add(flat.latin("l" + index, LATIN, 10, 10 + index * 40, .95f, index));
        assertThat(PageOrientationDetector.markVerticalWriting(
                latin, PageOrientationDetector.detect(latin), false)).isEqualTo(latin);
    }

    @Test
    void onAMixedPageOnlyTheParagraphsWithTheEvidenceThemselvesAreMarked() {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, 1);
        List<OcrRegion> regions = new ArrayList<>();
        // Marks high in their line: vertical writing, two samples each.
        regions.add(page.cjk("high-1", CJK, 10, 10, Marks.HIGH, SymbolFrame.FOLLOWS_LINE, 0));
        regions.add(page.cjk("high-2", CJK, 10, 50, Marks.HIGH, SymbolFrame.FOLLOWS_LINE, 1));
        // Marks low in their line: a turned page's paragraph. No marks at all: nothing to go by.
        regions.add(page.cjk("low", CJK, 10, 90, Marks.LOW, SymbolFrame.FOLLOWS_LINE, 2));
        regions.add(page.cjk("none", CJK, 10, 130, Marks.NONE, SymbolFrame.FOLLOWS_LINE, 3));
        // One mark only: one sample is under the two a paragraph needs.
        regions.add(page.cjk("one", "日本語の文章を読みます。", 10, 170, Marks.HIGH, SymbolFrame.FOLLOWS_LINE, 4));
        regions.add(page.latin("latin", LATIN, 10, 210, .95f, 5));
        // Upright text enough that no quarter holds four fifths of the page.
        RotatedPageFixtures flat = new RotatedPageFixtures(400, 400, 0);
        for (int index = 0; index < 4; index++) {
            regions.add(flat.latin("flat-" + index, LATIN, 10, 10 + index * 40, .95f, 6 + index));
        }
        PageOrientation orientation = PageOrientationDetector.detect(regions);
        assertThat(orientation.verdict()).isEqualTo(Verdict.MIXED);

        List<OcrRegion> marked = PageOrientationDetector.markVerticalWriting(regions, orientation, false);

        assertThat(marked).extracting(region -> region.id() + ":" + region.writingMode()).containsExactly(
                "high-1:VERTICAL_RL", "high-2:VERTICAL_RL", "low:ROTATED_90", "none:ROTATED_90",
                "one:ROTATED_90", "latin:ROTATED_90",
                "flat-0:HORIZONTAL", "flat-1:HORIZONTAL", "flat-2:HORIZONTAL", "flat-3:HORIZONTAL");
    }

    @Test
    void aMixedParagraphWithUprightGlyphBoxesIsMarkedOnlyWhenTheProviderIsTrustedToGiveThem() {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, 1);
        List<OcrRegion> regions = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            regions.add(page.cjk("c" + index, "日本語の文章を読みます", 10, 10 + index * 40,
                    Marks.NONE, SymbolFrame.UPRIGHT_IN_IMAGE, index));
        }
        RotatedPageFixtures flat = new RotatedPageFixtures(400, 400, 0);
        for (int index = 0; index < 4; index++) {
            regions.add(flat.latin("flat-" + index, LATIN, 10, 10 + index * 40, .95f, 4 + index));
        }
        PageOrientation orientation = PageOrientationDetector.detect(regions, true);
        assertThat(orientation.verdict()).isEqualTo(Verdict.MIXED);

        assertThat(PageOrientationDetector.markVerticalWriting(regions, orientation, true).subList(0, 4))
                .allSatisfy(region -> assertThat(region.writingMode()).isEqualTo(WritingMode.VERTICAL_RL));
        assertThat(PageOrientationDetector.markVerticalWriting(regions, orientation, false))
                .isEqualTo(regions);
    }

    private static List<OcrRegion> cjkParagraphs(RotatedPageFixtures page, Marks marks, int count) {
        List<OcrRegion> regions = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            regions.add(page.cjk("c" + index, CJK, 10, 10 + index * 40, marks, SymbolFrame.FOLLOWS_LINE, index));
        }
        return regions;
    }
}
