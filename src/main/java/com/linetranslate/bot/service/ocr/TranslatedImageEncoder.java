package com.linetranslate.bot.service.ocr;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import lombok.extern.slf4j.Slf4j;

/**
 * Brings the PNG the image-translator service made into a size LINE can fetch. LINE loads the
 * original itself within its own timeout and refuses one above 10 MB, and a photograph saved as PNG is
 * several times its JPEG size, so: a PNG up to the delivery target is kept as it is; a larger opaque
 * one is delivered as JPEG when the first of quality 0.92 / 0.85 gets it under the target; otherwise
 * the PNG is kept while it fits the output cap; beyond the cap an opaque picture is tried as JPEG at
 * 0.92 / 0.85 / 0.75. A picture that still does not fit is refused, and the caller replies with the
 * text instead. Transparent pictures are never turned into JPEG (it cannot carry alpha).
 */
@Slf4j
final class TranslatedImageEncoder {

    static final String PNG = "image/png";
    static final String JPEG = "image/jpeg";
    private static final float[] DELIVERY_QUALITIES = {.92f, .85f};
    private static final float[] CAP_QUALITIES = {.92f, .85f, .75f};

    /** The bytes to upload and the media type they are in. */
    record Encoded(byte[] bytes, String contentType) {
    }

    private TranslatedImageEncoder() {
    }

    /** The picture to deliver, or empty when no encoding of it fits {@code maxOutputBytes}. */
    static Optional<Encoded> fit(byte[] png, long deliveryTargetBytes, long maxOutputBytes) {
        if (png.length <= deliveryTargetBytes) {
            return Optional.of(new Encoded(png, PNG));
        }
        BufferedImage decoded = decode(png);
        boolean opaque = decoded != null && !decoded.getColorModel().hasAlpha();
        if (opaque && deliveryTargetBytes < maxOutputBytes) {
            for (float quality : DELIVERY_QUALITIES) {
                Optional<byte[]> jpeg = jpeg(decoded, quality);
                if (jpeg.isPresent() && jpeg.get().length <= deliveryTargetBytes) {
                    log.info("Translated image re-encoded to fit the delivery target: pngBytes={}, outputBytes={}, "
                                    + "quality={}, deliveryTargetBytes={}",
                            png.length, jpeg.get().length, quality, deliveryTargetBytes);
                    return Optional.of(new Encoded(jpeg.get(), JPEG));
                }
            }
        }
        if (png.length <= maxOutputBytes) {
            log.info("Translated image kept as PNG above the delivery target: pngBytes={}, opaque={}, "
                    + "deliveryTargetBytes={}, maxOutputBytes={}", png.length, opaque, deliveryTargetBytes, maxOutputBytes);
            return Optional.of(new Encoded(png, PNG));
        }
        if (opaque) {
            for (float quality : CAP_QUALITIES) {
                Optional<byte[]> jpeg = jpeg(decoded, quality);
                if (jpeg.isPresent() && jpeg.get().length <= maxOutputBytes) {
                    log.info("Translated image degraded to fit the output cap: pngBytes={}, outputBytes={}, "
                            + "quality={}, maxOutputBytes={}", png.length, jpeg.get().length, quality, maxOutputBytes);
                    return Optional.of(new Encoded(jpeg.get(), JPEG));
                }
            }
        }
        log.warn("Translated image exceeds the output cap: pngBytes={}, opaque={}, maxOutputBytes={}",
                png.length, opaque, maxOutputBytes);
        return Optional.empty();
    }

    private static BufferedImage decode(byte[] png) {
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException | RuntimeException failure) {
            log.warn("Translated image could not be decoded for re-encoding: failure={}",
                    failure.getClass().getSimpleName());
            return null;
        }
    }

    private static Optional<byte[]> jpeg(BufferedImage image, float quality) {
        // JPEG needs plain RGB: a palette or grey PNG is redrawn first.
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return Optional.empty();
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream stream = new MemoryCacheImageOutputStream(output)) {
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(rgb, null, null), parameters);
        } catch (IOException | RuntimeException failure) {
            log.warn("Translated image JPEG encoding failed: failure={}", failure.getClass().getSimpleName());
            return Optional.empty();
        } finally {
            writer.dispose();
        }
        return Optional.of(output.toByteArray());
    }
}
