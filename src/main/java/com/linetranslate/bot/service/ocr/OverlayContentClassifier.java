package com.linetranslate.bot.service.ocr;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * Conservative pixel/geometry classifier. A dense, low-colour text document is {@code DOCUMENT}; a
 * coloured layout of many reliable horizontal text lines on flat backgrounds (a poster or an
 * infographic) is {@code POSTER}; everything else, photographs included, is {@code GENERAL}.
 *
 * <p>Both need the text to run one way. Regions are grouped into writing-mode families: upright
 * (horizontal lines and upright vertical writing, which is how a vertical menu has always counted),
 * and one family each for the page turned 90, 180 and 270 degrees and for text at any other angle.
 * The dominant family must hold at least 80% of the regions. A page that was brought upright is all
 * horizontal. A page left turned, because it could not be told from vertical writing, is one family
 * and is as regular as a document, so it passes too. A page whose regions split across the turned
 * and skewed families does not.
 */
public final class OverlayContentClassifier {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OverlayContentClassifier.class);
    private static final int MINIMUM_DOCUMENT_REGIONS = 3;
    private static final int COLOUR_CHROMA_THRESHOLD = 45;
    private static final double MAX_COLOURFUL_PIXEL_RATIO = .08;
    /** Share of the regions the dominant writing-mode family must hold. */
    static final double MIN_DOMINANT_MODE_RATIO = .80;
    private static final double MIN_DOMINANT_TONE_RATIO = .60;
    /** Fewer text lines than this is a caption or a sign, which a photograph can have as well. */
    private static final int MINIMUM_POSTER_REGIONS = 10;
    private static final double MIN_POSTER_RELIABLE_RATIO = .90;
    /**
     * A text region is on a flat background when at least MIN_FLAT_SURROUND of the ring around it
     * lies within FLAT_SURROUND_DISTANCE of the ring's dominant colour. Printed fills measure above
     * .99 (synthetic poster, also after JPEG at quality 50); a card edge across the ring costs at
     * most about half of it; uniform noise of +-15 per channel measures .89 and +-25 measures .32.
     */
    private static final double MIN_FLAT_SURROUND = .90;
    private static final int FLAT_SURROUND_DISTANCE = 24;
    /** Share of text regions on a flat background from which the layout is printed, not a photo. */
    private static final double MIN_FLAT_REGION_RATIO = .80;

    public OverlayContentMode classify(BufferedImage image, List<OcrRegion> regions) {
        List<OcrRegion> values = regions == null ? List.of() : regions;
        if (image == null || values.size() < MINIMUM_DOCUMENT_REGIONS) {
            report(values.size(), -1, -1, null, -1, -1, -1, -1, "too-few-regions");
            return OverlayContentMode.GENERAL;
        }
        long reliable = values.stream().filter(region -> region.confidenceKnown()
                && region.confidence() >= .80f).count();
        long horizontal = values.stream()
                .filter(region -> region.writingMode() == WritingMode.HORIZONTAL).count();
        Map<Family, Long> families = new EnumMap<>(Family.class);
        values.forEach(region -> families.merge(Family.of(region.writingMode()), 1L, Long::sum));
        Map.Entry<Family, Long> dominant = families.entrySet().stream()
                .max(Map.Entry.<Family, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .orElseThrow();
        double reliableRatio = (double) reliable / values.size();
        double horizontalRatio = (double) horizontal / values.size();
        double dominantModeRatio = (double) dominant.getValue() / values.size();
        if (reliable < values.size() * .8
                || dominant.getValue() < Math.ceil(values.size() * MIN_DOMINANT_MODE_RATIO)) {
            report(values.size(), reliableRatio, horizontalRatio, dominant.getKey(), dominantModeRatio,
                    -1, -1, -1,
                    reliable < values.size() * .8 ? "unreliable-regions" : "no-dominant-writing-mode");
            return OverlayContentMode.GENERAL;
        }
        PixelProfile profile = pixelProfile(image);
        boolean document = profile.colourfulRatio() <= MAX_COLOURFUL_PIXEL_RATIO
                && profile.dominantToneRatio() >= MIN_DOMINANT_TONE_RATIO;
        if (document) {
            report(values.size(), reliableRatio, horizontalRatio, dominant.getKey(), dominantModeRatio,
                    profile.colourfulRatio(), profile.dominantToneRatio(), -1, "document");
            return OverlayContentMode.DOCUMENT;
        }
        // Only a layout with many reliable, horizontal lines can be a poster, so the flat-background
        // measure, the only one that reads the pixels around each region, is taken only for those.
        double flatRegionRatio = values.size() >= MINIMUM_POSTER_REGIONS
                && reliableRatio >= MIN_POSTER_RELIABLE_RATIO
                ? flatRegionRatio(image, values) : -1;
        boolean poster = flatRegionRatio >= MIN_FLAT_REGION_RATIO;
        report(values.size(), reliableRatio, horizontalRatio, dominant.getKey(), dominantModeRatio,
                profile.colourfulRatio(), profile.dominantToneRatio(), flatRegionRatio,
                poster ? "poster"
                        : profile.colourfulRatio() > MAX_COLOURFUL_PIXEL_RATIO
                                ? "too-colourful" : "no-dominant-tone");
        return poster ? OverlayContentMode.POSTER : OverlayContentMode.GENERAL;
    }

    /** Share of the regions on a flat background; a region that leaves no ring to sample counts out. */
    private static double flatRegionRatio(BufferedImage image, List<OcrRegion> regions) {
        long flat = regions.stream()
                .filter(region -> ImageTranslationStyleEstimator.surroundFlatness(
                        image, region, FLAT_SURROUND_DISTANCE * FLAT_SURROUND_DISTANCE)
                        >= MIN_FLAT_SURROUND)
                .count();
        return (double) flat / regions.size();
    }

    /**
     * DOCUMENT mode raises the per-region coverage limit from 12% to 45%, and DOCUMENT and POSTER
     * raise the total from 35%, which decides whether a paragraph or a whole section of a small
     * image can be overwritten at all, so the blocking criterion must be visible. Ratios and
     * counts only; flatRegionRatio is n/a when the layout is too small or too unreliable for the
     * measure to be taken. horizontalRatio is the share of strictly horizontal regions; the
     * verdict depends on dominantModeRatio, the share of the dominant writing-mode family.
     */
    private static void report(int regionCount, double reliableRatio, double horizontalRatio,
            Family dominantMode, double dominantModeRatio, double colourfulRatio, double dominantToneRatio,
            double flatRegionRatio, String verdict) {
        log.info("Overlay content mode: verdict={}, regions={}, reliableRatio={}, "
                        + "horizontalRatio={}, dominantMode={}, dominantModeRatio={}, colourfulRatio={}, "
                        + "dominantToneRatio={}, flatRegionRatio={}",
                verdict, regionCount, format(reliableRatio), format(horizontalRatio),
                dominantMode == null ? "n/a" : dominantMode.name().toLowerCase(java.util.Locale.ROOT),
                format(dominantModeRatio), format(colourfulRatio), format(dominantToneRatio),
                format(flatRegionRatio));
    }

    /** Writing modes that count as one layout: upright lines and columns, or one turned quarter. */
    private enum Family {
        UPRIGHT, ROTATED_90, ROTATED_180, ROTATED_270, SKEWED;

        static Family of(WritingMode mode) {
            return switch (mode) {
                case HORIZONTAL, VERTICAL_RL, VERTICAL_LR -> UPRIGHT;
                case ROTATED_90 -> ROTATED_90;
                case ROTATED_180 -> ROTATED_180;
                case ROTATED_270 -> ROTATED_270;
                case SKEWED -> SKEWED;
            };
        }
    }

    private static String format(double value) {
        return value < 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static PixelProfile pixelProfile(BufferedImage image) {
        int step = Math.max(1, Math.min(image.getWidth(), image.getHeight()) / 100);
        long samples = 0;
        long colourful = 0;
        Map<Integer, Long> toneCounts = new HashMap<>();
        for (int y = 0; y < image.getHeight(); y += step) {
            for (int x = 0; x < image.getWidth(); x += step) {
                Color colour = new Color(image.getRGB(x, y), true);
                if (colour.getAlpha() <= 32) continue;
                int maximum = Math.max(colour.getRed(), Math.max(colour.getGreen(), colour.getBlue()));
                int minimum = Math.min(colour.getRed(), Math.min(colour.getGreen(), colour.getBlue()));
                if (maximum - minimum > COLOUR_CHROMA_THRESHOLD) colourful++;
                int tone = (colour.getRed() >> 5) << 6
                        | (colour.getGreen() >> 5) << 3
                        | colour.getBlue() >> 5;
                toneCounts.merge(tone, 1L, Long::sum);
                samples++;
            }
        }
        if (samples == 0) return new PixelProfile(1, 0);
        long dominant = toneCounts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        return new PixelProfile((double) colourful / samples, (double) dominant / samples);
    }

    private record PixelProfile(double colourfulRatio, double dominantToneRatio) {
    }
}
