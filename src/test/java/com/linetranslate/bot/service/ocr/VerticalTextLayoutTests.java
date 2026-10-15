package com.linetranslate.bot.service.ocr;

import static com.linetranslate.bot.service.ocr.VerticalFixtures.cjkFonts;
import static com.linetranslate.bot.service.ocr.VerticalFixtures.column;
import static com.linetranslate.bot.service.ocr.VerticalFixtures.diagnostic;
import static com.linetranslate.bot.service.ocr.VerticalFixtures.differing;
import static com.linetranslate.bot.service.ocr.VerticalFixtures.inkBounds;
import static com.linetranslate.bot.service.ocr.VerticalFixtures.inkCount;
import static com.linetranslate.bot.service.ocr.VerticalFixtures.rectangle;
import static com.linetranslate.bot.service.ocr.VerticalFixtures.render;
import static com.linetranslate.bot.service.ocr.VerticalFixtures.singleColumn;
import static com.linetranslate.bot.service.ocr.VerticalFixtures.vertical;
import static com.linetranslate.bot.service.ocr.VerticalFixtures.whiteImage;
import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.linetranslate.bot.service.ocr.VerticalFixtures.Rendered;
import com.linetranslate.bot.service.ocr.VerticalTextLayout.Cell;
import com.linetranslate.bot.service.ocr.VerticalTextLayout.Kind;
import com.linetranslate.bot.service.ocr.VerticalTextLayout.Unit;

/**
 * The vertical layout engine, by what it places and by the pixels it leaves. The pictures are
 * structural checks (where the ink is, how tall and wide it is, in what order the columns fill),
 * never binary goldens, which would change with every version of a font. A diagnostic picture of
 * each case is written under {@code target/vertical-layout}.
 *
 * <p>The pixel tests set CJK text, so they need a face that covers it. Where the machine has no
 * Noto Sans CJK, Java's logical font stands in for it through the system's fallback faces, and
 * continuous integration, which has the real face, runs the same assertions on it.
 */
class VerticalTextLayoutTests {

    // --- where the ink is ----------------------------------------------------------------------

    @Test
    void aCjkReplacementInANarrowCellRunsDownTheColumnWithUprightGlyphs() throws Exception {
        BufferedImage original = whiteImage(140, 220);
        OcrRegion cell = singleColumn("column", "糖醋排骨飯", 50, 25, 34, 170);

        Rendered rendered = render(original, cjkFonts(), new ImageRegionOverlay(cell, "酸甜排骨飯"));
        diagnostic("single-column", rendered.output());

        assertThat(rendered.result().decisions()).extracting(OverlayRenderDecision::reason)
                .containsExactly("rendered");
        int[] ink = inkBounds(rendered.output(), 48, 23, 38, 174);
        assertThat(ink[3]).as("ink must run down the column (%sx%s)", ink[2], ink[3])
                .isGreaterThan(ink[2] * 3);
        assertThat(ink[2]).as("one column of upright glyphs is no wider than the cell").isLessThanOrEqualTo(34);
        assertThat(ink[1] - 25).as("the first glyph starts in the top fifth of the cell")
                .isLessThan(170 / 5);
        assertThat(ink[1] + ink[3] - 25).as("and the last ends inside the cell").isLessThanOrEqualTo(170);
        // Nothing outside the cell is touched.
        for (int y = 0; y < 220; y++) {
            for (int x = 0; x < 140; x++) {
                if (x >= 48 && x <= 86 && y >= 23 && y <= 197) continue;
                assertThat(rendered.output().getRGB(x, y)).as("pixel %s,%s", x, y).isEqualTo(original.getRGB(x, y));
            }
        }
    }

    @Test
    void aColumnOfVerticalMenuTextAsVisionReadsItIsSetAsAColumnEndToEnd() throws Exception {
        // Nothing here says "vertical": an axis-aligned paragraph of single-glyph words, one stack of
        // five, and a menu of four columns. The segmenter names them and the renderer sets them.
        BufferedImage original = whiteImage(240, 240);
        OcrRegion single = new OcrRegion("single", "糖醋排骨飯", rectangle(20, 20, 34, 170),
                column("糖醋排骨飯", 20, 20, 34, 34, 34), .98f, true, OcrBlockType.TEXT, List.of(), 0);
        List<OcrWord> menuWords = new java.util.ArrayList<>();
        String[] names = {"香酥雞腿飯", "經典排骨飯", "澎湃海鮮飯"};
        for (int index = 0; index < names.length; index++) {
            menuWords.addAll(column(names[index], 90 + index * 44, 20, 34, 34, 34));
        }
        OcrRegion menu = new OcrRegion("menu", "香酥雞腿飯 經典排骨飯 澎湃海鮮飯",
                rectangle(90, 20, 122, 170), menuWords, .98f, true, OcrBlockType.TEXT, List.of(), 1);

        List<OcrRegion> segmented = new OcrRegionSegmenter().segment(List.of(single, menu));

        assertThat(segmented).extracting(OcrRegion::writingMode).containsOnly(WritingMode.VERTICAL_RL);
        assertThat(segmented).hasSize(4);
        List<ImageRegionOverlay> overlays = new java.util.ArrayList<>();
        String[] translations = {"酸甜排骨飯", "炸雞腿飯", "經典排骨飯", "海鮮飯"};
        for (int index = 0; index < segmented.size(); index++) {
            overlays.add(new ImageRegionOverlay(segmented.get(index), translations[index]));
        }

        Rendered rendered = render(original, cjkFonts(), overlays.toArray(ImageRegionOverlay[]::new));
        diagnostic("end-to-end", rendered.output());

        assertThat(rendered.result().renderedBlockCount()).isEqualTo(4);
        for (ImageRegionOverlay overlay : overlays) {
            java.awt.Rectangle cell = OverlaySafetyPolicy.polygon(overlay.region().polygon()).getBounds();
            int[] ink = inkBounds(rendered.output(), cell.x, cell.y, cell.width, cell.height);
            assertThat(ink[3]).as("%s (%sx%s)", overlay.region().id(), ink[2], ink[3]).isGreaterThan(ink[2] * 3);
        }
    }

    @Test
    void columnsFillFromTheRightAndTheFirstColumnHoldsTheStartOfTheText() throws Exception {
        // The first three glyphs are one stroke each and the last three are the densest in the set,
        // so which side holds which shows in the ink. Six glyphs, three to a column.
        BufferedImage original = whiteImage(140, 160);
        OcrRegion cell = vertical("two", "一一一鬱鬱鬱", rectangle(20, 20, 80, 110),
                column("一一一", 66, 20, 34, 34, 36), "two", false);

        Rendered rendered = render(original, cjkFonts(), new ImageRegionOverlay(cell, "一一一鬱鬱鬱"));
        diagnostic("two-columns", rendered.output());

        assertThat(rendered.result().renderedBlockCount()).isEqualTo(1);
        int right = inkCount(rendered.output(), 60, 20, 40, 110);
        int left = inkCount(rendered.output(), 20, 20, 40, 110);
        assertThat(right).as("the first column, on the right, holds the light glyphs").isLessThan(left);
        assertThat(inkBounds(rendered.output(), 20, 20, 40, 110)[3])
                .as("both columns start at the top").isGreaterThan(0);
        int[] rightInk = inkBounds(rendered.output(), 60, 20, 40, 110);
        int[] leftInk = inkBounds(rendered.output(), 20, 20, 40, 110);
        assertThat(leftInk[1]).as("a multi-column cell is top-aligned, not centred").isLessThanOrEqualTo(rightInk[1] + 4);
    }

    @Test
    void theProlongedSoundMarkIsTurnedSoItsStrokeRunsDownTheColumn() throws Exception {
        // In a column the mark that is a horizontal bar in a line stands up, a thin vertical stroke;
        // set the way a horizontal engine sets it, they would be short bars. The mark alone is a
        // letter of no script, so a glyph of the replacement is what makes it Japanese text.
        BufferedImage original = whiteImage(140, 220);
        OcrRegion cell = singleColumn("marks", "コーヒー", 50, 25, 34, 170);

        Rendered rendered = render(original, cjkFonts(), new ImageRegionOverlay(cell, "ーーー天"));
        diagnostic("prolonged-marks", rendered.output());

        // Three marks and a glyph centred in a 170px cell: the marks are in the upper two thirds.
        int[] strokes = inkBounds(rendered.output(), 48, 25, 38, 75);
        assertThat(strokes[3]).as("there are marks up there").isPositive();
        assertThat(strokes[2]).as("a stroke, not a bar (%sx%s)", strokes[2], strokes[3]).isLessThanOrEqualTo(8);
    }

    @Test
    void aTurnedMarkIsDrawnWithItsStrokeAlongTheColumnWhicheverWayTheLayoutIsDrawn() {
        Font font = cjkFonts().fontFor("ー", 12, Font.PLAIN).orElseThrow();
        BufferedImage image = whiteImage(100, 200);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(java.awt.Color.BLACK);
        Cell cell = new Cell(20, 10, 34, 170);
        VerticalTextLayout.Layout layout = VerticalTextLayout.layoutAt(graphics,
                VerticalTextLayout.units("ーーーー"), List.of(cell), font, 28, 1d);
        VerticalTextLayout.draw(graphics, layout, List.of(cell));
        graphics.dispose();

        int[] ink = inkBounds(image, 0, 0, 100, 200);
        assertThat(layout.fits()).isTrue();
        assertThat(ink[2]).as("a stroke, not a bar (%sx%s)", ink[2], ink[3]).isLessThanOrEqualTo(8);
        assertThat(ink[3]).isGreaterThan(ink[2] * 6);
    }

    @Test
    void theStructureOfTwoColumnsIsTheFirstGlyphsOnTheRight() {
        Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        VerticalTextLayout.Layout layout = VerticalTextLayout.fit(graphics, "一二三四五六",
                List.of(new Cell(2, 2, 76, 106)), new Font("Dialog", Font.PLAIN, 12), 33, false);
        graphics.dispose();

        assertThat(layout.fits()).isTrue();
        assertThat(layout.columns()).hasSize(2);
        assertThat(layout.columns().get(0).index()).as("the first column is the rightmost").isZero();
        assertThat(layout.columns().get(0).units()).extracting(placed -> placed.unit().value())
                .containsExactly("一", "二", "三");
        assertThat(layout.columns().get(1).units()).extracting(placed -> placed.unit().value())
                .containsExactly("四", "五", "六");
        assertThat(layout.columns().get(0).units().get(1).offset())
                .as("each glyph advances one em").isEqualTo(layout.size() * layout.condense());
    }

    @Test
    void aFlowFillsItsCellsInOrderAndLeavesTheLaterOnesEmptyWhenTheTextIsShort() throws Exception {
        List<OcrPoint> union = rectangle(20, 20, 90, 110);
        List<List<OcrPoint>> cells = List.of(rectangle(65, 20, 45, 110), rectangle(20, 20, 45, 110));
        OcrRegion flow = new OcrRegion("flow", "一二三四五六", union,
                column("一二三", 70, 20, 34, 34, 36), .99f, true, OcrBlockType.TEXT, List.of(), 0, "flow", false,
                WritingMode.VERTICAL_RL, cells);

        Rendered shortText = render(whiteImage(140, 160), cjkFonts(), new ImageRegionOverlay(flow, "一二"));
        Rendered longText = render(whiteImage(140, 160), cjkFonts(), new ImageRegionOverlay(flow, "一二三四五六"));
        diagnostic("flow-short", shortText.output());
        diagnostic("flow-long", longText.output());

        assertThat(inkCount(shortText.output(), 65, 20, 45, 110)).as("short text, right cell").isPositive();
        assertThat(inkCount(shortText.output(), 20, 20, 45, 110)).as("short text, left cell").isZero();
        assertThat(inkCount(longText.output(), 65, 20, 45, 110)).as("long text, right cell").isPositive();
        assertThat(inkCount(longText.output(), 20, 20, 45, 110)).as("long text, left cell").isPositive();
    }

    // --- units ---------------------------------------------------------------------------------

    @Test
    void punctuationTakesItsVerticalPresentationFormAndTheProlongedSoundMarkTurns() {
        String mapped = VerticalTextLayout.mapped("「」、。（）…ー");

        assertThat(mapped).isEqualTo("\uFE41\uFE42\uFE11\uFE12\uFE35\uFE36\uFE19ー");
        assertThat(VerticalTextLayout.mapped("『』，：；！？【】《》〈〉｛｝〔〕‥—―–"))
                .isEqualTo("\uFE43\uFE44\uFE10\uFE13\uFE14\uFE15\uFE16\uFE3B\uFE3C\uFE3D\uFE3E"
                        + "\uFE3F\uFE40\uFE37\uFE38\uFE39\uFE3A\uFE30\uFE31\uFE31\uFE32");
        assertThat(VerticalTextLayout.mapped("天地")).as("a character without a form is itself").isEqualTo("天地");

        List<Unit> units = VerticalTextLayout.units("「天」ーー～〜");
        assertThat(units).extracting(Unit::kind).containsExactly(
                Kind.UPRIGHT, Kind.UPRIGHT, Kind.UPRIGHT, Kind.TURNED, Kind.TURNED, Kind.TURNED, Kind.TURNED);
        assertThat(units.get(0).value()).isEqualTo("\uFE41");
    }

    @Test
    void oneOrTwoDigitsAreSetSideBySideAndEverythingElseLatinRunsSideways() {
        assertThat(VerticalTextLayout.units("12")).extracting(Unit::kind).containsExactly(Kind.TATE_CHU_YOKO);
        assertThat(VerticalTextLayout.units("7")).extracting(Unit::kind).containsExactly(Kind.TATE_CHU_YOKO);
        assertThat(VerticalTextLayout.units("123")).extracting(Unit::kind).containsExactly(Kind.SIDEWAYS);
        assertThat(VerticalTextLayout.units("ABC")).extracting(Unit::kind).containsExactly(Kind.SIDEWAYS);
        assertThat(VerticalTextLayout.units("B2")).as("a digit next to a letter belongs to the word")
                .extracting(Unit::kind).containsExactly(Kind.SIDEWAYS);
        assertThat(VerticalTextLayout.units("天ABC地 12")).extracting(Unit::kind).containsExactly(
                Kind.UPRIGHT, Kind.SIDEWAYS, Kind.UPRIGHT, Kind.SPACE, Kind.TATE_CHU_YOKO);
        assertThat(VerticalTextLayout.units("全角１２")).as("full-width digits are upright, one em each")
                .extracting(Unit::kind).containsExactly(Kind.UPRIGHT, Kind.UPRIGHT, Kind.UPRIGHT, Kind.UPRIGHT);
    }

    @Test
    void aSpaceIsHalfAnEmAndALineFeedEndsTheColumn() {
        Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        Font font = new Font("Dialog", Font.PLAIN, 12);
        VerticalTextLayout.Layout spaced = VerticalTextLayout.layoutAt(graphics,
                VerticalTextLayout.units("天 地"), List.of(new Cell(0, 0, 40, 200)), font, 20, 1d);
        VerticalTextLayout.Layout broken = VerticalTextLayout.layoutAt(graphics,
                VerticalTextLayout.units("天\n\n地"), List.of(new Cell(0, 0, 80, 200)), font, 20, 1d);
        graphics.dispose();

        assertThat(spaced.columns()).hasSize(1);
        assertThat(spaced.columns().get(0).units().get(2).offset()).as("after the glyph and the space")
                .isEqualTo(30d);
        assertThat(broken.columns()).as("blank lines make one break").hasSize(2);
        assertThat(broken.columns().get(1).units().get(0).unit().value()).isEqualTo("地");
    }

    @Test
    void aSidewaysRunLongerThanAColumnBreaksBetweenCharacters() {
        Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        Font font = new Font("Dialog", Font.PLAIN, 12);
        List<Unit> units = VerticalTextLayout.units("ABCDEFGHIJKLMNOP");
        VerticalTextLayout.Layout layout = VerticalTextLayout.layoutAt(graphics, units,
                List.of(new Cell(0, 0, 140, 60)), font, 16, 1d);
        VerticalTextLayout.Layout wholeWord = VerticalTextLayout.layoutAt(graphics,
                VerticalTextLayout.units("天地 ABC"), List.of(new Cell(0, 0, 80, 66)), font, 16, 1d);
        graphics.dispose();

        assertThat(layout.fits()).isTrue();
        assertThat(layout.columns().size()).isGreaterThan(1);
        assertThat(layout.columns().stream().flatMap(column -> column.units().stream())
                .map(placed -> placed.unit().value()).reduce("", String::concat))
                .as("nothing lost or reordered").isEqualTo("ABCDEFGHIJKLMNOP");
        assertThat(layout.columns()).allSatisfy(column -> assertThat(column.length()).isLessThanOrEqualTo(58d));
        // A run that fits a column of its own moves there whole instead of being broken.
        assertThat(wholeWord.columns()).hasSize(2);
        assertThat(wholeWord.columns().get(1).units()).extracting(placed -> placed.unit().value())
                .containsExactly("ABC");
    }

    @Test
    void digitsAreSetInOneEmAndALatinRunIsTurnedWithItsInkRunningDownTheColumn() throws Exception {
        OcrRegion digits = singleColumn("digits", "價格", 20, 20, 34, 100);
        OcrRegion latin = singleColumn("latin", "價格", 90, 20, 34, 100);
        BufferedImage original = whiteImage(160, 150);

        Rendered rendered = render(original, cjkFonts(),
                new ImageRegionOverlay(digits, "天12"), new ImageRegionOverlay(latin, "天地人物ABC"));
        diagnostic("digits-and-latin", rendered.output());

        assertThat(rendered.result().renderedBlockCount()).isEqualTo(2);
        int[] twelve = inkBounds(rendered.output(), 18, 18, 38, 104);
        int[] abc = inkBounds(rendered.output(), 88, 18, 38, 104);
        assertThat(twelve[2]).as("digits stay inside the column").isLessThanOrEqualTo(34);
        assertThat(abc[2]).as("a turned run stays inside the column").isLessThanOrEqualTo(34);
        // The letters are turned, so the glyph and the run stand one over the other down the column.
        assertThat(abc[3]).as("ink %sx%s", abc[2], abc[3]).isGreaterThan(abc[2]);
    }

    // --- fitting -------------------------------------------------------------------------------

    @Test
    void textThatCannotFitIsKeptAsItWas() throws Exception {
        BufferedImage original = whiteImage(100, 100);
        var graphics = original.createGraphics();
        graphics.setColor(java.awt.Color.RED);
        graphics.fillRect(30, 30, 24, 30);
        graphics.dispose();
        OcrRegion cell = vertical("tiny", "字", rectangle(30, 30, 24, 30),
                column("字", 30, 30, 24, 24, 30), "tiny", false);

        Rendered rendered = render(original, cjkFonts(),
                new ImageRegionOverlay(cell, "這是一段完全不可能放進這個小格子裡的很長的翻譯"));

        assertThat(rendered.result().decisions()).extracting(OverlayRenderDecision::reason)
                .containsExactly("text-fit");
        assertThat(rendered.result().renderedBlockCount()).isZero();
        assertThat(differing(original, rendered.output())).isZero();
    }

    @Test
    void aGroupOfVerticalCellsSharesOneSize() throws Exception {
        BufferedImage original = whiteImage(140, 220);
        OcrRegion few = grouped("few", "鬱鬱", 20, "menu");
        OcrRegion many = grouped("many", "鬱".repeat(12), 80, "menu");

        Rendered alone = render(original, cjkFonts(), new ImageRegionOverlay(
                grouped("alone", "鬱鬱", 20, "solo"), "鬱鬱"));
        Rendered together = render(original, cjkFonts(),
                new ImageRegionOverlay(few, "鬱鬱"), new ImageRegionOverlay(many, "鬱".repeat(12)));
        diagnostic("group", together.output());

        int aloneWidth = inkBounds(alone.output(), 18, 18, 38, 174)[2];
        int fewWidth = inkBounds(together.output(), 18, 18, 38, 174)[2];
        int manyWidth = inkBounds(together.output(), 78, 18, 38, 174)[2];
        assertThat(together.result().renderedBlockCount()).isEqualTo(2);
        assertThat(aloneWidth).as("by itself the short text takes the largest size").isGreaterThan(manyWidth + 5);
        assertThat(fewWidth).as("in a group it takes the size the long text fits at").isCloseTo(manyWidth,
                org.assertj.core.data.Offset.offset(3));
    }

    @Test
    void aHostWhoseFontLacksTheVerticalFormsPreservesTheSource() throws Exception {
        // Covers the text as written but not the vertical presentation forms that would be drawn: the
        // gate has to look at the forms, or the punctuation would come out as missing-glyph boxes.
        ImageTranslationFontProvider noForms = new ImageTranslationFontProvider(Set.of("dejavu sans")) {
            @Override
            public Optional<Font> fontFor(String value, float size, int style) {
                boolean forms = value.codePoints().anyMatch(point -> point >= 0xFE10 && point <= 0xFE4F);
                return forms ? Optional.empty() : Optional.of(new Font("Dialog", style, Math.round(size)));
            }
        };
        BufferedImage original = whiteImage(100, 160);
        OcrRegion cell = singleColumn("forms", "菜單", 30, 20, 34, 100);

        Rendered rendered = render(original, noForms, new ImageRegionOverlay(cell, "天地。"));

        assertThat(rendered.result().decisions()).extracting(OverlayRenderDecision::reason)
                .containsExactly("font-coverage");
        assertThat(differing(original, rendered.output())).isZero();
        // The same text in a horizontal cell is not gated on the forms, which it never draws.
        OcrRegion row = new OcrRegion("row", "菜單", rectangle(10, 20, 80, 34), List.of(), .99f, true,
                OcrBlockType.TEXT, List.of(), 0);
        assertThat(render(original, noForms, new ImageRegionOverlay(row, "天地。")).result().decisions())
                .extracting(OverlayRenderDecision::reason).containsExactly("rendered");
    }

    @Test
    void condensingNarrowsTheGlyphsAlongTheColumnBeforeTheSizeShrinks() {
        Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        Font font = new Font("Dialog", Font.PLAIN, 12);
        // One column of 100px, too thin for a second: plain, 12px is the largest size for eight
        // glyphs; condensed to 0.8, 15px fits, and that is preferred to a smaller plain size.
        VerticalTextLayout.Layout layout = VerticalTextLayout.fit(graphics, "一二三四五六七八",
                List.of(new Cell(0, 0, 30, 102)), font, 38, false);
        graphics.dispose();

        assertThat(layout.fits()).isTrue();
        assertThat(layout.condense()).isEqualTo(.8);
        assertThat(layout.size()).isEqualTo(15);
        assertThat(layout.columns()).hasSize(1);
    }

    @Test
    void anyLayoutThatFitsPlacesEveryCharacterOnceInsideItsCells() {
        Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        Font font = new Font("Dialog", Font.PLAIN, 12);
        String alphabet = "天地人物飯麵「」、。！？…ー〜ABCxyz0123456789 \n（）";
        java.util.Random random = new java.util.Random(7);
        int fitted = 0;
        for (int round = 0; round < 400; round++) {
            StringBuilder text = new StringBuilder();
            for (int index = 0, length = 1 + random.nextInt(40); index < length; index++) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String value = text.toString();
            List<Cell> cells = new java.util.ArrayList<>();
            for (int cell = 0, count = 1 + random.nextInt(3); cell < count; cell++) {
                cells.add(new Cell(random.nextInt(20), random.nextInt(20), 20 + random.nextInt(120), 20 + random.nextInt(200)));
            }

            VerticalTextLayout.Layout layout = VerticalTextLayout.fit(graphics, value, cells, font,
                    8 + random.nextInt(60), random.nextInt(4) == 0);

            if (!layout.fits()) continue;
            fitted++;
            String placed = layout.columns().stream().flatMap(column -> column.units().stream())
                    .filter(item -> item.unit().kind() != Kind.SPACE && item.unit().kind() != Kind.COLUMN_BREAK)
                    .map(item -> item.unit().value()).reduce("", String::concat);
            String expected = VerticalTextLayout.mapped(value).replaceAll("\\s+", "");
            assertThat(placed).as("text %s", value).isEqualTo(expected);
            for (VerticalTextLayout.Column column : layout.columns()) {
                Cell cell = cells.get(column.cell());
                int available = cell.width() - 2;
                int capacity = available < layout.size() ? 0
                        : (available - layout.size()) / VerticalTextLayout.pitch(layout.size()) + 1;
                assertThat(column.index()).as("column index in %s", value).isLessThan(capacity);
                assertThat(column.length()).as("column length in %s", value).isLessThanOrEqualTo(cell.height() - 2 + 1e-6);
                column.units().forEach(item -> assertThat(item.offset()).isBetween(0d, cell.height() - 2d));
            }
            assertThat(layout.columns().stream().map(column -> column.cell() + ":" + column.index()).distinct())
                    .as("no two columns share a slot in %s", value).hasSameSizeAs(layout.columns());
        }
        graphics.dispose();
        assertThat(fitted).as("the generator produces layouts that fit").isGreaterThan(50);
    }

    /** A 34x170 column of {@code text} at {@code x}, in the named group. */
    private static OcrRegion grouped(String id, String text, int x, String group) {
        int count = text.codePointCount(0, text.length());
        return vertical(id, text, rectangle(x, 25, 34, 170),
                column(text, x, 25, 34, Math.max(8, 170 / count), Math.max(8, 170 / count)), group, false);
    }

    @Test
    void theSizeIsCappedByTheThicknessOfTheColumnNotItsLength() {
        Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        Font font = new Font("Dialog", Font.PLAIN, 12);
        VerticalTextLayout.Layout layout = VerticalTextLayout.fit(graphics, "天",
                List.of(new Cell(0, 0, 34, 400)), font, 128, false);
        graphics.dispose();

        assertThat(layout.size()).as("cell width less the pixel of slack").isEqualTo(32);
    }
}
