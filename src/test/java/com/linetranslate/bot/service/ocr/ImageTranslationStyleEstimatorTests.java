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

    private static List<OcrPoint> rectangle(int x, int y, int width, int height) {
        return List.of(new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height));
    }
}
