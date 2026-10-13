package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class RenderedImageSizeCapTests {

    @Test
    void outputUnderTheCapStaysTheSameLosslessPng() throws Exception {
        BufferedImage source = noise(64, 64, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        ImageIO.write(source, "png", expected);

        RenderedImage rendered = new ImageTranslationOverlayRenderer().render(validated(source), null);
        RenderedImage legacy = new ImageTranslationOverlayRenderer().render(validated(source), List.of(), .6f);

        assertThat(rendered.contentType()).isEqualTo("image/png");
        assertThat(rendered.bytes()).isEqualTo(expected.toByteArray());
        assertThat(rendered.pngBytes()).isEqualTo(rendered.bytes());
        assertThat(legacy.contentType()).isEqualTo("image/png");
        assertThat(legacy.bytes()).isEqualTo(expected.toByteArray());
    }

    @Test
    void oversizedOpaqueOutputFallsBackToJpegAtFullSize() throws Exception {
        // Noise is the worst case for PNG: about 480 KB here, while JPEG at 0.92 is about 160 KB.
        BufferedImage source = noise(400, 400, BufferedImage.TYPE_INT_RGB);
        int cap = 200_000;

        RenderedImage rendered = renderer(cap).render(validated(source), null);

        assertThat(rendered.contentType()).isEqualTo("image/jpeg");
        assertThat(rendered.bytes().length).isLessThanOrEqualTo(cap);
        assertThat(rendered.bytes()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(rendered.bytes()[1] & 0xFF).isEqualTo(0xD8);
        BufferedImage decoded = decode(rendered);
        assertThat(decoded.getWidth()).isEqualTo(400);
        assertThat(decoded.getHeight()).isEqualTo(400);
    }

    @Test
    void outputThatJpegAloneCannotFitIsDownscaledUnderTheCap() throws Exception {
        BufferedImage source = noise(400, 400, BufferedImage.TYPE_INT_RGB);
        int cap = 60_000;

        RenderedImage rendered = renderer(cap).render(validated(source), null);

        assertThat(rendered.contentType()).isEqualTo("image/jpeg");
        assertThat(rendered.bytes().length).isLessThanOrEqualTo(cap);
        BufferedImage decoded = decode(rendered);
        assertThat(decoded.getWidth()).isLessThan(400);
        assertThat((double) decoded.getWidth() / decoded.getHeight()).isCloseTo(1d,
                org.assertj.core.data.Offset.offset(.01));
    }

    @Test
    void transparentOutputNeverBecomesJpeg() throws Exception {
        BufferedImage source = noise(300, 300, BufferedImage.TYPE_INT_ARGB);
        int cap = 150_000;

        RenderedImage rendered = renderer(cap).render(validated(source), null);

        assertThat(rendered.contentType()).isEqualTo("image/png");
        assertThat(rendered.bytes().length).isLessThanOrEqualTo(cap);
        BufferedImage decoded = decode(rendered);
        assertThat(decoded.getColorModel().hasAlpha()).isTrue();
        assertThat(decoded.getWidth()).isLessThan(300);
    }

    @Test
    void outputThatCannotFitAfterBoundedDegradationIsRefused() {
        ImageTranslationOverlayRenderer renderer = renderer(10_000);

        assertThatThrownBy(() -> renderer.render(
                validated(noise(300, 300, BufferedImage.TYPE_INT_ARGB)), null))
                .isInstanceOf(RenderedImageEncodingException.class)
                .hasMessage("Translated image could not be encoded");
        assertThatThrownBy(() -> renderer.render(
                validated(noise(400, 400, BufferedImage.TYPE_INT_RGB)), null))
                .isInstanceOf(RenderedImageEncodingException.class)
                .hasMessage("Translated image could not be encoded");
    }

    @Test
    void rejectsNonPositiveCap() {
        assertThatThrownBy(() -> renderer(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ImageTranslationOverlayRenderer renderer(long maxOutputBytes) {
        return new ImageTranslationOverlayRenderer(new ImageTranslationFontProvider(), maxOutputBytes);
    }

    private static ValidatedImage validated(BufferedImage image) {
        return new ValidatedImage(new byte[] {1}, "image/png", image);
    }

    private static BufferedImage decode(RenderedImage rendered) throws Exception {
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(rendered.bytes()));
        assertThat(decoded).isNotNull();
        return decoded;
    }

    private static BufferedImage noise(int width, int height, int type) {
        Random random = new Random(7);
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        return image;
    }
}
