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

    /**
     * A page left turned (it could not be told from vertical writing) is one writing-mode family
     * and is as regular as a document.
     */
    @Test
    void aWhitePageOfRegionsTurnedNinetyDegreesIsADocument() {
        BufferedImage image = whitePage();

        assertThat(classifier.classify(image, List.of(
                turned("p1", 300, 10), turned("p2", 260, 10), turned("p3", 220, 10))))
                .isEqualTo(OverlayContentMode.DOCUMENT);
        assertThat(turned("p1", 300, 10).writingMode()).isEqualTo(WritingMode.ROTATED_90);
    }

    @Test
    void uprightVerticalColumnsCountWithTheHorizontalLinesAsOneFamily() {
        BufferedImage image = whitePage();
        List<OcrRegion> regions = new java.util.ArrayList<>();
        for (int index = 0; index < 6; index++) {
            regions.add(withMode(region("v" + index, 10 + index * 60, 10, 40, 300), WritingMode.VERTICAL_RL));
        }
        for (int index = 0; index < 4; index++) {
            regions.add(region("h" + index, 10, 330 + index * 12, 380, 10));
        }

        // 60 percent vertical columns and 40 percent horizontal lines: one upright family.
        assertThat(classifier.classify(image, regions)).isEqualTo(OverlayContentMode.DOCUMENT);
    }

    @Test
    void halfHorizontalHalfTurnedIsNotOneLayout() {
        BufferedImage image = whitePage();
        List<OcrRegion> regions = List.of(
                region("h1", 10, 10, 380, 40), region("h2", 10, 60, 380, 40),
                turned("t1", 300, 110), turned("t2", 260, 110));

        assertThat(classifier.classify(image, regions)).isEqualTo(OverlayContentMode.GENERAL);
        assertThat(regions).extracting(OcrRegion::writingMode).containsExactly(
                WritingMode.HORIZONTAL, WritingMode.HORIZONTAL, WritingMode.ROTATED_90, WritingMode.ROTATED_90);
    }

    @Test
    void turnedAndSkewedRegionsDoNotMakeOneFamilyTogether() {
        BufferedImage image = whitePage();
        List<OcrRegion> regions = List.of(
                turned("t1", 300, 10), turned("t2", 260, 10), turned("t3", 220, 10),
                new OcrRegion("s1", "text", List.of(new OcrPoint(10, 10), new OcrPoint(100, 40),
                        new OcrPoint(90, 70), new OcrPoint(0, 40)), List.of(), .98f, true,
                        OcrBlockType.TEXT, List.of(), 3));

        assertThat(regions.get(3).writingMode()).isEqualTo(WritingMode.SKEWED);
        // Three of four is 75 percent, under the 80 percent a family needs.
        assertThat(classifier.classify(image, regions)).isEqualTo(OverlayContentMode.GENERAL);
    }

    @Test
    void theDominantFamilyNeedsEightyPercentOfTheRegions() {
        BufferedImage image = whitePage();
        List<OcrRegion> fourOfFive = new java.util.ArrayList<>();
        for (int index = 0; index < 4; index++) fourOfFive.add(turned("t" + index, 300 - index * 40, 10));
        fourOfFive.add(region("h", 10, 200, 100, 30));

        assertThat(OverlayContentClassifier.MIN_DOMINANT_MODE_RATIO).isEqualTo(.80);
        assertThat(classifier.classify(image, fourOfFive)).isEqualTo(OverlayContentMode.DOCUMENT);
        fourOfFive.add(region("h2", 10, 240, 100, 30));
        assertThat(classifier.classify(image, fourOfFive)).isEqualTo(OverlayContentMode.GENERAL);
    }

    @Test
    void theDecisionLineNamesTheDominantFamilyAndKeepsTheStrictHorizontalShare() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(OverlayContentClassifier.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            classifier.classify(whitePage(), List.of(
                    turned("t1", 300, 10), turned("t2", 260, 10), turned("t3", 220, 10)));
            classifier.classify(whitePage(), List.of(
                    region("h1", 10, 10, 380, 40), turned("t1", 300, 110), turned("t2", 260, 110)));
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(logs.list).extracting(event -> event.getFormattedMessage()).satisfiesExactly(
                line -> assertThat(line).contains("verdict=document", "horizontalRatio=0.000",
                        "dominantMode=rotated_90", "dominantModeRatio=1.000"),
                line -> assertThat(line).contains("verdict=no-dominant-writing-mode", "horizontalRatio=0.333",
                        "dominantMode=rotated_90", "dominantModeRatio=0.667"));
    }

    private static BufferedImage whitePage() {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 400, 400);
        graphics.dispose();
        return image;
    }

    /** A line of a page turned 90 degrees clockwise: it starts at its top-right and runs down. */
    private static OcrRegion turned(String id, int right, int top) {
        return new OcrRegion(id, "text", List.of(
                new OcrPoint(right, top), new OcrPoint(right, top + 300),
                new OcrPoint(right - 30, top + 300), new OcrPoint(right - 30, top)),
                List.of(), .98f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("en", .98f)), 0);
    }

    private static OcrRegion withMode(OcrRegion region, WritingMode mode) {
        return region.withGeometry(region.polygon(), region.words(), mode);
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
