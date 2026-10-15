package com.linetranslate.bot.service.ocr;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;

/**
 * Geometry and pixels for the tests of vertical writing: columns of single-glyph words as Vision
 * reads a vertical menu, a renderer that can set CJK text, and a probe for the ink it leaves.
 */
final class VerticalFixtures {

    static final ImageTranslationProperties PROPERTIES =
            new ImageTranslationProperties(1000, 400, 100000, .6f, true, .8, .9);

    private VerticalFixtures() {
    }

    /**
     * A provider that names Noto Sans CJK, as the deployment image has. Where the machine has no such
     * family Java substitutes its logical font, which covers CJK through the system's fallback faces,
     * so the layout and the pixels are tested here with a stand-in face and with the real one in CI.
     */
    static ImageTranslationFontProvider cjkFonts() {
        ImageTranslationFontProvider provider = new ImageTranslationFontProvider(Set.of("noto sans cjk tc"));
        Assumptions.assumeTrue(provider.fontFor("一二三「」︑︒︵︶ー", 12, Font.PLAIN).isPresent(),
                "no CJK-capable font in this environment");
        return provider;
    }

    static List<OcrPoint> rectangle(int x, int y, int width, int height) {
        return List.of(new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height));
    }

    /** One word per glyph, stacked from {@code top} at {@code pitch}, each {@code width} wide and {@code glyph} tall. */
    static List<OcrWord> column(String text, int x, int top, int width, int glyph, int pitch) {
        List<OcrWord> words = new ArrayList<>();
        int[] codePoints = text.codePoints().toArray();
        for (int index = 0; index < codePoints.length; index++) {
            words.add(new OcrWord(new String(Character.toChars(codePoints[index])),
                    rectangle(x, top + index * pitch, width, glyph), .98f, true));
        }
        return words;
    }

    /** A region of vertical writing over {@code words}, one cell. */
    static OcrRegion vertical(String id, String text, List<OcrPoint> polygon, List<OcrWord> words,
            String groupId, boolean compactLabel) {
        return new OcrRegion(id, text, polygon, words, .99f, true, OcrBlockType.TEXT, List.of(), 0,
                groupId, compactLabel, WritingMode.VERTICAL_RL, List.of());
    }

    /** A single column of {@code text}, {@code glyph} wide and tall, in a cell that wraps it exactly. */
    static OcrRegion singleColumn(String id, String text, int x, int y, int glyph, int height) {
        int count = text.codePointCount(0, text.length());
        return vertical(id, text, rectangle(x, y, glyph, height),
                column(text, x, y, glyph, glyph, Math.max(glyph, height / count)), id, false);
    }

    static BufferedImage whiteImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    static ValidatedImage validated(BufferedImage image) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return new ImageInputValidator(ImageTranslationProperties.defaults()).validate(bytes.toByteArray(), "image/png");
    }

    static OverlaySafetyPlan plan(int width, int height, ImageRegionOverlay... overlays) {
        return new OverlaySafetyPolicy().evaluate(List.of(overlays), width, height, PROPERTIES);
    }

    /** Renders the overlays on {@code original} and returns the decoded output. */
    record Rendered(RenderedImage result, BufferedImage output) {
    }

    static Rendered render(BufferedImage original, ImageTranslationFontProvider fonts,
            ImageRegionOverlay... overlays) throws Exception {
        RenderedImage rendered = new ImageTranslationOverlayRenderer(fonts)
                .render(validated(original), plan(original.getWidth(), original.getHeight(), overlays));
        return new Rendered(rendered, ImageIO.read(new ByteArrayInputStream(rendered.pngBytes())));
    }

    /** Writes a diagnostic picture under {@code target/vertical-layout}, as the replay tests do. */
    static void diagnostic(String name, BufferedImage image) throws Exception {
        Path output = Path.of("target", "vertical-layout", name + ".png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }

    /** Left, top, width and height of the dark pixels inside a rectangle, or zeros when there are none. */
    static int[] inkBounds(BufferedImage image, int x, int y, int width, int height) {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                if (dark(image, column, row)) {
                    left = Math.min(left, column);
                    right = Math.max(right, column);
                    top = Math.min(top, row);
                    bottom = Math.max(bottom, row);
                }
            }
        }
        return right < left
                ? new int[] {0, 0, 0, 0}
                : new int[] {left, top, right - left + 1, bottom - top + 1};
    }

    static int inkCount(BufferedImage image, int x, int y, int width, int height) {
        int count = 0;
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                if (dark(image, column, row)) count++;
            }
        }
        return count;
    }

    static boolean dark(BufferedImage image, int x, int y) {
        Color colour = new Color(image.getRGB(x, y));
        return colour.getRed() < 128 && colour.getGreen() < 128 && colour.getBlue() < 128;
    }

    /** The number of pixels that differ between two images of the same size. */
    static int differing(BufferedImage left, BufferedImage right) {
        int count = 0;
        for (int y = 0; y < left.getHeight(); y++) {
            for (int x = 0; x < left.getWidth(); x++) {
                if (left.getRGB(x, y) != right.getRGB(x, y)) count++;
            }
        }
        return count;
    }
}
