package com.linetranslate.bot.service.storage;

import java.util.Optional;

/** The storage outcome is independent from whether OCR and translation succeeded. */
public record ImageStorageResult(boolean stored, Optional<String> url) {

    public ImageStorageResult {
        url = url == null ? Optional.empty() : url.filter(value -> !value.isBlank());
        if (!stored) {
            url = Optional.empty();
        }
    }

    public static ImageStorageResult stored(String url) {
        return new ImageStorageResult(true, Optional.ofNullable(url));
    }

    public static ImageStorageResult storedWithoutUrl() {
        return new ImageStorageResult(true, Optional.empty());
    }

    public static ImageStorageResult notStored() {
        return new ImageStorageResult(false, Optional.empty());
    }
}
