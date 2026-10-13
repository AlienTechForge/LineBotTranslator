package com.linetranslate.bot.util;

/** LINE text message length limit, counted in Unicode code points. */
public final class LineTextLimit {

    /** LINE rejects a text message longer than this with HTTP 400. */
    public static final int MAX_CODE_POINTS = 5_000;

    /** Appended to text that was cut to fit; counted inside the limit. */
    public static final String TRUNCATION_MARKER = "…（內容過長，已截斷）";

    private LineTextLimit() {
    }

    public static int codePoints(CharSequence value) {
        return Character.codePointCount(value, 0, value.length());
    }

    /** Fits {@code value} into one LINE text message. */
    public static String fit(String value) {
        return fit(value, MAX_CODE_POINTS);
    }

    /**
     * Returns {@code value} unchanged when it has at most {@code maxCodePoints} code points;
     * otherwise cuts it on a code point boundary and appends {@link #TRUNCATION_MARKER} so the
     * result has at most {@code maxCodePoints} code points. A budget smaller than the marker
     * yields the marker alone.
     */
    public static String fit(String value, int maxCodePoints) {
        if (codePoints(value) <= maxCodePoints) {
            return value;
        }
        int keep = Math.max(0, maxCodePoints - codePoints(TRUNCATION_MARKER));
        return value.substring(0, value.offsetByCodePoints(0, keep)) + TRUNCATION_MARKER;
    }
}
