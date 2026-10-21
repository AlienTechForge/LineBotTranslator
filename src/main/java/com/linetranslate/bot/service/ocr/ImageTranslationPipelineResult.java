package com.linetranslate.bot.service.ocr;

import com.linetranslate.bot.service.storage.ImageStorageResult;

/**
 * What the image-translator service made of one picture, ready to be put into a LINE reply: the
 * stored translated picture (when there is one to send), whether it carries the "may have gaps"
 * warning, and the texts with the id of the {@code TranslationRecord} that was saved for them.
 *
 * @param renderedImage the translated picture in storage; {@link ImageStorageResult#notStored()} when
 *                      the reply is the translated text alone
 * @param warned        the service judged the picture usable but not clean ({@code tier: warned})
 */
public record ImageTranslationPipelineResult(
        ImageTranslationContext context,
        ImageStorageResult renderedImage,
        boolean warned,
        Text text) {

    public ImageTranslationPipelineResult {
        if (context == null || text == null) {
            throw new IllegalArgumentException("Image translation result requires context and text");
        }
        renderedImage = renderedImage == null ? ImageStorageResult.notStored() : renderedImage;
    }

    /**
     * The recognised and the translated text of the picture. {@code recordId} is null when the record
     * could not be saved; the languages are the ones the service reported and the one that was asked for.
     */
    public record Text(
            String recognizedText,
            String translatedText,
            String sourceLanguage,
            String targetLanguage,
            String recordId,
            String stylePresetId,
            String stylePromptVersion) {

        public Text {
            recognizedText = recognizedText == null ? "" : recognizedText;
            translatedText = translatedText == null ? "" : translatedText;
        }
    }
}
