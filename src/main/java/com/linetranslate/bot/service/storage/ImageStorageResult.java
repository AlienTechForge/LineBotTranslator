package com.linetranslate.bot.service.storage;

import java.util.Optional;

/**
 * The storage outcome is independent from whether OCR and translation succeeded. A translated image
 * may also carry the URL of a separately stored LINE preview rendition.
 */
public record ImageStorageResult(boolean stored, Optional<String> url, Optional<String> previewUrl) {

    public ImageStorageResult {
        url = url == null ? Optional.empty() : url.filter(value -> !value.isBlank());
        previewUrl = previewUrl == null ? Optional.empty() : previewUrl.filter(value -> !value.isBlank());
        if (!stored) {
            url = Optional.empty();
        }
        if (url.isEmpty()) {
            previewUrl = Optional.empty();
        }
    }

    public ImageStorageResult(boolean stored, Optional<String> url) {
        this(stored, url, Optional.empty());
    }

    public static ImageStorageResult stored(String url) {
        return new ImageStorageResult(true, Optional.ofNullable(url));
    }

    public static ImageStorageResult stored(String url, String previewUrl) {
        return new ImageStorageResult(true, Optional.ofNullable(url), Optional.ofNullable(previewUrl));
    }

    public static ImageStorageResult storedWithoutUrl() {
        return new ImageStorageResult(true, Optional.empty());
    }

    public static ImageStorageResult notStored() {
        return new ImageStorageResult(false, Optional.empty());
    }
}
