package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

class OverlayContentClassifierTests {

    private final OverlayContentClassifier classifier = new OverlayContentClassifier();

    @Test
    void recognizesHighConfidenceTextOnUniformDocumentBackground() {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 400, 400);
        graphics.setColor(Color.BLACK);
        graphics.drawString("document", 20, 30);
        graphics.dispose();

        assertThat(classifier.classify(image, List.of(
                region("p1", 10, 10, 380, 100),
                region("p2", 10, 140, 380, 100),
                region("p3", 10, 270, 380, 100))))
                .isEqualTo(OverlayContentMode.DOCUMENT);
    }

    @Test
    void keepsColourfulArtworkInGeneralMode() {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 400; y++) {
            for (int x = 0; x < 400; x++) {
                image.setRGB(x, y, new Color(x % 256, y % 256, (x + y) % 256).getRGB());
            }
        }

        assertThat(classifier.classify(image, List.of(
                region("p1", 10, 10, 380, 100),
                region("p2", 10, 140, 380, 100),
                region("p3", 10, 270, 380, 100))))
                .isEqualTo(OverlayContentMode.GENERAL);
    }

    @Test
    void keepsGrayscalePhotosWithManyTonesInGeneralMode() {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 400; y++) {
            for (int x = 0; x < 400; x++) {
                int tone = (x + y) % 256;
                image.setRGB(x, y, new Color(tone, tone, tone).getRGB());
            }
        }

        assertThat(classifier.classify(image, List.of(
                region("p1", 10, 10, 380, 100),
                region("p2", 10, 140, 380, 100),
                region("p3", 10, 270, 380, 100))))
                .isEqualTo(OverlayContentMode.GENERAL);
    }

    private static OcrRegion region(String id, int x, int y, int width, int height) {
        return new OcrRegion(id, "text", List.of(
                new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height)),
                List.of(), .98f, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage("ja", .98f)), 0);
    }
}
