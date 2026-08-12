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

class PolygonOverlayRendererTests {
    @Test
    void manySparseOverlaysRenderWithinAReasonableLocalCpuBudget() {
        BufferedImage original = new BufferedImage(1536, 2730, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(new Color(38, 38, 38));
            graphics.fillRect(0, 0, original.getWidth(), original.getHeight());
        } finally {
            graphics.dispose();
        }
        List<ImageRegionOverlay> overlays = new java.util.ArrayList<>();
        for (int index = 0; index < 48; index++) {
            int column = index % 6;
            int row = index / 6;
            int x = 80 + column * 240;
            int y = 80 + row * 320;
            List<OcrPoint> polygon = List.of(
                    new OcrPoint(x + 40, y), new OcrPoint(x + 40, y + 180),
                    new OcrPoint(x, y + 180), new OcrPoint(x, y));
            OcrRegion region = new OcrRegion("perf-" + index, "菜單", polygon,
                    List.of(new OcrWord("菜單", polygon, .99f, true)),
                    .99f, true, OcrBlockType.TEXT, List.of(), index);
            overlays.add(new ImageRegionOverlay(region, "Menu"));
        }
        OverlaySafetyPlan plan = new OverlaySafetyPlan(true, "safe", overlays, 0);
        ImageTranslationOverlayRenderer renderer = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans")));

        long started = System.nanoTime();
        RenderedImage rendered = renderer.render(
                new ValidatedImage(new byte[] {1}, "image/png", original), plan);
        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);

        assertThat(rendered.renderedBlockCount()).isEqualTo(48);
        assertThat(elapsedMillis).as("48 overlays on 1536x2730 image").isLessThan(5_000);
    }

    @Test
    void replacementClearsOnlySourceGlyphAreaAndPreservesTableGrid() throws Exception {
        BufferedImage original = new BufferedImage(170, 90, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, original.getWidth(), original.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.drawRect(10, 10, 145, 55);
            graphics.setColor(new Color(0, 90, 210));
            graphics.drawLine(115, 10, 115, 65);
            graphics.setColor(Color.BLACK);
            graphics.setFont(graphics.getFont().deriveFont(java.awt.Font.BOLD, 24f));
            graphics.drawString("ITEM", 20, 48);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        List<OcrPoint> cell = List.of(
                new OcrPoint(10, 10), new OcrPoint(120, 10),
                new OcrPoint(120, 65), new OcrPoint(10, 65));
        List<OcrPoint> word = List.of(
                new OcrPoint(18, 20), new OcrPoint(92, 20),
                new OcrPoint(92, 53), new OcrPoint(18, 53));
        OcrRegion region = new OcrRegion("grid-cell", "ITEM", cell,
                List.of(new OcrWord("ITEM", word, .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(region, "Meal")), 170, 90,
                new ImageTranslationProperties(1000, 200, 20000, .6f, true, .8, .9));

        RenderedImage rendered = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);
        assertThat(rendered.renderedBlockCount()).as("decisions=%s", rendered.decisions()).isEqualTo(1);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(rendered.pngBytes()));

        for (int x = 10; x <= 155; x++) {
            assertThat(output.getRGB(x, 10)).as("top grid at x=%s", x)
                    .isEqualTo(original.getRGB(x, 10));
            assertThat(output.getRGB(x, 65)).as("bottom grid at x=%s", x)
                    .isEqualTo(original.getRGB(x, 65));
        }
        for (int y = 10; y <= 65; y++) {
            assertThat(output.getRGB(10, y)).as("left grid at y=%s", y)
                    .isEqualTo(original.getRGB(10, y));
            assertThat(output.getRGB(115, y)).as("column grid at y=%s", y)
                    .isEqualTo(original.getRGB(115, y));
        }
    }

    @Test
    void translatedTextUsesWholeParagraphInsteadOfSparseSourceWordMasks() throws Exception {
        BufferedImage original = new BufferedImage(240, 100, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, original.getWidth(), original.getHeight());
            graphics.setColor(new Color(92, 72, 220));
            graphics.setFont(graphics.getFont().deriveFont(java.awt.Font.BOLD, 34f));
            graphics.drawString("SLEEP", 22, 58);
            graphics.drawString("SCORE", 168, 58);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion region = new OcrRegion("r-sparse", "SLEEP SCORE", List.of(
                new OcrPoint(20, 15), new OcrPoint(220, 15),
                new OcrPoint(220, 75), new OcrPoint(20, 75)), List.of(
                        new OcrWord("SLEEP", List.of(
                                new OcrPoint(20, 20), new OcrPoint(72, 20),
                                new OcrPoint(72, 66), new OcrPoint(20, 66)), .99f, true),
                        new OcrWord("SCORE", List.of(
                                new OcrPoint(168, 20), new OcrPoint(220, 20),
                                new OcrPoint(220, 66), new OcrPoint(168, 66)), .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("en", .99f)), 0);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(region, "TRANSLATED")), 240, 100,
                new ImageTranslationProperties(1000, 100, 10000, .6f, true, .7, .8));

        BufferedImage output = ImageIO.read(new ByteArrayInputStream(
                new ImageTranslationOverlayRenderer().render(source, plan).pngBytes()));

        int changedInGap = 0;
        for (int y = 15; y < 75; y++) for (int x = 80; x < 160; x++) {
            if (output.getRGB(x, y) != original.getRGB(x, y)) changedInGap++;
        }
        assertThat(changedInGap).as("translated glyphs in the gap between source word masks").isPositive();
    }

    @Test
    void replacementPreservesDominantSourceTextColour() throws Exception {
        Color sourceColour = new Color(92, 72, 220);
        BufferedImage original = new BufferedImage(180, 80, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, original.getWidth(), original.getHeight());
            graphics.setColor(sourceColour);
            graphics.setFont(graphics.getFont().deriveFont(java.awt.Font.BOLD, 32f));
            graphics.drawString("SLEEP", 20, 52);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion region = new OcrRegion("r-colour", "SLEEP", List.of(
                new OcrPoint(16, 14), new OcrPoint(130, 14),
                new OcrPoint(130, 62), new OcrPoint(16, 62)), List.of(
                        new OcrWord("SLEEP", List.of(
                                new OcrPoint(16, 14), new OcrPoint(130, 14),
                                new OcrPoint(130, 62), new OcrPoint(16, 62)), .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(region, "REST")), 180, 80,
                new ImageTranslationProperties(1000, 100, 10000, .6f, true, .6, .7));

        BufferedImage output = ImageIO.read(new ByteArrayInputStream(
                new ImageTranslationOverlayRenderer().render(source, plan).pngBytes()));

        int matchingPixels = 0;
        for (int y = 14; y < 62; y++) for (int x = 16; x < 130; x++) {
            Color pixel = new Color(output.getRGB(x, y));
            if (Math.abs(pixel.getRed() - sourceColour.getRed()) <= 12
                    && Math.abs(pixel.getGreen() - sourceColour.getGreen()) <= 12
                    && Math.abs(pixel.getBlue() - sourceColour.getBlue()) <= 12) {
                matchingPixels++;
            }
        }
        assertThat(matchingPixels).as("replacement pixels close to the source text colour").isPositive();
    }

    @Test
    void clearsSourceAntialiasFringeImmediatelyOutsideProviderPolygon() throws Exception {
        BufferedImage original = new BufferedImage(130, 70, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, original.getWidth(), original.getHeight());
            graphics.setColor(new Color(92, 72, 220));
            graphics.fillRect(91, 32, 1, 2);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        List<OcrPoint> polygon = List.of(
                new OcrPoint(10, 10), new OcrPoint(90, 10),
                new OcrPoint(90, 50), new OcrPoint(10, 50));
        OcrRegion region = new OcrRegion("r-fringe", "SOURCE", polygon,
                List.of(new OcrWord("SOURCE", polygon, .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(region, "OK")), 130, 70,
                new ImageTranslationProperties(1000, 100, 10000, .6f, true, .6, .7));

        BufferedImage output = ImageIO.read(new ByteArrayInputStream(
                new ImageTranslationOverlayRenderer().render(source, plan).pngBytes()));

        assertThat(output.getRGB(91, 32)).isEqualTo(Color.WHITE.getRGB());
        assertThat(output.getRGB(91, 33)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void rotatedOverlayNeverChangesPixelsOutsideValidatedPolygon() throws Exception {
        BufferedImage original = new BufferedImage(180, 140, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < original.getHeight(); y++) for (int x = 0; x < original.getWidth(); x++)
            original.setRGB(x, y, new Color((x * 3) % 255, (y * 5) % 255, 90).getRGB());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion rotated = new OcrRegion("r-rotated", "Cheese", List.of(
                new OcrPoint(50, 30), new OcrPoint(135, 70),
                new OcrPoint(120, 102), new OcrPoint(35, 62)), List.of(),
                .98f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("en", .99f)), 0);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(rotated, "Translated cheese")), 180, 140,
                new ImageTranslationProperties(10_485_760, 4096, 16_000_000, .6f, true, .2, .35));
        RenderedImage rendered = new ImageTranslationOverlayRenderer().render(source, plan);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(rendered.pngBytes()));
        java.awt.geom.Area mask = OverlaySafetyPolicy.mask(rotated);
        int changedInside = 0;
        for (int y = 0; y < original.getHeight(); y++) for (int x = 0; x < original.getWidth(); x++) {
            if (!mask.contains(x + .5, y + .5)) {
                assertThat(output.getRGB(x, y)).as("outside mask (%s,%s)", x, y)
                        .isEqualTo(original.getRGB(x, y));
            } else if (output.getRGB(x, y) != original.getRGB(x, y)) changedInside++;
        }
        assertThat(plan.safe()).isTrue();
        assertThat(rendered.renderedBlockCount()).isEqualTo(1);
        assertThat(rendered.decisions()).containsExactly(
                new OverlayRenderDecision("r-rotated", OverlayRenderStatus.RENDERED, "rendered"));
        assertThat(changedInside).isPositive();
    }

    @Test
    void missingGlyphCoveragePreservesEveryPixel() throws Exception {
        BufferedImage original = new BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion region = new OcrRegion("r-font", "hello", List.of(
                new OcrPoint(10, 10), new OcrPoint(80, 10), new OcrPoint(80, 40), new OcrPoint(10, 40)),
                List.of(), .98f, true, OcrBlockType.TEXT, List.of(), 0);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(region, "中文")), 100, 60,
                new ImageTranslationProperties(1000, 100, 10000, .6f, true, .5, .6));
        ImageTranslationOverlayRenderer renderer = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans")));

        RenderedImage result = renderer.render(source, plan);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(result.pngBytes()));

        assertThat(result.renderedBlockCount()).isZero();
        assertThat(result.degradation().count(OverlayDegradationReason.FONT)).isEqualTo(1);
        assertThat(result.degradation().count(OverlayDegradationReason.LOW_CONFIDENCE)).isZero();
        assertThat(result.decisions()).containsExactly(
                new OverlayRenderDecision("r-font", OverlayRenderStatus.PRESERVED, "font-coverage"));
        for (int y = 0; y < 60; y++) for (int x = 0; x < 100; x++)
            assertThat(output.getRGB(x, y)).isEqualTo(original.getRGB(x, y));
    }

    @Test
    void replacementThatCannotFitAtReadableSizePreservesOriginalInsteadOfEllipsizing() throws Exception {
        BufferedImage original = new BufferedImage(90, 36, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 90, 36);
            graphics.setColor(Color.BLACK);
            graphics.drawString("日", 10, 22);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion region = new OcrRegion("tiny", "日", List.of(
                new OcrPoint(8, 5), new OcrPoint(30, 5), new OcrPoint(30, 28), new OcrPoint(8, 28)),
                List.of(), .99f, true, OcrBlockType.TEXT, List.of(), 0);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(region, "Sunday with very long overflow")), 90, 36,
                new ImageTranslationProperties(1000, 100, 10000, .6f, true, .5, .6));

        RenderedImage result = new ImageTranslationOverlayRenderer().render(source, plan);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(result.pngBytes()));

        assertThat(result.renderedBlockCount()).isZero();
        assertThat(result.degradation().count(OverlayDegradationReason.TEXT_FIT)).isEqualTo(1);
        for (int y = 0; y < 36; y++) for (int x = 0; x < 90; x++)
            assertThat(output.getRGB(x, y)).isEqualTo(original.getRGB(x, y));
    }

    @Test
    void transparentPngKeepsAlphaOutsideEdgeTouchingMask() throws Exception {
        BufferedImage original = new BufferedImage(80, 50, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion edge = new OcrRegion("r-edge", "text", List.of(
                new OcrPoint(0, 0), new OcrPoint(55, 0), new OcrPoint(55, 25), new OcrPoint(0, 25)),
                List.of(), .99f, true, OcrBlockType.TEXT, List.of(), 0);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(edge, "translated")), 80, 50,
                new ImageTranslationProperties(1000, 100, 10000, .6f, true, .4, .5));

        BufferedImage output = ImageIO.read(new ByteArrayInputStream(
                new ImageTranslationOverlayRenderer().render(source, plan).pngBytes()));

        assertThat(output.getColorModel().hasAlpha()).isTrue();
        assertThat((output.getRGB(70, 40) >>> 24) & 0xff).isZero();
        assertThat(output.getRGB(70, 40)).isEqualTo(original.getRGB(70, 40));
    }
}
