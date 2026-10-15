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

    /**
     * A coloured infographic is not a document (a fifth of its pixels are colourful) and not a
     * photo: its many reliable lines sit on flat fills, so a flat mask over one is invisible.
     */
    @Test
    void recognizesManyReliableHorizontalLinesOnFlatBackgroundsAsAPoster() {
        assertThat(classifier.classify(SyntheticPoster.posterImage(), SyntheticPoster.regions()))
                .isEqualTo(OverlayContentMode.POSTER);
    }

    @Test
    void keepsPhotoWithTheSameManyLinesInGeneralMode() {
        assertThat(classifier.classify(SyntheticPoster.photoImage(), SyntheticPoster.regions()))
                .isEqualTo(OverlayContentMode.GENERAL);
    }

    @Test
    void aPosterNeedsAtLeastTenTextRegions() {
        List<OcrRegion> caption = SyntheticPoster.regions().subList(0, 9);

        assertThat(classifier.classify(SyntheticPoster.posterImage(), caption))
                .as("nine lines on flat fills are a sign or a caption, which a photo can have too")
                .isEqualTo(OverlayContentMode.GENERAL);
        assertThat(classifier.classify(SyntheticPoster.posterImage(), SyntheticPoster.regions().subList(0, 10)))
                .isEqualTo(OverlayContentMode.POSTER);
    }

    @Test
    void aPosterNeedsNinetyPercentReliableRegions() {
        List<OcrRegion> regions = SyntheticPoster.regions();
        // A region is reliable from a confidence of 0.80; documents need 80% of them reliable.
        assertThat(classifier.classify(SyntheticPoster.posterImage(), withConfidence(regions, 6, .7f)))
                .as("84% reliable passes the shared gate but not the poster one")
                .isEqualTo(OverlayContentMode.GENERAL);
        assertThat(classifier.classify(SyntheticPoster.posterImage(), withConfidence(regions, 3, .7f)))
                .as("92% reliable")
                .isEqualTo(OverlayContentMode.POSTER);
    }

    @Test
    void aPosterNeedsMostOfItsRegionsOnFlatBackgrounds() {
        List<OcrRegion> regions = SyntheticPoster.regions();
        BufferedImage halfNoisy = SyntheticPoster.posterImage();
        addNoiseFromLine(halfNoisy, 19);
        BufferedImage mostlyFlat = SyntheticPoster.posterImage();
        addNoiseFromLine(mostlyFlat, 36);

        assertThat(classifier.classify(halfNoisy, regions))
                .as("19 of 38 regions on noise").isEqualTo(OverlayContentMode.GENERAL);
        assertThat(classifier.classify(mostlyFlat, regions))
                .as("the last 2 of 38 on noise, the rest still flat enough").isEqualTo(OverlayContentMode.POSTER);
    }

    /** Replaces everything from the top of line {@code index} down with random colour. */
    private static void addNoiseFromLine(BufferedImage image, int index) {
        java.util.Random random = new java.util.Random(11);
        for (int y = SyntheticPoster.top(index) - 12; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, new Color(random.nextInt(256), random.nextInt(256),
                        random.nextInt(256)).getRGB());
            }
        }
    }

    @Test
    void aWhitePageWithManyLinesIsStillADocument() {
        BufferedImage image = new BufferedImage(
                SyntheticPoster.WIDTH, SyntheticPoster.HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();

        assertThat(classifier.classify(image, SyntheticPoster.regions()))
                .isEqualTo(OverlayContentMode.DOCUMENT);
    }

    private static List<OcrRegion> withConfidence(List<OcrRegion> regions, int count, float confidence) {
        List<OcrRegion> result = new java.util.ArrayList<>();
        for (int index = 0; index < regions.size(); index++) {
            OcrRegion region = regions.get(index);
            result.add(index >= count ? region : new OcrRegion(region.id(), region.text(),
                    region.polygon(), region.words(), confidence, true, region.blockType(),
                    region.languages(), region.readingOrder(), region.groupId(), region.compactLabel()));
        }
        return result;
    }

    private static OcrRegion region(String id, int x, int y, int width, int height) {
        return new OcrRegion(id, "text", List.of(
                new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height)),
                List.of(), .98f, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage("ja", .98f)), 0);
    }
}
