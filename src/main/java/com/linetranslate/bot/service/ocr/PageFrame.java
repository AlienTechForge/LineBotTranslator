package com.linetranslate.bot.service.ocr;

/**
 * The frame the regions of one recognition are in, relative to the image that was submitted.
 *
 * @param quarterTurns  0 to 3. When not 0 the regions were moved into the upright frame by
 *                      counter-clockwise quarter turns, and the raster must be turned the same way
 *                      to be drawn on and turned back clockwise to be delivered
 * @param sourceWidth   width of the page as the provider saw it, which is the width of the image that
 *                      was submitted; 0 when unknown
 * @param sourceHeight  height of that page; 0 when unknown
 * @param verdict       what the detector made of the page
 * @param retryAdvised  a rotated page that was read poorly (see {@link PageOrientation})
 * @param reliableShare reliable weight over the weight of all regions, or negative when unknown
 */
public record PageFrame(
        int quarterTurns,
        int sourceWidth,
        int sourceHeight,
        PageOrientation.Verdict verdict,
        boolean retryAdvised,
        double reliableShare) {

    /** A recognition whose regions are in the frame of the image, which was not analysed. */
    public static final PageFrame NONE =
            new PageFrame(0, 0, 0, PageOrientation.Verdict.NOT_ANALYSED, false, -1);

    public PageFrame {
        quarterTurns = Math.floorMod(quarterTurns, 4);
        sourceWidth = Math.max(0, sourceWidth);
        sourceHeight = Math.max(0, sourceHeight);
        verdict = verdict == null ? PageOrientation.Verdict.NOT_ANALYSED : verdict;
    }

    /** Whether the regions are upright versions of a rotated page. */
    public boolean rotated() {
        return quarterTurns != 0;
    }
}
