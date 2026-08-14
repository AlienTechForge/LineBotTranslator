package com.linetranslate.bot.service.ocr;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** Conservative pixel/geometry classifier for dense, low-colour text documents. */
public final class OverlayContentClassifier {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OverlayContentClassifier.class);
    private static final int MINIMUM_DOCUMENT_REGIONS = 3;
    private static final int COLOUR_CHROMA_THRESHOLD = 45;
    private static final double MAX_COLOURFUL_PIXEL_RATIO = .08;
    private static final double MIN_HORIZONTAL_REGION_RATIO = .80;
    private static final double MIN_DOMINANT_TONE_RATIO = .60;

    public OverlayContentMode classify(BufferedImage image, List<OcrRegion> regions) {
        List<OcrRegion> values = regions == null ? List.of() : regions;
        if (image == null || values.size() < MINIMUM_DOCUMENT_REGIONS) {
            report(values.size(), -1, -1, -1, -1, "too-few-regions");
            return OverlayContentMode.GENERAL;
        }
        long reliable = values.stream().filter(region -> region.confidenceKnown()
                && region.confidence() >= .80f).count();
        long horizontal = values.stream()
                .filter(region -> region.orientation() == OcrOrientation.HORIZONTAL).count();
        double reliableRatio = (double) reliable / values.size();
        double horizontalRatio = (double) horizontal / values.size();
        if (reliable < values.size() * .8
                || horizontal < Math.ceil(values.size() * MIN_HORIZONTAL_REGION_RATIO)) {
            report(values.size(), reliableRatio, horizontalRatio, -1, -1,
                    reliable < values.size() * .8 ? "unreliable-regions" : "not-horizontal-enough");
            return OverlayContentMode.GENERAL;
        }
        PixelProfile profile = pixelProfile(image);
        boolean document = profile.colourfulRatio() <= MAX_COLOURFUL_PIXEL_RATIO
                && profile.dominantToneRatio() >= MIN_DOMINANT_TONE_RATIO;
        report(values.size(), reliableRatio, horizontalRatio,
                profile.colourfulRatio(), profile.dominantToneRatio(),
                document ? "document"
                        : profile.colourfulRatio() > MAX_COLOURFUL_PIXEL_RATIO
                                ? "too-colourful" : "no-dominant-tone");
        return document ? OverlayContentMode.DOCUMENT : OverlayContentMode.GENERAL;
    }

    /**
     * DOCUMENT mode raises the per-region coverage limit from 12% to 45%, which decides whether a
     * paragraph on a small image can be overwritten at all, so the blocking criterion must be
     * visible. Ratios and counts only.
     */
    private static void report(int regionCount, double reliableRatio, double horizontalRatio,
            double colourfulRatio, double dominantToneRatio, String verdict) {
        log.info("Overlay content mode: verdict={}, regions={}, reliableRatio={}, "
                        + "horizontalRatio={}, colourfulRatio={}, dominantToneRatio={}",
                verdict, regionCount, format(reliableRatio), format(horizontalRatio),
                format(colourfulRatio), format(dominantToneRatio));
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
