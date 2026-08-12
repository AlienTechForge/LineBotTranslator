package com.linetranslate.bot.service.ocr;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/** Deterministic font gate. Missing glyph coverage means preserve source pixels. */
@Component
public class ImageTranslationFontProvider {
    private static final List<String> PREFERRED = List.of(
            "Noto Sans CJK TC", "Noto Sans CJK SC", "Noto Sans CJK JP",
            "Noto Sans", "DejaVu Sans");
    private final Set<String> available;

    public ImageTranslationFontProvider() {
        this(Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet()));
    }

    ImageTranslationFontProvider(Set<String> available) {
        this.available = Set.copyOf(available);
    }

    public Optional<Font> fontFor(String text, float size) {
        return fontFor(text, size, Font.PLAIN);
    }

    public Optional<Font> fontFor(String text, float size, int style) {
        return PREFERRED.stream()
                .filter(name -> available.contains(name.toLowerCase(Locale.ROOT)))
                .map(name -> new Font(name, style, Math.max(MIN_SIZE, Math.round(size))))
                .filter(font -> font.canDisplayUpTo(text) < 0)
                .findFirst();
    }

    private static final int MIN_SIZE = 8;
}
