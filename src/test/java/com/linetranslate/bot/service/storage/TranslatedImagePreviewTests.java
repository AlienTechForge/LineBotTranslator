package com.linetranslate.bot.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.Optional;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class TranslatedImagePreviewTests {

    @Test
    void scalesAnImageItIsGivenWithoutDecodingAnything() {
        BufferedImage raster = noise(900, 700, BufferedImage.TYPE_INT_RGB);

        try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class, CALLS_REAL_METHODS)) {
            Optional<TranslatedImagePreview.Rendition> preview = TranslatedImagePreview.create(raster);

            assertThat(preview).isPresent();
            imageIo.verify(() -> ImageIO.read(any(InputStream.class)), never());
            imageIo.verify(() -> ImageIO.read(any(File.class)), never());
            imageIo.verify(() -> ImageIO.read(any(URL.class)), never());
        }
    }

    @Test
    void decodingTheEncodedBytesDoesGoThroughImageIo() throws Exception {
        // The control for the test above: the spy sees a decode when there is one.
        byte[] png = encodePng(noise(300, 200, BufferedImage.TYPE_INT_RGB));

        try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class, CALLS_REAL_METHODS)) {
            assertThat(TranslatedImagePreview.create(png)).isPresent();

            imageIo.verify(() -> ImageIO.read(any(InputStream.class)));
        }
    }

    @Test
    void aRasterPreviewKeepsTheCurrentOutputRules() throws Exception {
        TranslatedImagePreview.Rendition preview =
                TranslatedImagePreview.create(noise(900, 700, BufferedImage.TYPE_INT_RGB)).orElseThrow();

        assertThat(Math.max(preview.width(), preview.height())).isEqualTo(TranslatedImagePreview.MAX_DIMENSION);
        assertThat((double) preview.width() / preview.height()).isBetween(1.27, 1.30);
        assertThat(preview.bytes().length).isLessThanOrEqualTo(TranslatedImagePreview.MAX_BYTES);
        assertThat(preview.bytes()).startsWith((byte) 0xFF, (byte) 0xD8);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(preview.bytes()));
        assertThat(decoded.getWidth()).isEqualTo(preview.width());
        assertThat(decoded.getHeight()).isEqualTo(preview.height());
    }

    @Test
    void aSmallRasterIsNotUpscaled() {
        TranslatedImagePreview.Rendition preview =
                TranslatedImagePreview.create(noise(120, 80, BufferedImage.TYPE_INT_RGB)).orElseThrow();

        assertThat(preview.width()).isEqualTo(120);
        assertThat(preview.height()).isEqualTo(80);
    }

    @Test
    void aRasterPreviewIsByteForByteThePreviewOfItsLosslessEncoding() throws Exception {
        for (int type : new int[] {BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB}) {
            BufferedImage raster = noise(900, 700, type);
            byte[] png = encodePng(raster);

            byte[] fromRaster = TranslatedImagePreview.create(raster).orElseThrow().bytes();
            byte[] fromBytes = TranslatedImagePreview.create(png).orElseThrow().bytes();

            assertThat(fromRaster).as("image type %d", type).isEqualTo(fromBytes);
        }
    }

    @Test
    void transparencyIsFlattenedOntoWhiteForARasterToo() throws Exception {
        BufferedImage clear = new BufferedImage(60, 40, BufferedImage.TYPE_INT_ARGB);

        TranslatedImagePreview.Rendition preview = TranslatedImagePreview.create(clear).orElseThrow();

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(preview.bytes()));
        assertThat(decoded.getRGB(30, 20) & 0xFFFFFF).isGreaterThan(0xF0F0F0);
    }

    @Test
    void aMissingRasterYieldsNoPreview() {
        assertThat(TranslatedImagePreview.create((BufferedImage) null)).isEmpty();
    }

    @Test
    void undecodableBytesStillYieldNoPreview() {
        assertThat(TranslatedImagePreview.create(new byte[] {1, 2, 3})).isEmpty();
    }

    private static byte[] encodePng(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static BufferedImage noise(int width, int height, int type) {
        Random random = new Random(11);
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        return image;
    }
}
