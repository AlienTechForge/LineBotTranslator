package com.linetranslate.bot.service.ocr;

import static com.linetranslate.bot.service.ocr.VerticalFixtures.rectangle;
import static com.linetranslate.bot.service.ocr.VerticalFixtures.whiteImage;
import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Where vertical writing is measured across the column, not along it: the upright frame of a
 * region, the type size read off its words, and the padding of its masks.
 */
class VerticalGeometryTests {

    /** A column 30 wide and 163 tall, as one word, with the corners in the order given. */
    private static OcrRegion column(WritingMode mode, List<OcrPoint> polygon) {
        return new OcrRegion("column", "糖醋排骨飯", polygon,
                List.of(new OcrWord("糖醋排骨飯", polygon, .99f, true)), .99f, true, OcrBlockType.TEXT,
                List.of(), 0, "column", false, mode, List.of());
    }

    private static final List<OcrPoint> UPRIGHT = rectangle(40, 25, 30, 163);
    /** The same box as Vision lists a column: from the top right corner, the first edge pointing down. */
    private static final List<OcrPoint> PROVIDER = List.of(new OcrPoint(70, 25), new OcrPoint(70, 188),
            new OcrPoint(40, 188), new OcrPoint(40, 25));

    // --- the upright frame ---------------------------------------------------------------------

    @Test
    void theUprightPolygonStartsAtTheCornerNearestTheTopLeft() {
        assertThat(column(WritingMode.VERTICAL_RL, UPRIGHT).uprightPolygon()).isEqualTo(UPRIGHT);
        assertThat(column(WritingMode.VERTICAL_RL, PROVIDER).uprightPolygon()).containsExactly(
                new OcrPoint(40, 25), new OcrPoint(70, 25), new OcrPoint(70, 188), new OcrPoint(40, 188));
    }

    @Test
    void theUprightWidthIsTheWidthAcrossTheColumnWhicheverCornerTheProviderStartedAt() {
        assertThat(OcrRegion.uprightWidth(UPRIGHT)).isEqualTo(30);
        assertThat(OcrRegion.uprightWidth(PROVIDER)).isEqualTo(30);
        assertThat(OcrRegion.uprightWidth(List.of())).isZero();
    }

    @Test
    void theStoredPolygonKeepsTheProviderOrder() {
        OcrRegion region = column(WritingMode.VERTICAL_RL, PROVIDER);

        assertThat(region.polygon()).isEqualTo(PROVIDER);
        assertThat(region.rotationDegrees()).isCloseTo(90, org.assertj.core.data.Offset.offset(.01));
    }

    // --- type size ----------------------------------------------------------------------------

    @Test
    void theSourceSizeOfAColumnIsReadAcrossItAndNotAlongIt() {
        BufferedImage page = whiteImage(140, 220);

        int upright = ImageTranslationStyleEstimator.estimate(page, column(WritingMode.VERTICAL_RL, UPRIGHT))
                .maximumFontSize();
        int provider = ImageTranslationStyleEstimator.estimate(page, column(WritingMode.VERTICAL_RL, PROVIDER))
                .maximumFontSize();
        int measuredAlong = ImageTranslationStyleEstimator.estimate(page, column(WritingMode.HORIZONTAL, UPRIGHT))
                .maximumFontSize();

        assertThat(upright).as("a 30px column holds 30px type").isEqualTo(33);
        assertThat(provider).isEqualTo(33);
        // Without the mode the word is a line 163 pixels tall, which is what the estimate always did.
        assertThat(measuredAlong).isEqualTo(128);
    }

    // --- mask padding -------------------------------------------------------------------------

    @Test
    void theMaskOfAColumnIsPaddedByTheWidthOfItsGlyphsNotByItsLength() {
        Rectangle vertical = OverlaySafetyPolicy.mask(column(WritingMode.VERTICAL_RL, UPRIGHT)).getBounds();
        Rectangle provider = OverlaySafetyPolicy.mask(column(WritingMode.VERTICAL_RL, PROVIDER)).getBounds();
        Rectangle horizontal = OverlaySafetyPolicy.mask(column(WritingMode.HORIZONTAL, UPRIGHT)).getBounds();

        // 30px glyphs: 1 + ceil(30 * .06) = 3px on each side.
        assertThat(vertical.width).isEqualTo(30 + 2 * 3);
        assertThat(vertical.height).isEqualTo(163 + 2 * 3);
        assertThat(provider).isEqualTo(vertical);
        // The same box read as a line 163px tall is padded by the 8px cap, as it always was.
        assertThat(horizontal.width).isEqualTo(30 + 2 * 8);
    }
}
