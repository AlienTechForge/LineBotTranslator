package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class ImageInputValidatorTests {

    private final ImageTranslationProperties properties = new ImageTranslationProperties(
            1_000_000, 4_096, 16_000_000, 0.60f);
    private final ImageInputValidator validator = new ImageInputValidator(properties);

    @Test
    void acceptsPngUsingDecodedFormatInsteadOfTrustingTheHeader() throws Exception {
        byte[] png = image("png", 32, 20);

        ValidatedImage result = validator.validate(png, "image/png");

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.image().getWidth()).isEqualTo(32);
        assertThat(result.image().getHeight()).isEqualTo(20);
    }

    @Test
    void rejectsMimeTypeThatDoesNotMatchTheDecodedImage() throws Exception {
        byte[] png = image("png", 10, 10);

        assertThatThrownBy(() -> validator.validate(png, "image/jpeg"))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("type");
    }

    @Test
    void rejectsUnsupportedAndOversizedImagesBeforeRendering() throws Exception {
        ImageInputValidator tinyLimit = new ImageInputValidator(
                new ImageTranslationProperties(32, 4_096, 16_000_000, 0.60f));

        assertThatThrownBy(() -> tinyLimit.validate(image("png", 20, 20), "image/png"))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("size");
        assertThatThrownBy(() -> validator.validate(new byte[] {'G', 'I', 'F', '8'}, "image/gif"))
                .isInstanceOf(InvalidImageException.class);
    }

    @Test
    void rejectsDecodedDimensionsAboveTheConfiguredLimit() throws Exception {
        ImageInputValidator smallDimensions = new ImageInputValidator(
                new ImageTranslationProperties(1_000_000, 8, 64, 0.60f));

        assertThatThrownBy(() -> smallDimensions.validate(image("png", 9, 8), "image/png"))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("dimensions");
    }

    @Test
    void appliesJpegExifOrientationBeforeOcrAndOverlay() throws Exception {
        byte[] jpeg = image("jpg", 2, 1);
        byte[] oriented = withExifOrientation(jpeg, 6);

        ValidatedImage result = validator.validate(oriented, "image/jpeg");

        assertThat(result.image().getWidth()).isEqualTo(1);
        assertThat(result.image().getHeight()).isEqualTo(2);
        assertThat(result.contentType()).isEqualTo("image/png");
    }

    private static byte[] image(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, x < Math.max(1, width / 2) ? Color.RED.getRGB() : Color.BLUE.getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }

    private static byte[] withExifOrientation(byte[] jpeg, int orientation) throws Exception {
        ByteBuffer tiff = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1);
        tiff.putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0);

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        payload.write(tiff.array());
        byte[] app1 = payload.toByteArray();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(jpeg, 0, 2);
        output.write(0xFF);
        output.write(0xE1);
        output.write((app1.length + 2) >>> 8);
        output.write((app1.length + 2) & 0xFF);
        output.write(app1);
        output.write(jpeg, 2, jpeg.length - 2);
        return output.toByteArray();
    }
}
