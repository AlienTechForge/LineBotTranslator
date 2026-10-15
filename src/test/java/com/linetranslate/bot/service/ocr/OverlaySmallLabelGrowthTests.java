package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * A one-line label whose cell is about ten pixels tall cannot hold even the smallest permitted type:
 * the line box of an 8px face (ascent plus descent) is 10px for Latin and more for CJK, and the
 * label was kept in its source language ("text-fit"). Production logged eleven such cells on one
 * poster, next to bullets that were translated. When the space under the cell is empty the label
 * may take it, so the line box fits; when anything else lives there it must not.
 */
class OverlaySmallLabelGrowthTests {

    private static final int IMAGE_WIDTH = 200;
    private static final int IMAGE_HEIGHT = 140;
    private static final int CELL_X = 20;
    private static final int CELL_Y = 20;
    /** 57x9 once the 2px insets are taken: the cell of the production log. */
    private static final int CELL_WIDTH = 61;
    private static final int CELL_HEIGHT = 13;
    private static final ImageTranslationProperties PROPERTIES =
            new ImageTranslationProperties(1000, 400, 100000, .6f, true, .8, .9);
    private static final Color INK = new Color(30, 30, 30);

    @Test
    void labelTooShortForItsLineBoxTakesTheEmptySpaceUnderIt() throws Exception {
        BufferedImage original = page();
        drawSource(original, CELL_Y);

        RenderedImage result = render(original, List.of(label("label", CELL_Y, "ABCD")),
                new ImageTranslationFontProvider(Set.of("dejavu sans")));

        assertThat(result.decisions()).as("decisions=%s", result.decisions()).contains(
                new OverlayRenderDecision("label", OverlayRenderStatus.RENDERED, "rendered"));
        assertThat(result.renderedBlockCount()).isEqualTo(1);
        BufferedImage output = decoded(result);
        int below = inkRowsBelow(output, CELL_Y + CELL_HEIGHT);
        assertThat(below).as("the line box reaches into the space under the cell").isPositive();
        assertThat(below).as("but never further than one line height").isLessThanOrEqualTo(CELL_HEIGHT);
    }

    @Test
    void growthStaysInsideTheEnlargedCellAndLeavesEveryOtherPixelAlone() throws Exception {
        BufferedImage original = page();
        drawSource(original, CELL_Y);

        RenderedImage result = render(original, List.of(label("label", CELL_Y, "ABCD")),
                new ImageTranslationFontProvider(Set.of("dejavu sans")));

        BufferedImage output = decoded(result);
        Rectangle enlarged = new Rectangle(
                CELL_X, CELL_Y, CELL_WIDTH, CELL_HEIGHT + CELL_HEIGHT);
        int outside = 0;
        for (int y = 0; y < IMAGE_HEIGHT; y++) {
            for (int x = 0; x < IMAGE_WIDTH; x++) {
                if (!enlarged.contains(x, y) && original.getRGB(x, y) != output.getRGB(x, y)) outside++;
            }
        }
        assertThat(outside).as("pixels changed outside the cell and the one line height under it").isZero();
    }

    @Test
    void labelThatAlreadyFitsDoesNotGrow() throws Exception {
        BufferedImage original = page();
        drawSource(original, CELL_Y);
        // Same width, but 40px tall: one line fits comfortably without leaving the region.
        OcrRegion tall = region("label", CELL_Y, 40, true);

        RenderedImage result = render(original, List.of(new ImageRegionOverlay(tall, "ABCD")),
                new ImageTranslationFontProvider(Set.of("dejavu sans")));

        assertThat(result.renderedBlockCount()).isEqualTo(1);
        assertThat(inkRowsBelow(decoded(result), CELL_Y + 40))
                .as("a cell that fits keeps its own bounds").isZero();
    }

    @Test
    void labelStaysInItsSourceLanguageWhenAnotherTranslatedRegionSitsRightUnderIt() throws Exception {
        BufferedImage original = page();
        int secondY = CELL_Y + CELL_HEIGHT + 1;
        drawSource(original, CELL_Y);
        drawSource(original, secondY);

        RenderedImage result = render(original,
                List.of(label("upper", CELL_Y, "ABCD"), label("lower", secondY, "EFGH")),
                new ImageTranslationFontProvider(Set.of("dejavu sans")));

        assertThat(result.decisions())
                .contains(new OverlayRenderDecision("upper", OverlayRenderStatus.PRESERVED, "text-fit"))
                .contains(new OverlayRenderDecision("lower", OverlayRenderStatus.RENDERED, "rendered"));
        BufferedImage output = decoded(result);
        for (int y = CELL_Y; y < CELL_Y + CELL_HEIGHT; y++) {
            for (int x = CELL_X; x < CELL_X + CELL_WIDTH; x++) {
                assertThat(output.getRGB(x, y)).as("source pixel %s,%s of the kept label", x, y)
                        .isEqualTo(original.getRGB(x, y));
            }
        }
    }

    @Test
    void labelStaysInItsSourceLanguageWhenSomethingOtherThanBackgroundIsUnderIt() throws Exception {
        BufferedImage original = page();
        drawSource(original, CELL_Y);
        // A rule, an icon or a line of untranslated text two pixels under the cell.
        Graphics2D graphics = original.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(CELL_X, CELL_Y + CELL_HEIGHT + 2, CELL_WIDTH, 3);
        } finally {
            graphics.dispose();
        }

        RenderedImage result = render(original, List.of(label("label", CELL_Y, "ABCD")),
                new ImageTranslationFontProvider(Set.of("dejavu sans")));

        assertThat(result.decisions()).containsExactly(
                new OverlayRenderDecision("label", OverlayRenderStatus.PRESERVED, "text-fit"));
        BufferedImage output = decoded(result);
        for (int y = 0; y < IMAGE_HEIGHT; y++) {
            for (int x = 0; x < IMAGE_WIDTH; x++) {
                assertThat(output.getRGB(x, y)).as("pixel %s,%s", x, y).isEqualTo(original.getRGB(x, y));
            }
        }
    }

    /** The rows follow the cell's own frame, so a line turned by a few degrees grows along its tilt. */
    @Test
    void lightlyTurnedLabelGrowsInItsOwnFrameAndLeavesTheRestOfTheImageAlone() throws Exception {
        BufferedImage original = page();
        drawSource(original, CELL_Y);
        // About five degrees clockwise: still a horizontal line to the OCR orientation rule.
        List<OcrPoint> polygon = List.of(new OcrPoint(20, 20), new OcrPoint(81, 25),
                new OcrPoint(80, 38), new OcrPoint(19, 33));
        OcrRegion turned = new OcrRegion("turned", "nguon", polygon,
                List.of(new OcrWord("nguon", polygon, .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0, "turned", true);

        RenderedImage result = render(original, List.of(new ImageRegionOverlay(turned, "ABCD")),
                new ImageTranslationFontProvider(Set.of("dejavu sans")));

        assertThat(result.decisions()).as("decisions=%s", result.decisions()).contains(
                new OverlayRenderDecision("turned", OverlayRenderStatus.RENDERED, "rendered"));
        BufferedImage output = decoded(result);
        Rectangle reach = new Rectangle(18, 19, 66, 38 + CELL_HEIGHT - 19);
        int outside = 0;
        for (int y = 0; y < IMAGE_HEIGHT; y++) {
            for (int x = 0; x < IMAGE_WIDTH; x++) {
                if (!reach.contains(x, y) && original.getRGB(x, y) != output.getRGB(x, y)) outside++;
            }
        }
        assertThat(outside).as("pixels changed far from the cell and the band under it").isZero();
    }

    @Test
    void labelAtTheBottomEdgeOfTheImageHasNothingToGrowInto() throws Exception {
        BufferedImage original = new BufferedImage(IMAGE_WIDTH, CELL_Y + CELL_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = original.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, original.getWidth(), original.getHeight());
        } finally {
            graphics.dispose();
        }
        drawSource(original, CELL_Y);

        RenderedImage result = render(original, List.of(label("label", CELL_Y, "ABCD")),
                new ImageTranslationFontProvider(Set.of("dejavu sans")));

        assertThat(result.decisions()).containsExactly(
                new OverlayRenderDecision("label", OverlayRenderStatus.PRESERVED, "text-fit"));
    }

    /**
     * The production cell: four CJK glyphs in 57x9. Noto Sans CJK has a line box of about 1.45 em,
     * so it needs even more than a Latin face. Skipped where the environment has no such face.
     */
    @Test
    void fourCjkGlyphsInAFiftySevenByNineCellTakeTheEmptySpaceUnderThem() throws Exception {
        String text = "越南語訳";
        ImageTranslationFontProvider provider = new ImageTranslationFontProvider(Set.of("noto sans cjk tc"));
        Assumptions.assumeTrue(provider.fontFor(text, 12, Font.PLAIN).isPresent(),
                "no CJK-capable font in this environment");
        BufferedImage original = page();
        drawSource(original, CELL_Y);

        RenderedImage result = render(original, List.of(label("label", CELL_Y, text)), provider);

        assertThat(result.decisions()).as("decisions=%s", result.decisions()).contains(
                new OverlayRenderDecision("label", OverlayRenderStatus.RENDERED, "rendered"));
        assertThat(inkRowsBelow(decoded(result), CELL_Y + CELL_HEIGHT)).isPositive();
    }

    private static ImageRegionOverlay label(String id, int y, String replacement) {
        return new ImageRegionOverlay(region(id, y, CELL_HEIGHT, true), replacement);
    }

    private static OcrRegion region(String id, int y, int height, boolean compact) {
        List<OcrPoint> polygon = List.of(
                new OcrPoint(CELL_X, y), new OcrPoint(CELL_X + CELL_WIDTH, y),
                new OcrPoint(CELL_X + CELL_WIDTH, y + height), new OcrPoint(CELL_X, y + height));
        return new OcrRegion(id, "nguon", polygon,
                List.of(new OcrWord("nguon", polygon, .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0, id, compact);
    }

    private static RenderedImage render(
            BufferedImage original, List<ImageRegionOverlay> overlays, ImageTranslationFontProvider fonts)
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                overlays, original.getWidth(), original.getHeight(), PROPERTIES);
        assertThat(plan.safe()).as("plan=%s", plan).isTrue();
        assertThat(plan.overlays()).as("every region is approved by the policy").hasSameSizeAs(overlays);
        return new ImageTranslationOverlayRenderer(fonts).render(source, plan);
    }

    private static BufferedImage page() {
        BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /** Stands in for the source text of a cell: a dark bar across most of it. */
    private static void drawSource(BufferedImage image, int y) {
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(INK);
            graphics.fillRect(CELL_X + 4, y + 3, CELL_WIDTH - 8, 6);
        } finally {
            graphics.dispose();
        }
    }

    private static BufferedImage decoded(RenderedImage result) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(result.pngBytes()));
    }

    /** Number of rows from {@code fromY} down that hold dark ink inside the cell's columns. */
    private static int inkRowsBelow(BufferedImage image, int fromY) {
        int rows = 0;
        for (int y = fromY; y < image.getHeight(); y++) {
            boolean ink = false;
            for (int x = CELL_X; x < CELL_X + CELL_WIDTH && !ink; x++) {
                Color colour = new Color(image.getRGB(x, y));
                ink = colour.getRed() < 128 && colour.getGreen() < 128 && colour.getBlue() < 128;
            }
            if (ink) rows++;
        }
        return rows;
    }
}
