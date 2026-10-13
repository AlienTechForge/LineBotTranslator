package com.linetranslate.bot.service.ocr;

/**
 * The script rule for putting OCR words back together when the provider does not say what
 * separated them. CJK text has no spaces between words, so two CJK neighbours join directly;
 * any other pair, such as two Latin words or a CJK glyph next to a number, gets a space.
 */
final class OcrWordJoiner {

    private OcrWordJoiner() {
    }

    /** The separator to put between {@code left} and {@code right}; empty when either is empty. */
    static String scriptSeparator(CharSequence left, String right) {
        if (left.isEmpty() || right.isEmpty()) return "";
        int previous = Character.codePointBefore(left, left.length());
        int next = right.codePointAt(0);
        return isCjk(previous) && isCjk(next) ? "" : " ";
    }

    static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
