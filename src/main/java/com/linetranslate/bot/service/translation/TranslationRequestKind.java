package com.linetranslate.bot.service.translation;

public enum TranslationRequestKind {
    STANDARD_TEXT(false),
    QUICK_TEXT(false),
    BATCH_TEXT(false),
    IMAGE_OCR(true);

    private final boolean image;

    TranslationRequestKind(boolean image) {
        this.image = image;
    }

    public boolean isImage() {
        return image;
    }
}
