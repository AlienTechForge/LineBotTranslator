package com.linetranslate.bot.service.ocr;

import static com.linetranslate.bot.service.ocr.VerticalFixtures.column;
import static com.linetranslate.bot.service.ocr.VerticalFixtures.rectangle;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** When a region the segmenter keeps whole is a column of upright vertical writing. */
class WritingModeClassifierTests {

    @Test
    void aStackOfSingleGlyphCjkWordsIsVerticalWriting() {
        // Five glyph boxes, 30 wide and 30 tall, one under the other in a 30x160 box.
        OcrRegion stack = region("stack", "糖醋排骨飯", rectangle(10, 10, 30, 160),
                column("糖醋排骨飯", 10, 10, 30, 30, 32));

        assertThat(WritingModeClassifier.columnMode(stack)).isEqualTo(WritingMode.VERTICAL_RL);
    }

    @Test
    void oneMultiSymbolWordWhoseSymbolsAreStackedIsVerticalWriting() {
        List<OcrWord> glyphs = column("糖醋排骨飯", 10, 10, 30, 30, 32);
        List<OcrSymbol> symbols = glyphs.stream()
                .map(word -> new OcrSymbol(word.text(), word.polygon(), .98f, true)).toList();
        OcrWord word = new OcrWord("糖醋排骨飯", rectangle(10, 10, 30, 160), .98f, true, symbols);

        assertThat(WritingModeClassifier.columnMode(
                region("one-word", "糖醋排骨飯", rectangle(10, 10, 30, 160), List.of(word))))
                .isEqualTo(WritingMode.VERTICAL_RL);
    }

    @Test
    void aTallNarrowCellOfHorizontalTwoGlyphLinesIsNotAStack() {
        // The cell is as tall and narrow as a column, and its words are stacked, but each is a
        // line two glyphs wide and one tall: a box twice as wide as high is a line, not a glyph.
        List<OcrWord> lines = new ArrayList<>();
        for (int line = 0; line < 5; line++) {
            lines.add(new OcrWord("染髮", rectangle(10, 10 + line * 32, 30, 15), .98f, true));
        }

        assertThat(WritingModeClassifier.columnMode(
                region("lines", "染髮染髮染髮染髮染髮", rectangle(10, 10, 30, 160), lines)))
                .isEqualTo(WritingMode.HORIZONTAL);
    }

    @Test
    void singleGlyphsWrappedTwoToALineAreNotAStackEither() {
        // Six glyph boxes in three rows of two: only every other step goes down, because the
        // nearest neighbour of a glyph is the one beside it.
        List<OcrWord> glyphs = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            glyphs.add(new OcrWord("字", rectangle(10 + (index % 2) * 16, 10 + (index / 2) * 40, 14, 14),
                    .98f, true));
        }

        assertThat(WritingModeClassifier.columnMode(
                region("wrapped", "字字字字字字", rectangle(10, 10, 30, 160), glyphs)))
                .isEqualTo(WritingMode.HORIZONTAL);
    }

    @Test
    void stackedSingleLatinLettersAreVerticalWritingThatReadsLeftToRight() {
        assertThat(WritingModeClassifier.columnMode(
                region("tea", "TEA", rectangle(10, 10, 30, 100), column("TEA", 10, 10, 30, 30, 33))))
                .isEqualTo(WritingMode.VERTICAL_LR);
    }

    @Test
    void aCellThatIsNotTallEnoughIsNotAColumn() {
        // 30 wide and 50 tall is 1.67 times, under the 1.8 a column needs.
        assertThat(WritingModeClassifier.columnMode(
                region("short", "糖醋", rectangle(10, 10, 30, 50), column("糖醋", 10, 10, 30, 24, 25))))
                .isEqualTo(WritingMode.HORIZONTAL);
        assertThat(WritingModeClassifier.columnMode(
                region("tall", "糖醋", rectangle(10, 10, 30, 55), column("糖醋", 10, 10, 30, 24, 28))))
                .isEqualTo(WritingMode.VERTICAL_RL);
    }

    @Test
    void aStackOfDigitsIsNeitherCjkNorLetters() {
        assertThat(WritingModeClassifier.columnMode(
                region("digits", "2800", rectangle(10, 10, 30, 130), column("2800", 10, 10, 30, 30, 33))))
                .isEqualTo(WritingMode.HORIZONTAL);
    }

    @Test
    void aRegionWhoseFrameIsTurnedKeepsItsFrameMode() {
        // The same stack read in a frame that points down the image is a rotated line, not a column.
        List<OcrPoint> turned = List.of(new OcrPoint(40, 10), new OcrPoint(40, 170),
                new OcrPoint(10, 170), new OcrPoint(10, 10));
        OcrRegion region = region("turned", "糖醋排骨飯", turned, column("糖醋排骨飯", 10, 10, 30, 30, 32));

        assertThat(region.writingMode()).isEqualTo(WritingMode.ROTATED_90);
        assertThat(WritingModeClassifier.columnMode(region)).isEqualTo(WritingMode.ROTATED_90);
    }

    @Test
    void aRegionWithoutWordGeometryIsLeftAlone() {
        assertThat(WritingModeClassifier.columnMode(
                region("bare", "糖醋排骨飯", rectangle(10, 10, 30, 160), List.of())))
                .isEqualTo(WritingMode.HORIZONTAL);
        assertThat(WritingModeClassifier.columnMode(null)).isEqualTo(WritingMode.HORIZONTAL);
    }

    @Test
    void theScriptRuleCountsLettersAndLeavesTheCommonScriptOut() {
        assertThat(WritingModeClassifier.isMostlyCjk(List.of("コーヒー"))).isTrue();
        assertThat(WritingModeClassifier.isMostlyCjk(List.of("香酥飯", "TEA"))).as("a tie is not CJK").isFalse();
        assertThat(WritingModeClassifier.isMostlyCjk(List.of("香酥飯", "TE"))).isTrue();
        assertThat(WritingModeClassifier.isMostlyCjk(List.of("1234"))).isFalse();
        assertThat(WritingModeClassifier.isMostlyCjk(List.of("김치찌개"))).isTrue();
    }

    private static OcrRegion region(String id, String text, List<OcrPoint> polygon, List<OcrWord> words) {
        return new OcrRegion(id, text, polygon, words, .98f, true, OcrBlockType.TEXT, List.of(), 0);
    }
}
