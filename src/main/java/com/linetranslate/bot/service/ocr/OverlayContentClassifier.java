package com.linetranslate.bot.service.ocr;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** Conservative pixel/geometry classifier for dense, low-colour text documents. */
public final class OverlayContentClassifier {
    private static final int MINIMUM_DOCUMENT_REGIONS = 3;
    private static final int COLOUR_CHROMA_THRESHOLD = 45;
    private static final double MAX_COLOURFUL_PIXEL_RATIO = .08;
    private static final double MIN_HORIZONTAL_REGION_RATIO = .80;
    private static final double MIN_DOMINANT_TONE_RATIO = .60;

    public OverlayContentMode classify(BufferedImage image, List<OcrRegion> regions) {
        List<OcrRegion> values = regions == null ? List.of() : regions;
        if (image == null || values.size() < MINIMUM_DOCUMENT_REGIONS) {
            return OverlayContentMode.GENERAL;
        }
        long reliable = values.stream().filter(region -> region.confidenceKnown()
                && region.confidence() >= .80f).count();
        long horizontal = values.stream()
                .filter(region -> region.orientation() == OcrOrientation.HORIZONTAL).count();
        if (reliable < values.size() * .8
                || horizontal < Math.ceil(values.size() * MIN_HORIZONTAL_REGION_RATIO)) {
            return OverlayContentMode.GENERAL;
        }
        PixelProfile profile = pixelProfile(image);
        return profile.colourfulRatio() <= MAX_COLOURFUL_PIXEL_RATIO
                && profile.dominantToneRatio() >= MIN_DOMINANT_TONE_RATIO
                ? OverlayContentMode.DOCUMENT
                : OverlayContentMode.GENERAL;
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
