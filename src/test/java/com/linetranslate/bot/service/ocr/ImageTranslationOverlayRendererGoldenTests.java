package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class ImageTranslationOverlayRendererGoldenTests {

    private static final int TRANSLATED_TEXT_RGB = new Color(25, 25, 25).getRGB();
    private static final int SOURCE_TEXT_RGB = new Color(45, 50, 60).getRGB();
    private static final int LOW_CONFIDENCE_RGB = new Color(230, 138, 0).getRGB();

    private final ImageTranslationOverlayRenderer renderer = new ImageTranslationOverlayRenderer();
    private final ImageInputValidator validator = new ImageInputValidator(ImageTranslationProperties.defaults());

    @Test
    void horizontalGoldenImageWrapsAndShrinksTextInPlace() throws Exception {
        GoldenFixture fixture = render(
                240, 120,
                List.of(plan("hello world", "這是一段需要換行的翻譯文字", 20, 25, 200, 48, 0.98f)));

        assertThat(fixture.result().renderedBlockCount()).isEqualTo(1);
        assertThat(countColor(fixture.rendered(), TRANSLATED_TEXT_RGB, 20, 25, 200, 24)).isPositive();
        assertThat(countColor(fixture.rendered(), TRANSLATED_TEXT_RGB, 20, 49, 200, 24)).isPositive();
        assertOutsideBlocksUnchanged(fixture);
    }

    @Test
    void verticalGoldenImageUsesTopToBottomGlyphLayout() throws Exception {
        GoldenFixture fixture = render(
                220, 160,
                List.of(plan("原文", "翻譯", 172, 15, 32, 125, 0.97f)));

        assertThat(fixture.result().renderedBlockCount()).isEqualTo(1);
        assertThat(countColor(fixture.rendered(), TRANSLATED_TEXT_RGB, 172, 15, 32, 42)).isPositive();
        assertThat(countColor(fixture.rendered(), TRANSLATED_TEXT_RGB, 172, 57, 32, 42)).isPositive();
        assertOutsideBlocksUnchanged(fixture);
    }

    @Test
    void multilingualGoldenImageKeepsIndependentBlocks() throws Exception {
        GoldenFixture fixture = render(
                280, 180,
                List.of(
                        plan("Hello", "你好", 15, 18, 110, 40, 0.99f),
                        plan("こんにちは", "Good day", 145, 102, 120, 48, 0.94f)));

        assertThat(fixture.result().renderedBlockCount()).isEqualTo(2);
        assertThat(countColor(fixture.rendered(), TRANSLATED_TEXT_RGB, 15, 18, 110, 40)).isPositive();
        assertThat(countColor(fixture.rendered(), TRANSLATED_TEXT_RGB, 145, 102, 120, 48)).isPositive();
        assertOutsideBlocksUnchanged(fixture);
    }

    @Test
    void denseGoldenImagePreservesLowConfidenceSourceInsteadOfReplacingIt() throws Exception {
        GoldenFixture fixture = render(
                300, 200,
                List.of(
                        plan("one", "一", 10, 10, 80, 32, 0.96f),
                        plan("two", "二", 105, 12, 80, 32, 0.93f),
                        plan("uncertain", "不應覆寫", 200, 10, 90, 32, 0.31f),
                        plan("dense paragraph", "密集文字會自動縮排與換行", 12, 80, 276, 95, 0.91f)));

        assertThat(fixture.result().renderedBlockCount()).isEqualTo(3);
        assertThat(fixture.result().lowConfidenceBlockCount()).isEqualTo(1);
        assertThat(countColor(fixture.rendered(), TRANSLATED_TEXT_RGB, 10, 10, 80, 32)).isPositive();
        assertThat(countColor(fixture.rendered(), TRANSLATED_TEXT_RGB, 105, 12, 80, 32)).isPositive();
        assertThat(countColor(fixture.rendered(), TRANSLATED_TEXT_RGB, 12, 80, 276, 95)).isPositive();
        assertThat(countColor(fixture.rendered(), SOURCE_TEXT_RGB, 200, 10, 90, 32)).isPositive();
        assertThat(countColor(fixture.rendered(), LOW_CONFIDENCE_RGB, 200, 10, 90, 32)).isPositive();
        assertOutsideBlocksUnchanged(fixture);
    }

    private GoldenFixture render(int width, int height, List<ImageOverlayBlock> blocks) throws Exception {
        BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = source.createGraphics();
        try {
            graphics.setColor(new Color(238, 241, 246));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new Color(45, 50, 60));
            for (ImageOverlayBlock block : blocks) {
                OcrService.TextBlock area = block.source();
                graphics.drawString(block.source().getText(), area.getX() + 2, area.getY() + area.getHeight() / 2);
            }
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(source, "png", output);
        ValidatedImage image = validator.validate(output.toByteArray(), "image/png");
        RenderedImage result = renderer.render(image, blocks, 0.60f);
        BufferedImage rendered = ImageIO.read(new java.io.ByteArrayInputStream(result.pngBytes()));
        return new GoldenFixture(result, source, rendered, List.copyOf(blocks));
    }

    private static ImageOverlayBlock plan(
            String source,
            String replacement,
            int x,
            int y,
            int width,
            int height,
            float confidence) {
        return new ImageOverlayBlock(
                new OcrService.TextBlock(source, x, y, width, height, confidence),
                replacement);
    }

    private static int countColor(
            BufferedImage image,
            int rgb,
            int x,
            int y,
            int width,
            int height) {
        int count = 0;
        int right = Math.min(image.getWidth(), x + width);
        int bottom = Math.min(image.getHeight(), y + height);
        for (int pixelY = Math.max(0, y); pixelY < bottom; pixelY++) {
            for (int pixelX = Math.max(0, x); pixelX < right; pixelX++) {
                if (image.getRGB(pixelX, pixelY) == rgb) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void assertOutsideBlocksUnchanged(GoldenFixture fixture) {
        for (int y = 0; y < fixture.original().getHeight(); y++) {
            for (int x = 0; x < fixture.original().getWidth(); x++) {
                if (!insideAnyBlock(x, y, fixture.blocks())) {
                    assertThat(fixture.rendered().getRGB(x, y))
                            .as("pixel outside OCR blocks at (%s,%s)", x, y)
                            .isEqualTo(fixture.original().getRGB(x, y));
                }
            }
        }
    }

    private static boolean insideAnyBlock(int x, int y, List<ImageOverlayBlock> blocks) {
        return blocks.stream().map(ImageOverlayBlock::source).anyMatch(block ->
                x >= block.getX() - 1
                        && x < block.getX() + block.getWidth() + 1
                        && y >= block.getY() - 1
                        && y < block.getY() + block.getHeight() + 1);
    }

    private record GoldenFixture(
            RenderedImage result,
            BufferedImage original,
            BufferedImage rendered,
            List<ImageOverlayBlock> blocks) {
    }
}
