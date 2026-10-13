package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

class ImageTranslationStyleEstimatorTests {
    @Test
    void infersDominantBackgroundTextColourBoldWeightAndSourceSize() {
        Color purple = new Color(92, 72, 220);
        BufferedImage image = new BufferedImage(180, 80, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(purple);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 32));
            graphics.drawString("SLEEP", 20, 52);
        } finally {
            graphics.dispose();
        }
        OcrRegion region = new OcrRegion("style", "SLEEP", rectangle(16, 14, 114, 48),
                List.of(new OcrWord("SLEEP", rectangle(16, 14, 114, 48), .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0);

        ImageTranslationTextStyle style = ImageTranslationStyleEstimator.estimate(image, region);

        assertThat(style.background()).isEqualTo(Color.WHITE);
        assertThat(style.foreground().getRed()).isBetween(purple.getRed() - 12, purple.getRed() + 12);
        assertThat(style.foreground().getGreen()).isBetween(purple.getGreen() - 12, purple.getGreen() + 12);
        assertThat(style.foreground().getBlue()).isBetween(purple.getBlue() - 12, purple.getBlue() + 12);
        assertThat(style.fontStyle()).isEqualTo(Font.BOLD);
        assertThat(style.maximumFontSize()).isBetween(48, 54);
    }

    @Test
    void paragraphWithManyGlyphsIsSampledWithoutQuadraticMaskCost() {
        // Testing every pixel against the union of all glyph polygons took about 20 s here;
        // rasterising each polygon within its own bounds takes well under one second.
        BufferedImage image = new BufferedImage(760, 560, BufferedImage.TYPE_INT_RGB);
        List<OcrWord> words = new java.util.ArrayList<>();
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(new Color(30, 30, 30));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
            for (int line = 0; line < 20; line++) {
                int y = 10 + line * 27;
                graphics.drawString("Lorem ipsum dolor sit amet, consectetur adipiscing", 10, y + 20);
                List<OcrSymbol> symbols = new java.util.ArrayList<>();
                for (int index = 0; index < 30; index++) {
                    symbols.add(new OcrSymbol("x", rectangle(10 + index * 24, y, 22, 24), .99f, true));
                }
                words.add(new OcrWord("line", rectangle(10, y, 718, 24), .99f, true, symbols));
            }
        } finally {
            graphics.dispose();
        }
        OcrRegion region = new OcrRegion("paragraph", "Lorem ipsum", rectangle(8, 8, 722, 544),
                words, .99f, true, OcrBlockType.TEXT, List.of(), 0);

        long started = System.nanoTime();
        ImageTranslationTextStyle style = ImageTranslationStyleEstimator.estimate(image, region);
        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);

        assertThat(style.background()).isEqualTo(Color.WHITE);
        assertThat(elapsedMillis).as("600 glyph polygons in one paragraph").isLessThan(3_000);
    }

    private static List<OcrPoint> rectangle(int x, int y, int width, int height) {
        return List.of(new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height));
    }
}
