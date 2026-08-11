package com.linetranslate.bot.service.translation;

import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.util.LanguageUtils;

/** Application response with optional safe reference for follow-up actions. */
public record TranslationResponse(
        String displayText,
        String translatedText,
        String recordId,
        String sourceLanguage,
        String targetLanguage) {

    public static TranslationResponse plain(String displayText) {
        return new TranslationResponse(displayText, null, null, null, null);
    }

    public static TranslationResponse success(
            TranslationWorkflowResult result,
            String displayText) {
        return new TranslationResponse(
                displayText,
                result.translatedText(),
                result.recordId(),
                result.sourceLanguage(),
                result.targetLanguage());
    }

    public static TranslationResponse fromRecord(TranslationRecord record) {
        String display = record.getTranslatedText()
                + "\n\n[偵測到: " + LanguageUtils.toChineseName(record.getSourceLanguage())
                + " | 翻譯成: " + LanguageUtils.toChineseName(record.getTargetLanguage()) + "]";
        return new TranslationResponse(
                display,
                record.getTranslatedText(),
                record.getId(),
                record.getSourceLanguage(),
                record.getTargetLanguage());
    }

    public boolean actionable() {
        return recordId != null
                && !recordId.isBlank()
                && translatedText != null
                && !translatedText.isBlank()
                && targetLanguage != null
                && !targetLanguage.isBlank();
    }
}
