package com.linetranslate.bot.service.ocr;

import java.awt.image.BufferedImage;

/**
 * A decoded upload: its encoded bytes, canonical content type and decoded raster.
 *
 * <p>Ownership: the record owns {@code bytes} and {@code image} and does not copy either. The only
 * producer is {@link ImageInputValidator}, which gives up the array and raster it passes in, and
 * the only consumers are the stages of one image translation request (storage, OCR, overlay
 * rendering), which read them and never write. A defensive copy here would keep the file (up to
 * {@code IMAGE_TRANSLATION_MAX_FILE_SIZE_BYTES}) alive once more for every read, on top of a raster
 * that is already four bytes per pixel.
 */
public record ValidatedImage(byte[] bytes, String contentType, BufferedImage image) {

    public ValidatedImage {
        if (bytes == null || bytes.length == 0 || contentType == null || image == null) {
            throw new IllegalArgumentException("Validated image fields are required");
        }
    }
}
