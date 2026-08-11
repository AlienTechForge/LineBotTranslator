package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class ImageTranslationOverlayRendererGoldenTests {

    private final ImageTranslationOverlayRenderer renderer = new ImageTranslationOverlayRenderer();
    private final ImageInputValidator validator = new ImageInputValidator(ImageTranslationProperties.defaults());

    @Test
    void horizontalGoldenImageWrapsAndShrinksTextInPlace() throws Exception {
        RenderedImage result = render(
                240, 120,
                List.of(plan("hello world", "這是一段需要換行的翻譯文字", 20, 25, 200, 48, 0.98f)));

        assertThat(result.renderedBlockCount()).isEqualTo(1);
        assertThat(sha256(result.pngBytes()))
                .isEqualTo("bfe53f308278d2ad93ef7f41186b29c50409cd25be400747a32da7193dad1f46");
    }

    @Test
    void verticalGoldenImageUsesTopToBottomGlyphLayout() throws Exception {
        RenderedImage result = render(
                220, 160,
                List.of(plan("原文", "翻譯", 172, 15, 32, 125, 0.97f)));

        assertThat(result.renderedBlockCount()).isEqualTo(1);
        assertThat(sha256(result.pngBytes()))
                .isEqualTo("4314cfacfda3705131cd8ad745fd7f26d51b940b27245f3f3200a707a84dbed0");
    }

    @Test
    void multilingualGoldenImageKeepsIndependentBlocks() throws Exception {
        RenderedImage result = render(
                280, 180,
                List.of(
                        plan("Hello", "你好", 15, 18, 110, 40, 0.99f),
                        plan("こんにちは", "Good day", 145, 102, 120, 48, 0.94f)));

        assertThat(result.renderedBlockCount()).isEqualTo(2);
        assertThat(sha256(result.pngBytes()))
                .isEqualTo("951aeb3a35578fe9059ee05caf8f1fe3995ac834642aaef7131abb72cf08a6b9");
    }

    @Test
    void denseGoldenImagePreservesLowConfidenceSourceInsteadOfReplacingIt() throws Exception {
        RenderedImage result = render(
                300, 200,
                List.of(
                        plan("one", "一", 10, 10, 80, 32, 0.96f),
                        plan("two", "二", 105, 12, 80, 32, 0.93f),
                        plan("uncertain", "不應覆寫", 200, 10, 90, 32, 0.31f),
                        plan("dense paragraph", "密集文字會自動縮排與換行", 12, 80, 276, 95, 0.91f)));

        assertThat(result.renderedBlockCount()).isEqualTo(3);
        assertThat(result.lowConfidenceBlockCount()).isEqualTo(1);
        assertThat(sha256(result.pngBytes()))
                .isEqualTo("5926ab1c4c9a492871c0dff3fed134f6ce2b171e56c7b772f1d6abcdc2d6134f");
    }

    private RenderedImage render(int width, int height, List<ImageOverlayBlock> blocks) throws Exception {
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
        return renderer.render(image, blocks, 0.60f);
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

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
