package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * A line is only as good as the cell it is drawn in. The wrap puts a glyph on a line of its own
 * however wide it is, so a size chosen from the block height alone can draw ink that runs past the
 * cell and is cut off by the region mask. The fit therefore has to hold every line to the width too.
 */
class OverlayCellWidthFitTests {

    private static final int CELL_X = 20;
    private static final int CELL_Y = 20;
    private static final int CELL_HEIGHT = 150;
    private static final int IMAGE_HEIGHT = 190;
    private static final ImageTranslationProperties PROPERTIES =
            new ImageTranslationProperties(1000, 400, 100000, .6f, true, .8, .9);

    /** The cell leaves 1px for text once insets and padding are taken, so not even a digit fits. */
    @Test
    void cellNarrowerThanOneGlyphKeepsItsSourceInsteadOfDrawingClippedInk() throws Exception {
        BufferedImage original = sourceImage(7);

        RenderedImage result = render(original, 7, "100");

        assertThat(result.renderedBlockCount()).as("decisions=%s", result.decisions()).isZero();
        assertThat(result.decisions()).containsExactly(
                new OverlayRenderDecision("price", OverlayRenderStatus.PRESERVED, "text-fit"));
        assertThat(result.degradation().count(OverlayDegradationReason.TEXT_FIT)).isEqualTo(1);
        assertThat(pixelsDiffering(original, decoded(result))).isZero();
    }

    /** Wide enough for a glyph, but not at the size the block height alone would allow. */
    @Test
    void columnAboutOneGlyphWideShrinksTheTypeUntilEveryLineFitsTheCell() throws Exception {
        int cellWidth = 24;
        BufferedImage original = sourceImage(cellWidth);

        RenderedImage result = render(original, cellWidth, "100");

        assertThat(result.renderedBlockCount()).as("decisions=%s", result.decisions()).isEqualTo(1);
        int[] ink = inkColumns(decoded(result), cellWidth);
        assertThat(ink[0]).as("the replacement must draw something").isNotNegative();
        // Lines start 3px in (2px inset, 1px padding) and end 3px short of the far edge. Ink on the
        // last columns of the cell is a glyph the mask cut off.
        assertThat(ink[1] - CELL_X)
                .as("ink runs to column %s of a %s px cell: the glyphs were clipped",
                        ink[1] - CELL_X, cellWidth)
                .isLessThanOrEqualTo(cellWidth - 3);
    }

    private static RenderedImage render(BufferedImage original, int cellWidth, String replacement)
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        List<OcrPoint> polygon = List.of(
                new OcrPoint(CELL_X, CELL_Y), new OcrPoint(CELL_X + cellWidth, CELL_Y),
                new OcrPoint(CELL_X + cellWidth, CELL_Y + CELL_HEIGHT),
                new OcrPoint(CELL_X, CELL_Y + CELL_HEIGHT));
        OcrRegion cell = new OcrRegion("price", "100", polygon,
                List.of(new OcrWord("100", polygon, .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0, "price", false);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(cell, replacement)),
                original.getWidth(), original.getHeight(), PROPERTIES);
        assertThat(plan.safe()).as("plan=%s", plan).isTrue();

        return new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);
    }

    /** White page with a dark bar down the middle of the cell standing in for the source text. */
    private static BufferedImage sourceImage(int cellWidth) {
        BufferedImage image = new BufferedImage(
                CELL_X * 2 + cellWidth, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.fillRect(CELL_X + cellWidth / 2 - 1, CELL_Y + 10, 2, CELL_HEIGHT - 20);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static BufferedImage decoded(RenderedImage result) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(result.pngBytes()));
    }

    private static int pixelsDiffering(BufferedImage left, BufferedImage right) {
        int differing = 0;
        for (int y = 0; y < left.getHeight(); y++) {
            for (int x = 0; x < left.getWidth(); x++) {
                if (left.getRGB(x, y) != right.getRGB(x, y)) differing++;
            }
        }
        return differing;
    }

    /** Leftmost and rightmost columns holding dark ink inside the cell, or {-1, -1} when none. */
    private static int[] inkColumns(BufferedImage image, int cellWidth) {
        int left = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        for (int y = CELL_Y; y < CELL_Y + CELL_HEIGHT; y++) {
            for (int x = CELL_X; x < CELL_X + cellWidth; x++) {
                Color colour = new Color(image.getRGB(x, y));
                if (colour.getRed() < 128 && colour.getGreen() < 128 && colour.getBlue() < 128) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
        }
        return right < left ? new int[] {-1, -1} : new int[] {left, right};
    }
}
