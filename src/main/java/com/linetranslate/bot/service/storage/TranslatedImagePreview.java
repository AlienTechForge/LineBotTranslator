package com.linetranslate.bot.service.storage;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

import javax.imageio.ImageIO;

import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;

/**
 * Builds the small JPEG that LINE shows in the chat while the full image stays behind a tap. LINE
 * requires {@code previewImageUrl} to be at most 1 MB and documents 240 x 240 px as its maximum
 * preview size, whereas the rendered original may be up to {@code IMAGE_TRANSLATION_MAX_OUTPUT_BYTES}.
 */
@Slf4j
final class TranslatedImagePreview {

    static final String CONTENT_TYPE = "image/jpeg";
    /** Longest side of the preview, in pixels. */
    static final int MAX_DIMENSION = 240;
    /** LINE rejects a preview above 1 MB. */
    static final int MAX_BYTES = 1_048_576;

    private static final float[] JPEG_QUALITIES = {.8f, .6f, .4f};

    private TranslatedImagePreview() {
    }

    /**
     * Downscales a PNG or JPEG to fit {@link #MAX_DIMENSION} and encodes it as JPEG, never
     * upscaling. Transparency is flattened onto white since JPEG has no alpha channel. Returns
     * empty when the input cannot be decoded or no quality fits {@link #MAX_BYTES}; the caller then
     * falls back to the original as its own preview.
     */
    static Optional<Rendition> create(byte[] imageBytes) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
                return Optional.empty();
            }
            BufferedImage flat = flatten(downscale(source));
            for (float quality : JPEG_QUALITIES) {
                byte[] jpeg = encode(flat, quality);
                if (jpeg.length <= MAX_BYTES) {
                    return Optional.of(new Rendition(jpeg, flat.getWidth(), flat.getHeight()));
                }
            }
            return Optional.empty();
        } catch (Exception failure) {
            log.warn("Translated image preview could not be built: failure={}",
                    MinioStorageService.safeFailure(failure));
            return Optional.empty();
        }
    }

    private static BufferedImage downscale(BufferedImage source) throws IOException {
        if (Math.max(source.getWidth(), source.getHeight()) <= MAX_DIMENSION) {
            return source;
        }
        return Thumbnails.of(source)
                .size(MAX_DIMENSION, MAX_DIMENSION)
                .imageType(BufferedImage.TYPE_INT_ARGB)
                .asBufferedImage();
    }

    private static BufferedImage flatten(BufferedImage image) {
        BufferedImage flat = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = flat.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, flat.getWidth(), flat.getHeight());
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return flat;
    }

    private static byte[] encode(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thumbnails.of(image)
                .scale(1.0)
                .outputFormat("jpg")
                .outputQuality(quality)
                .toOutputStream(output);
        return output.toByteArray();
    }

    /** Encoded preview bytes with the pixel size they ended up at. */
    record Rendition(byte[] bytes, int width, int height) {
    }
}
