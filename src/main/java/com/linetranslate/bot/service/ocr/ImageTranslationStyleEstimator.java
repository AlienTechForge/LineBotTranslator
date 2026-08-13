package com.linetranslate.bot.service.ocr;

import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Best-effort source style recovery from pixels inside located OCR geometry. */
final class ImageTranslationStyleEstimator {
    private static final int SAMPLE_MARGIN = 4;
    private static final int MINIMUM_COLOUR_DISTANCE_SQUARED = 45 * 45;
    private static final double BOLD_INK_RATIO = .16;

    private ImageTranslationStyleEstimator() {
    }

    static ImageTranslationTextStyle estimate(BufferedImage image, OcrRegion region) {
        Area regionArea = OverlaySafetyPolicy.mask(region);
        Rectangle bounds = regionArea.getBounds();
        Color background = dominant(borderPixels(image, regionArea, bounds), Color.WHITE);
        Area sourceArea = OverlaySafetyPolicy.sourceMask(region);
        if (sourceArea.isEmpty()) sourceArea = new Area(regionArea);

        PixelSample foreground = foregroundPixels(image, sourceArea, background);
        Color text = dominant(foreground.colours(), contrastingText(background));
        int fontStyle = foreground.inkRatio() >= BOLD_INK_RATIO ? Font.BOLD : Font.PLAIN;
        return new ImageTranslationTextStyle(background, text, fontStyle, sourceFontSize(region, bounds));
    }

    /**
     * Background colour for one glyph cleanup area. In dense text a glyph's perimeter is mostly the
     * ink of neighbouring glyphs rather than background, so the local sample is only trusted when it
     * sits closer to the region background than to the region text colour. Without that guard the
     * cleanup fill becomes a solid ink-coloured block painted over the source content.
     */
    static Color localBackground(
            BufferedImage image, Area cleanupArea, Color background, Color foreground) {
        Rectangle bounds = cleanupArea.getBounds();
        int adaptiveMargin = Math.max(SAMPLE_MARGIN,
                Math.min(16, Math.max(bounds.width, bounds.height) / 3));
        Color sampled = dominant(perimeterPixels(image, bounds, adaptiveMargin), background);
        if (foreground == null) return sampled;
        return colourDistanceSquared(sampled, foreground) < colourDistanceSquared(sampled, background)
                ? background
                : sampled;
    }

    private static List<Color> perimeterPixels(BufferedImage image, Rectangle bounds, int margin) {
        List<Color> pixels = new ArrayList<>();
        int left = Math.max(0, bounds.x - margin);
        int top = Math.max(0, bounds.y - margin);
        int right = Math.min(image.getWidth() - 1, bounds.x + bounds.width - 1 + margin);
        int bottom = Math.min(image.getHeight() - 1, bounds.y + bounds.height - 1 + margin);
        for (int x = left; x <= right; x++) {
            pixels.add(new Color(image.getRGB(x, top), true));
            if (bottom != top) pixels.add(new Color(image.getRGB(x, bottom), true));
        }
        for (int y = top + 1; y < bottom; y++) {
            pixels.add(new Color(image.getRGB(left, y), true));
            if (right != left) pixels.add(new Color(image.getRGB(right, y), true));
        }
        return pixels;
    }

    private static List<Color> borderPixels(BufferedImage image, Area regionArea, Rectangle bounds) {
        List<Color> pixels = new ArrayList<>();
        int left = Math.max(0, bounds.x - SAMPLE_MARGIN);
        int top = Math.max(0, bounds.y - SAMPLE_MARGIN);
        int right = Math.min(image.getWidth() - 1, bounds.x + bounds.width - 1 + SAMPLE_MARGIN);
        int bottom = Math.min(image.getHeight() - 1, bounds.y + bounds.height - 1 + SAMPLE_MARGIN);
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                if (!regionArea.contains(x + .5, y + .5)) pixels.add(new Color(image.getRGB(x, y), true));
            }
        }
        if (!pixels.isEmpty()) return pixels;
        for (int y = Math.max(0, bounds.y); y < Math.min(image.getHeight(), bounds.y + bounds.height); y++) {
            for (int x = Math.max(0, bounds.x); x < Math.min(image.getWidth(), bounds.x + bounds.width); x++) {
                if (regionArea.contains(x + .5, y + .5)) pixels.add(new Color(image.getRGB(x, y), true));
            }
        }
        return pixels;
    }

    private static PixelSample foregroundPixels(BufferedImage image, Area sourceArea, Color background) {
        Rectangle bounds = sourceArea.getBounds();
        List<Color> colours = new ArrayList<>();
        int areaPixels = 0;
        for (int y = Math.max(0, bounds.y); y < Math.min(image.getHeight(), bounds.y + bounds.height); y++) {
            for (int x = Math.max(0, bounds.x); x < Math.min(image.getWidth(), bounds.x + bounds.width); x++) {
                if (!sourceArea.contains(x + .5, y + .5)) continue;
                areaPixels++;
                Color colour = new Color(image.getRGB(x, y), true);
                if (colour.getAlpha() > 32 && colourDistanceSquared(colour, background)
                        >= MINIMUM_COLOUR_DISTANCE_SQUARED) {
                    colours.add(colour);
                }
            }
        }
        return new PixelSample(colours, areaPixels == 0 ? 0 : (double) colours.size() / areaPixels);
    }

    private static Color dominant(List<Color> colours, Color fallback) {
        if (colours.isEmpty()) return fallback;
        Map<Integer, ColourBucket> buckets = new HashMap<>();
        for (Color colour : colours) {
            int key = (colour.getRed() >> 4) << 8
                    | (colour.getGreen() >> 4) << 4
                    | colour.getBlue() >> 4;
            buckets.computeIfAbsent(key, ignored -> new ColourBucket()).add(colour);
        }
        return buckets.entrySet().stream()
                .max(Comparator.<Map.Entry<Integer, ColourBucket>>comparingInt(entry -> entry.getValue().count)
                        .thenComparingInt(Map.Entry::getKey))
                .map(entry -> entry.getValue().average())
                .orElse(fallback);
    }

    private static int sourceFontSize(OcrRegion region, Rectangle regionBounds) {
        List<Integer> wordHeights = region.words().stream()
                .map(OcrWord::polygon)
                .filter(points -> points.size() >= 4)
                .map(points -> (int) Math.round(Math.hypot(
                        points.get(points.size() - 1).x() - points.get(0).x(),
                        points.get(points.size() - 1).y() - points.get(0).y())))
                .filter(height -> height > 1)
                .sorted()
                .toList();
        int measured = wordHeights.isEmpty()
                ? regionBounds.height
                : wordHeights.get(wordHeights.size() / 2);
        return Math.max(8, Math.min(128, (int) Math.round(measured * 1.1)));
    }

    private static int colourDistanceSquared(Color left, Color right) {
        int red = left.getRed() - right.getRed();
        int green = left.getGreen() - right.getGreen();
        int blue = left.getBlue() - right.getBlue();
        return red * red + green * green + blue * blue;
    }

    private static Color contrastingText(Color background) {
        double luminance = .2126 * background.getRed() + .7152 * background.getGreen()
                + .0722 * background.getBlue();
        return luminance < 128 ? Color.WHITE : new Color(25, 25, 25);
    }

    private record PixelSample(List<Color> colours, double inkRatio) {
        PixelSample {
            colours = List.copyOf(colours);
        }
    }

    private static final class ColourBucket {
        private int count;
        private long red;
        private long green;
        private long blue;

        void add(Color colour) {
            count++;
            red += colour.getRed();
            green += colour.getGreen();
            blue += colour.getBlue();
        }

        Color average() {
            return new Color((int) (red / count), (int) (green / count), (int) (blue / count));
        }
    }
}
