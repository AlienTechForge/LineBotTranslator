package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class OverlayVerticalCenteringTests {

    /** Ink and metric box centres differ by a fraction of the type size and by integer rounding. */
    private static final double CENTRE_TOLERANCE = 4;

    @Test
    void oneLineTranslationSitsInTheMiddleOfACellThatHeldTwoSourceLines() throws Exception {
        BufferedImage original = whiteImage(260, 130);
        ValidatedImage source = validated(original);
        // Two stacked 20px source lines in a 90px cell; the translation needs a single line.
        List<OcrPoint> cell = rectangle(20, 20, 200, 90);
        OcrRegion region = new OcrRegion("two-line-cell", "染髮 護髮", cell, List.of(
                new OcrWord("染髮", rectangle(30, 38, 60, 20), .99f, true),
                new OcrWord("護髮", rectangle(30, 72, 60, 20), .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(region, "Hair dye")), 260, 130,
                new ImageTranslationProperties(1000, 400, 100000, .6f, true, .8, .9));

        RenderedImage rendered = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);

        assertThat(rendered.renderedBlockCount()).as("decisions=%s", rendered.decisions()).isEqualTo(1);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(rendered.pngBytes()));
        double centroid = inkCentroidY(output, 20, 20, 200, 90);
        assertThat(centroid)
                .as("ink centroid of a one-line translation in a cell spanning y=20..110")
                .isCloseTo(20 + 90 / 2d, org.assertj.core.data.Offset.offset(CENTRE_TOLERANCE));
    }

    @Test
    void legacyBlockRendererAlsoCentresAShortReplacement() throws Exception {
        BufferedImage original = whiteImage(240, 150);
        ValidatedImage source = validated(original);
        ImageOverlayBlock block = new ImageOverlayBlock(
                new OcrService.TextBlock("標籤", 20, 20, 200, 110, .98f), "OK");

        RenderedImage rendered = new ImageTranslationOverlayRenderer().render(source, List.of(block), .6f);

        assertThat(rendered.renderedBlockCount()).isEqualTo(1);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(rendered.pngBytes()));
        assertThat(inkCentroidY(output, 20, 20, 200, 110))
                .as("ink centroid of a one-line replacement in a block spanning y=20..130")
                .isCloseTo(20 + 110 / 2d, org.assertj.core.data.Offset.offset(CENTRE_TOLERANCE));
    }

    @Test
    void overflowingMultiLineBlockStillStartsAtTheTopAndDropsLinesPastTheBottom() throws Exception {
        // Neither public path hands drawHorizontal a block taller than its bounds today: the
        // polygon path preserves the source on text-fit and the legacy path truncates. The guard
        // that keeps such a block from being pushed above the region is therefore checked directly.
        Font font = new Font("DejaVu Sans", Font.PLAIN, 16);
        List<String> lines = List.of("first line", "second line", "third line");
        BufferedImage probe = whiteImage(1, 1);
        Graphics2D probeGraphics = probe.createGraphics();
        FontMetrics metrics;
        try {
            metrics = probeGraphics.getFontMetrics(font);
        } finally {
            probeGraphics.dispose();
        }
        int x = 10;
        int y = 40;
        // Room for exactly one line: the second baseline falls past the bottom edge.
        int height = metrics.getAscent() + metrics.getDescent() + 1;
        int blockHeight = (lines.size() - 1) * metrics.getHeight()
                + metrics.getAscent() + metrics.getDescent();
        assertThat(blockHeight).as("precondition: the block overflows its bounds").isGreaterThan(height);

        BufferedImage drawn = whiteImage(200, 120);
        Graphics2D graphics = drawn.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            drawHorizontal(graphics, x, y, 180, height, font, lines, false);
        } finally {
            graphics.dispose();
        }
        BufferedImage expected = whiteImage(200, 120);
        Graphics2D reference = expected.createGraphics();
        try {
            reference.setColor(Color.BLACK);
            reference.setFont(font);
            reference.drawString(lines.get(0), x + 1, y + metrics.getAscent());
        } finally {
            reference.dispose();
        }

        int[] ink = inkRows(drawn, 0, 0, 200, 120);
        assertThat(ink[0]).as("no ink above the top of the bounds").isGreaterThanOrEqualTo(y);
        assertThat(ink[1]).as("no line drawn past the bottom of the bounds")
                .isLessThanOrEqualTo(y + height - 1);
        for (int row = 0; row < 120; row++) {
            for (int column = 0; column < 200; column++) {
                assertThat(drawn.getRGB(column, row))
                        .as("only the first line, at the uncentred top position (%s,%s)", column, row)
                        .isEqualTo(expected.getRGB(column, row));
            }
        }
    }

    private static void drawHorizontal(
            Graphics2D graphics,
            int x,
            int y,
            int width,
            int height,
            Font font,
            List<String> lines,
            boolean fits) throws ReflectiveOperationException {
        String renderer = ImageTranslationOverlayRenderer.class.getName();
        Class<?> boundsType = Class.forName(renderer + "$Bounds");
        Class<?> layoutType = Class.forName(renderer + "$Layout");
        Constructor<?> bounds = boundsType.getDeclaredConstructor(
                int.class, int.class, int.class, int.class);
        Constructor<?> layout = layoutType.getDeclaredConstructor(Font.class, List.class, boolean.class);
        Method draw = ImageTranslationOverlayRenderer.class.getDeclaredMethod(
                "drawHorizontal", Graphics2D.class, boundsType, layoutType);
        bounds.setAccessible(true);
        layout.setAccessible(true);
        draw.setAccessible(true);
        draw.invoke(null, graphics,
                bounds.newInstance(x, y, width, height), layout.newInstance(font, lines, fits));
    }

    /** Mean row of the dark pixels inside a cell. */
    private static double inkCentroidY(BufferedImage image, int x, int y, int width, int height) {
        long rows = 0;
        long count = 0;
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                if (isInk(image.getRGB(column, row))) {
                    rows += row;
                    count++;
                }
            }
        }
        assertThat(count).as("translated ink inside the cell").isPositive();
        return (double) rows / count;
    }

    /** First and last rows holding dark pixels inside a cell, as {top, bottom}. */
    private static int[] inkRows(BufferedImage image, int x, int y, int width, int height) {
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                if (isInk(image.getRGB(column, row))) {
                    top = Math.min(top, row);
                    bottom = Math.max(bottom, row);
                }
            }
        }
        assertThat(bottom).as("ink present").isGreaterThanOrEqualTo(top);
        return new int[] {top, bottom};
    }

    private static boolean isInk(int rgb) {
        Color colour = new Color(rgb);
        return colour.getRed() < 128 && colour.getGreen() < 128 && colour.getBlue() < 128;
    }

    private static ValidatedImage validated(BufferedImage image) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
    }

    private static BufferedImage whiteImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static List<OcrPoint> rectangle(int x, int y, int width, int height) {
        return List.of(new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height));
    }
}
