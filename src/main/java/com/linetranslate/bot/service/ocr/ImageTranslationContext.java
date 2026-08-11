package com.linetranslate.bot.service.ocr;

import com.linetranslate.bot.service.storage.ImageStorageResult;

/** Explicit state passed between download, storage, OCR and translation stages. */
public record ImageTranslationContext(
        DownloadedImage image,
        ImageStorageResult storage,
        String recognizedText,
        String requestedTargetLanguage) {

    public ImageTranslationContext {
        if (image == null || storage == null) {
            throw new IllegalArgumentException("Image translation context requires image and storage results");
        }
        if (recognizedText == null || recognizedText.isBlank()) {
            throw new IllegalArgumentException("Image translation context requires recognized text");
        }
        requestedTargetLanguage = requestedTargetLanguage == null
                || requestedTargetLanguage.isBlank()
                        ? null
                        : requestedTargetLanguage.trim();
    }
}
