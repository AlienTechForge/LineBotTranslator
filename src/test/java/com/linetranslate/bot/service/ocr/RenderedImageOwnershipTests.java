package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * The image carriers of one translation request own their buffers instead of copying them, and the
 * renderer hands on the raster it encoded.
 */
class RenderedImageOwnershipTests {

    @Test
    void validatedImageOwnsTheBytesAndRasterItIsGiven() {
        byte[] bytes = {1, 2, 3};
        BufferedImage raster = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);

        ValidatedImage image = new ValidatedImage(bytes, "image/png", raster);

        assertThat(image.bytes()).isSameAs(bytes);
        assertThat(image.image()).isSameAs(raster);
    }

    @Test
    void validatorKeepsTheDownloadedArrayInsteadOfCopyingIt() throws Exception {
        BufferedImage source = noise(16, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "png", encoded);
        byte[] download = encoded.toByteArray();

        ValidatedImage validated = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(download, "image/png");

        assertThat(validated.bytes()).isSameAs(download);
    }

    @Test
    void renderedImageOwnsItsBytes() {
        byte[] bytes = {1, 2, 3};

        RenderedImage rendered = new RenderedImage(
                bytes, "image/png", 1, 0, List.of(), null, null);

        assertThat(rendered.bytes()).isSameAs(bytes);
        assertThat(rendered.pngBytes()).isSameAs(bytes);
    }

    @Test
    void losslessOutputCarriesTheRasterItWasEncodedFrom() throws Exception {
        BufferedImage source = noise(64, 64, BufferedImage.TYPE_INT_ARGB);

        RenderedImage rendered = renderer(10_485_760).render(validated(source), null);

        assertThat(rendered.contentType()).isEqualTo("image/png");
        assertThat(rendered.raster()).isNotNull().isNotSameAs(source);
        assertSamePixels(rendered.raster(), decode(rendered));
    }

    @Test
    void outputRecodedToFitTheCapCarriesNoRaster() {
        BufferedImage opaque = noise(400, 400, BufferedImage.TYPE_INT_RGB);
        BufferedImage transparent = noise(300, 300, BufferedImage.TYPE_INT_ARGB);

        RenderedImage jpeg = renderer(200_000).render(validated(opaque), null);
        RenderedImage downscaledJpeg = renderer(60_000).render(validated(opaque), null);
        RenderedImage downscaledPng = renderer(150_000).render(validated(transparent), null);

        assertThat(jpeg.contentType()).isEqualTo("image/jpeg");
        assertThat(downscaledJpeg.contentType()).isEqualTo("image/jpeg");
        assertThat(downscaledPng.contentType()).isEqualTo("image/png");
        assertThat(jpeg.raster()).isNull();
        assertThat(downscaledJpeg.raster()).isNull();
        assertThat(downscaledPng.raster()).isNull();
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

    private static void assertSamePixels(BufferedImage actual, BufferedImage expected) {
        assertThat(actual.getWidth()).isEqualTo(expected.getWidth());
        assertThat(actual.getHeight()).isEqualTo(expected.getHeight());
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertThat(actual.getRGB(x, y)).as("pixel %d,%d", x, y).isEqualTo(expected.getRGB(x, y));
            }
        }
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
