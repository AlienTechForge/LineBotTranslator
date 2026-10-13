package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * A translation too wide for its cell can be narrowed or shrunk. Narrowing keeps the glyph height
 * that shrinking gives up, so at every size each condensing step is tried before the size drops.
 */
class OverlayCondenseOrderTests {

    /**
     * The source glyph box caps the type size at 1.1 times its height, so a 40px word allows 44.
     * The cell is taller than that so one line always fits and the width alone decides the size.
     */
    private static final int CELL_HEIGHT = 80;
    private static final int WORD_HEIGHT = 40;
    private static final int LARGEST_SIZE = 44;
    private static final int MARGIN = 20;
    private static final Color TEXT = new Color(25, 25, 25);

    @Test
    void latinTextThatFitsCondensedKeepsItsSizeInsteadOfShrinking() throws Exception {
        assertLaidOutCondensedAtLargestSize("dejavu sans", "Spicy Pork Belly");
    }

    /**
     * Decision: CJK follows the same order rather than being gated on script. Wrapping breaks CJK
     * per glyph, so a 0.9 width gains as many glyphs per line as it does for Latin, and narrowing
     * keeps the vertical stroke spacing that shrinking takes from dense Han glyphs. Skipped where
     * the environment has no font able to draw the text.
     */
    @Test
    void cjkTextIsAlsoCondensedBeforeItIsShrunk() throws Exception {
        String text = "火辣雞肉起司炒麵";
        Assumptions.assumeTrue(new ImageTranslationFontProvider(Set.of("noto sans cjk tc"))
                        .fontFor(text, 12, Font.PLAIN).isPresent(),
                "no CJK-capable font in this environment");
        assertLaidOutCondensedAtLargestSize("noto sans cjk tc", text);
    }

    private static void assertLaidOutCondensedAtLargestSize(String family, String text)
            throws Exception {
        Font base = new ImageTranslationFontProvider(Set.of(family))
                .fontFor(text, 12, Font.PLAIN).orElseThrow();
        // The renderer wraps at the cell width less a 2px inset and 1px of padding per side. A
        // wrap width one pixel above the 0.9 width fits the largest size only when condensed.
        int wrapWidth = width(base, LARGEST_SIZE, .9d, text) + 1;
        int cellWidth = wrapWidth + 6;
        assertThat(width(base, LARGEST_SIZE, 1d, text))
                .as("precondition: at full width the largest size needs a second line")
                .isGreaterThan(wrapWidth);
        int shrunk = LARGEST_SIZE;
        while (width(base, shrunk, 1d, text) > wrapWidth) shrunk--;

        int[] rendered = render(family, text, cellWidth);
        int[] condensedAtLargest = referenceInk(base, LARGEST_SIZE, .9d, text, cellWidth);
        int[] shrunkAtFullWidth = referenceInk(base, shrunk, 1d, text, cellWidth);

        assertThat(shrunkAtFullWidth[1])
                .as("precondition: size %s at full width is visibly shorter than size %s",
                        shrunk, LARGEST_SIZE)
                .isLessThan(condensedAtLargest[1] - 2);
        assertThat(rendered[1])
                .as("ink height must match size %s condensed, not size %s at full width (%s)",
                        LARGEST_SIZE, shrunk, shrunkAtFullWidth[1])
                .isCloseTo(condensedAtLargest[1], within(1));
        assertThat(rendered[0])
                .as("ink width must match the 0.9 glyph width at size %s", LARGEST_SIZE)
                .isCloseTo(condensedAtLargest[0], within(2));
    }

    /** Renders one prose cell (not a compact label) and returns its ink box as {width, height}. */
    private static int[] render(String family, String text, int cellWidth) throws Exception {
        int imageWidth = cellWidth + MARGIN * 2;
        int imageHeight = CELL_HEIGHT + MARGIN * 2;
        BufferedImage original =
                new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = original.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, imageWidth, imageHeight);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        List<OcrPoint> polygon = List.of(
                new OcrPoint(MARGIN, MARGIN), new OcrPoint(MARGIN + cellWidth, MARGIN),
                new OcrPoint(MARGIN + cellWidth, MARGIN + CELL_HEIGHT),
                new OcrPoint(MARGIN, MARGIN + CELL_HEIGHT));
        List<OcrPoint> word = List.of(
                new OcrPoint(MARGIN, MARGIN), new OcrPoint(MARGIN + cellWidth, MARGIN),
                new OcrPoint(MARGIN + cellWidth, MARGIN + WORD_HEIGHT),
                new OcrPoint(MARGIN, MARGIN + WORD_HEIGHT));
        OcrRegion cell = new OcrRegion("cell", "原文", polygon,
                List.of(new OcrWord("原文", word, .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0, "cell", false);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(cell, text)), imageWidth, imageHeight,
                new ImageTranslationProperties(1000, 400, 100000, .6f, true, .8, .9));

        RenderedImage result = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of(family))).render(source, plan);

        assertThat(result.renderedBlockCount()).as("decisions=%s", result.decisions()).isEqualTo(1);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(result.pngBytes()));
        return inkSize(output, MARGIN, MARGIN, cellWidth, CELL_HEIGHT);
    }

    /** Ink bounding box of the text drawn alone at one size and glyph width, as {width, height}. */
    private static int[] referenceInk(
            Font base, int size, double widthScale, String text, int width) {
        BufferedImage image = new BufferedImage(width, CELL_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = measuring(image);
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, CELL_HEIGHT);
            graphics.setColor(TEXT);
            graphics.setFont(font(base, size, widthScale));
            graphics.drawString(text, 3, 2 + graphics.getFontMetrics().getAscent());
        } finally {
            graphics.dispose();
        }
        return inkSize(image, 0, 0, width, CELL_HEIGHT);
    }

    private static int width(Font base, int size, double widthScale, String text) {
        Graphics2D graphics = measuring(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB));
        try {
            return graphics.getFontMetrics(font(base, size, widthScale)).stringWidth(text);
        } finally {
            graphics.dispose();
        }
    }

    private static Font font(Font base, int size, double widthScale) {
        Font sized = base.deriveFont((float) size);
        return widthScale >= 1d
                ? sized
                : sized.deriveFont(AffineTransform.getScaleInstance(widthScale, 1d));
    }

    /** Same hints as the polygon renderer, so measured widths match the widths it wraps by. */
    private static Graphics2D measuring(BufferedImage image) {
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return graphics;
    }

    private static int[] inkSize(BufferedImage image, int x, int y, int width, int height) {
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                Color colour = new Color(image.getRGB(column, row));
                if (colour.getRed() < 128 && colour.getGreen() < 128 && colour.getBlue() < 128) {
                    left = Math.min(left, column);
                    right = Math.max(right, column);
                    top = Math.min(top, row);
                    bottom = Math.max(bottom, row);
                }
            }
        }
        return right < left ? new int[] {0, 0} : new int[] {right - left + 1, bottom - top + 1};
    }
}
