package com.linetranslate.bot.service.ocr;

public enum ImageTranslationFailureStage {
    DOWNLOAD,
    RECOGNITION,
    NO_TEXT,
    TRANSLATION,
    CANCELLED
}
