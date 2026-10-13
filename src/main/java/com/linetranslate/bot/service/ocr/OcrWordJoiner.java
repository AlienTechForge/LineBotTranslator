package com.linetranslate.bot.service.ocr;

/**
 * The script rule for putting OCR words back together when the provider does not say what
 * separated them. Chinese and Japanese have no spaces between words, so two neighbours in those
 * scripts join directly. Korean is written with spaces between words, so a Hangul word keeps a
 * space on either side, even next to Hanja. Any other pair, such as two Latin words or a CJK
 * glyph next to a number, gets a space.
 */
final class OcrWordJoiner {

    private OcrWordJoiner() {
    }

    /** The separator to put between {@code left} and {@code right}; empty when either is empty. */
    static String scriptSeparator(CharSequence left, String right) {
        if (left.isEmpty() || right.isEmpty()) return "";
        int previous = Character.codePointBefore(left, left.length());
        int next = right.codePointAt(0);
        return isUnspacedScript(previous) && isUnspacedScript(next) ? "" : " ";
    }

    /** Han, Hiragana and Katakana: scripts whose words are written without separating spaces. */
    static boolean isUnspacedScript(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA;
    }

    /** Any CJK glyph, including Hangul, for callers that only need to tell CJK from other text. */
    static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
