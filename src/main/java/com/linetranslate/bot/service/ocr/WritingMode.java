package com.linetranslate.bot.service.ocr;

import java.util.List;

/**
 * How the lines of a region run in the image, and whether its glyphs stand upright there.
 *
 * <p>The mode a region gets by default is derived from its polygon alone (see {@link #ofFrame}),
 * and the frame cannot tell a page that was turned a quarter from upright vertical writing: both
 * leave the line edge pointing down the image. The frame default therefore never says
 * {@code VERTICAL_*}; those two values are reserved for a region that carries evidence of upright
 * vertical writing, and nothing sets them yet. An unclassified region keeps being laid out in its
 * own frame, exactly as before the mode existed.
 */
public enum WritingMode {
    /** Line along +x within tolerance, glyphs upright. */
    HORIZONTAL,
    /** Upright glyphs top to bottom, columns right to left (CJK). */
    VERTICAL_RL,
    /** Upright glyphs top to bottom, columns left to right (stacked Latin signage). */
    VERTICAL_LR,
    /** Horizontal writing on a page turned 90 degrees clockwise: the line runs down the image. */
    ROTATED_90,
    /** Horizontal writing on a page turned upside down. */
    ROTATED_180,
    /** Horizontal writing on a page turned 270 degrees clockwise: the line runs up the image. */
    ROTATED_270,
    /** Any other angle; the glyphs follow the line. */
    SKEWED;

    /**
     * Largest deviation, in degrees, of the line edge from a quarter turn for the frame default
     * to still name it. This is the band {@link OcrRegion#orientation()} always used for
     * "horizontal" (7) and "vertical" (83 to 97), so a region that was not horizontal before is
     * not horizontal now, and no decision taken on an upright page changes. The page detector
     * allows a wider deviation for a region to vote on the page's rotation.
     */
    static final double FRAME_TOLERANCE_DEGREES = 7;

    /** Glyphs stand upright in the image: horizontal lines and upright vertical writing. */
    public boolean upright() {
        return this == HORIZONTAL || vertical();
    }

    public boolean vertical() {
        return this == VERTICAL_RL || this == VERTICAL_LR;
    }

    /** Quarter turns clockwise a page was turned by: 0 for the upright modes, 1 to 3, -1 for SKEWED. */
    public int quarterTurns() {
        return switch (this) {
            case HORIZONTAL, VERTICAL_RL, VERTICAL_LR -> 0;
            case ROTATED_90 -> 1;
            case ROTATED_180 -> 2;
            case ROTATED_270 -> 3;
            case SKEWED -> -1;
        };
    }

    /**
     * The mode the polygon's first edge (vertex 0 to vertex 1, the provider's reading direction)
     * implies. Never {@code VERTICAL_*}. A polygon without an edge is horizontal.
     */
    static WritingMode ofFrame(List<OcrPoint> polygon) {
        if (polygon == null || polygon.size() < 2) return HORIZONTAL;
        OcrPoint first = polygon.get(0);
        OcrPoint second = polygon.get(1);
        double degrees = Math.toDegrees(Math.atan2(second.y() - first.y(), second.x() - first.x()));
        return ofAngle(degrees, FRAME_TOLERANCE_DEGREES);
    }

    /** The mode of a line edge at {@code degrees} (as {@code atan2} reports it) within a tolerance. */
    static WritingMode ofAngle(double degrees, double toleranceDegrees) {
        double normalized = Math.abs(degrees) % 180;
        if (normalized <= toleranceDegrees || normalized >= 180 - toleranceDegrees) {
            return Math.abs(degrees) <= 90 ? HORIZONTAL : ROTATED_180;
        }
        if (normalized >= 90 - toleranceDegrees && normalized <= 90 + toleranceDegrees) {
            return degrees > 0 ? ROTATED_90 : ROTATED_270;
        }
        return SKEWED;
    }
}
