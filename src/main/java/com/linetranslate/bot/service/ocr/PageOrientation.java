package com.linetranslate.bot.service.ocr;

/**
 * What {@link PageOrientationDetector} measured on one page, and the verdict drawn from it. Shares
 * and offsets are fractions in [0, 1] (the punctuation offset can fall slightly outside), and a
 * value that could not be measured is negative so a log prints it as {@code n/a}. Counts and
 * ratios only: nothing here can carry text or an id.
 *
 * @param verdict            what the page is
 * @param quarterTurns       the leading frame quarter (0 to 3) of the page's text, or -1 when no text
 *                           carried enough weight to lead; a quarter only turns the page when the
 *                           verdict is {@link Verdict#ROTATED}
 * @param regions            provider paragraphs looked at
 * @param reliableRegions    of those, the ones at or above the reliability bar
 * @param dominantShare      weight of the leading quarter over the weight of all reliable regions,
 *                           skewed ones included
 * @param skewedShare        weight of regions off every quarter by more than the tolerance, over the
 *                           same total
 * @param reliableShare      reliable weight over the weight of all regions
 * @param cjkShare           share of the scripted letters of the leading quarter's regions that are CJK
 * @param symbolSamples      symbols of the leading quarter's regions whose box is within tolerance of a
 *                           quarter, which the glyph shares below are shares of
 * @param uprightGlyphShare  share of those whose own box runs along +x
 * @param rotatedGlyphShare  share of those whose own box runs along +y
 * @param punctuationSamples punctuation and small kana whose line band could be measured
 * @param punctuationOffset  median position of those inside their line band, 0 at the top of the line
 *                           frame and 1 at the bottom
 * @param retryAdvised       a rotated page that was read poorly, so reading it upright may do better
 */
public record PageOrientation(
        Verdict verdict,
        int quarterTurns,
        int regions,
        int reliableRegions,
        double dominantShare,
        double skewedShare,
        double reliableShare,
        double cjkShare,
        int symbolSamples,
        double uprightGlyphShare,
        double rotatedGlyphShare,
        int punctuationSamples,
        double punctuationOffset,
        boolean retryAdvised) {

    public enum Verdict {
        /** Most of the text runs along +x. No action. */
        UPRIGHT,
        /** The page is a rotated page: its text runs along one non-zero quarter. */
        ROTATED,
        /** Upright vertical writing, whose frame looks like a rotated page. Not acted on yet. */
        VERTICAL_WRITING,
        /** CJK at a quarter turn with no evidence either way. No action: the frame is kept. */
        AMBIGUOUS,
        /** No quarter holds enough of the text. No action. */
        MIXED,
        /** Too little reliable text to judge. No action. */
        INSUFFICIENT,
        /** The page was never analysed (another OCR service, or no page size to map with). */
        NOT_ANALYSED
    }
}
