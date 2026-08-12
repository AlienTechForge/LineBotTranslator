package com.linetranslate.bot.service.ocr;

import java.awt.Color;
import java.awt.Font;

/** Visual hints inferred from source pixels; OCR providers do not expose font metadata. */
record ImageTranslationTextStyle(
        Color background,
        Color foreground,
        int fontStyle,
        int maximumFontSize) {

    ImageTranslationTextStyle {
        background = background == null ? Color.WHITE : background;
        foreground = foreground == null ? Color.BLACK : foreground;
        fontStyle = fontStyle == Font.BOLD ? Font.BOLD : Font.PLAIN;
        maximumFontSize = Math.max(8, Math.min(128, maximumFontSize));
    }
}
