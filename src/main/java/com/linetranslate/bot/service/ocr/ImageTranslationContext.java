package com.linetranslate.bot.service.ocr;

import com.linetranslate.bot.service.storage.ImageStorageResult;

/** The original picture as it was downloaded from LINE, and what became of its copy in storage. */
public record ImageTranslationContext(
        DownloadedImage image,
        ImageStorageResult storage) {

    public ImageTranslationContext {
        if (image == null || storage == null) {
            throw new IllegalArgumentException("Image translation context requires image and storage results");
        }
    }
}
