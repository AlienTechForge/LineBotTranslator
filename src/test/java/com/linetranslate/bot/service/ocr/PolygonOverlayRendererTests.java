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
        assertThat(result.lowConfidenceBlockCount()).isEqualTo(1);
        assertThat(result.decisions()).containsExactly(
                new OverlayRenderDecision("r-font", OverlayRenderStatus.PRESERVED, "font-coverage"));
        for (int y = 0; y < 60; y++) for (int x = 0; x < 100; x++)
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
