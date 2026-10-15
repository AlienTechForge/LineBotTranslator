package com.linetranslate.bot.service.ocr;

import java.awt.Color;
import java.awt.Font;
import java.awt.Polygon;
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
    /**
     * Squared distance from the background at which a pixel counts as ink, for callers outside the
     * estimator that must tell background from content by the same rule.
     */
    static final int INK_DISTANCE_SQUARED = MINIMUM_COLOUR_DISTANCE_SQUARED;
    private static final int MAXIMUM_COLOUR_DISTANCE = 442;
    private static final int MINIMUM_SIZE_WORDS = 2;
    private static final int MINIMUM_STROKE_RUNS = 16;
    /** Runs shorter than this are antialiasing specks, not strokes. */
    private static final double MINIMUM_STROKE_RUN = .35;
    /** A word whose ink is much shorter than its box is punctuation or a dash, not a glyph row. */
    private static final double MINIMUM_INK_TO_BOX_HEIGHT = .3;
    /** A taller, narrower word box is a vertical column; its ink is as long as the column. */
    private static final double MAXIMUM_WORD_HEIGHT_TO_WIDTH = 2;
    private static final int RUN_BINS_PER_PIXEL = 16;
    private static final int RUN_BINS = 128 * RUN_BINS_PER_PIXEL;

    private ImageTranslationStyleEstimator() {
    }

    static ImageTranslationTextStyle estimate(BufferedImage image, OcrRegion region) {
        Area regionArea = OverlaySafetyPolicy.mask(region);
        Rectangle bounds = regionArea.getBounds();
        Color background = dominant(borderPixels(image, regionArea, bounds), Color.WHITE);
        List<Area> sourceAreas = OverlaySafetyPolicy.sourceAreas(region);
        if (sourceAreas.isEmpty()) sourceAreas = List.of(new Area(regionArea));

        PixelSample foreground = foregroundPixels(image, sourceAreas, background);
        Color text = dominant(foreground.colours(), contrastingText(background));
        GlyphMetrics glyphs = glyphMetrics(image, region, background);
        SourceScript script = SourceScript.of(region.text());
        int fontStyle = glyphs.bold(script) ? Font.BOLD : Font.PLAIN;
        int fontSize = glyphs.words() >= MINIMUM_SIZE_WORDS
                ? Math.max(8, Math.min(128, (int) Math.round(glyphs.inkHeight() / script.inkPerEm)))
                : sourceFontSize(region, bounds);
        return new ImageTranslationTextStyle(background, text, fontStyle, fontSize);
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

    /**
     * How flat the surface behind a region is: the share of the pixels in the band of
     * SAMPLE_MARGIN around the region's bounds (the ring {@link #estimate} takes the background
     * colour from, less the corners inside the bounds that the region's outline leaves out) that lie
     * within {@code distanceSquared} of that band's dominant colour. Printed layouts (fills, cards,
     * banners) read near 1 and a photograph's texture reads low. -1 when the region leaves no band
     * to sample (it fills the image).
     *
     * <p>Only the band is read, never the area inside it, so the cost follows the perimeter of the
     * region: this is taken for every region of a layout before anything is drawn, where the full
     * ring scan of {@code estimate} (every pixel of the bounds, tested against the outline) took a
     * second on a 3000x2000 poster.
     */
    static double surroundFlatness(BufferedImage image, OcrRegion region, int distanceSquared) {
        Rectangle bounds = OverlaySafetyPolicy.mask(region).getBounds();
        int left = Math.max(0, bounds.x - SAMPLE_MARGIN);
        int top = Math.max(0, bounds.y - SAMPLE_MARGIN);
        int right = Math.min(image.getWidth() - 1, bounds.x + bounds.width - 1 + SAMPLE_MARGIN);
        int bottom = Math.min(image.getHeight() - 1, bounds.y + bounds.height - 1 + SAMPLE_MARGIN);
        if (left > right || top > bottom) return -1;
        int innerLeft = Math.max(left, bounds.x);
        int innerRight = Math.min(right, bounds.x + bounds.width - 1);
        int[] band = new int[(right - left + 1) * 2 * SAMPLE_MARGIN + (bottom - top + 1) * 2 * SAMPLE_MARGIN];
        int count = 0;
        for (int y = top; y <= bottom; y++) {
            boolean insideRows = y >= bounds.y && y < bounds.y + bounds.height;
            for (int x = left; x <= right; x++) {
                if (insideRows && x >= innerLeft && x <= innerRight) {
                    x = innerRight;
                    continue;
                }
                band[count++] = image.getRGB(x, y);
            }
        }
        if (count == 0) return -1;
        int surface = dominantRgb(band, count);
        long flat = 0;
        for (int index = 0; index < count; index++) {
            if (rgbDistanceSquared(band[index], surface) <= distanceSquared) flat++;
        }
        return (double) flat / count;
    }

    /** Average colour of the most common 4-bit-per-channel bucket, as {@link #dominant} picks it. */
    private static int dominantRgb(int[] pixels, int count) {
        int[] buckets = new int[1 << 12];
        for (int index = 0; index < count; index++) buckets[bucketOf(pixels[index])]++;
        int best = 0;
        for (int key = 1; key < buckets.length; key++) {
            if (buckets[key] >= buckets[best]) best = key;
        }
        long red = 0;
        long green = 0;
        long blue = 0;
        for (int index = 0; index < count; index++) {
            if (bucketOf(pixels[index]) != best) continue;
            red += (pixels[index] >> 16) & 0xff;
            green += (pixels[index] >> 8) & 0xff;
            blue += pixels[index] & 0xff;
        }
        int members = buckets[best];
        return ((int) (red / members) << 16) | ((int) (green / members) << 8) | (int) (blue / members);
    }

    private static int bucketOf(int rgb) {
        return ((rgb >> 20) & 0xf) << 8 | ((rgb >> 12) & 0xf) << 4 | ((rgb >> 4) & 0xf);
    }

    private static int rgbDistanceSquared(int left, int right) {
        int red = ((left >> 16) & 0xff) - ((right >> 16) & 0xff);
        int green = ((left >> 8) & 0xff) - ((right >> 8) & 0xff);
        int blue = (left & 0xff) - (right & 0xff);
        return red * red + green * green + blue * blue;
    }

    private static PixelSample foregroundPixels(
            BufferedImage image, List<Area> sourceAreas, Color background) {
        Rectangle bounds = sourceAreas.get(0).getBounds();
        sourceAreas.forEach(area -> bounds.add(area.getBounds()));
        Rectangle window = bounds.intersection(new Rectangle(0, 0, image.getWidth(), image.getHeight()));
        List<Color> colours = new ArrayList<>();
        boolean[] inside = OverlaySafetyPolicy.coverage(sourceAreas, window);
        for (int y = window.y; y < window.y + window.height; y++) {
            for (int x = window.x; x < window.x + window.width; x++) {
                if (!inside[(y - window.y) * window.width + x - window.x]) continue;
                Color colour = new Color(image.getRGB(x, y), true);
                if (colour.getAlpha() > 32 && colourDistanceSquared(colour, background)
                        >= MINIMUM_COLOUR_DISTANCE_SQUARED) {
                    colours.add(colour);
                }
            }
        }
        return new PixelSample(colours);
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

    /**
     * The type size read off the word boxes: how tall a word is across its line. In upright vertical
     * writing a word is a run of glyphs down a column, so what lies across the line is the column's
     * width, the first edge of the upright box, and not the length of the column.
     */
    private static int sourceFontSize(OcrRegion region, Rectangle regionBounds) {
        boolean vertical = region.writingMode().vertical();
        List<Integer> wordHeights = region.words().stream()
                .map(OcrWord::polygon)
                .filter(points -> points.size() >= 4)
                .map(points -> (int) Math.round(vertical
                        ? OcrRegion.uprightWidth(points)
                        : Math.hypot(
                                points.get(points.size() - 1).x() - points.get(0).x(),
                                points.get(points.size() - 1).y() - points.get(0).y())))
                .filter(height -> height > 1)
                .sorted()
                .toList();
        int measured = wordHeights.isEmpty()
                ? (vertical ? regionBounds.width : regionBounds.height)
                : wordHeights.get(wordHeights.size() / 2);
        return Math.max(8, Math.min(128, (int) Math.round(measured * 1.1)));
    }

    /**
     * Glyph geometry read from the ink itself. {@code inkHeight} is the median extent, across the
     * text line, of the ink of {@code words} words; {@code strokeWidth} is the typical stroke
     * thickness in pixels. Both are zero when the sample is too small to trust.
     */
    private record GlyphMetrics(double inkHeight, int words, double strokeWidth) {
        static final GlyphMetrics NONE = new GlyphMetrics(0, 0, 0);

        /**
         * Stroke thickness is compared with the em, recovered from the ink height, so type of one
         * weight lands on the same side of the threshold at every size.
         */
        boolean bold(SourceScript script) {
            if (words < 1 || inkHeight <= 0 || strokeWidth <= 0) return false;
            return strokeWidth / (inkHeight / script.inkPerEm) >= script.boldStrokePerEm;
        }
    }

    /**
     * What a script family looks like on the page, measured from rendered CJK and Latin faces in
     * ImageTranslationStyleEstimatorCalibrationTests. {@code inkPerEm} is the ink height of a line
     * as a share of the em (Latin: ascender and cap height; CJK: the ideograph face).
     * {@code boldStrokePerEm} is the stroke thickness, as a share of the em, between the regular
     * and the bold faces. Regular strokes measure about .06 to .08 em in CJK faces and .08 to .09
     * in Latin ones, so the CJK threshold sits lower.
     */
    private enum SourceScript {
        CJK(.92, .09),
        LATIN(.76, .099);

        private final double inkPerEm;
        private final double boldStrokePerEm;

        SourceScript(double inkPerEm, double boldStrokePerEm) {
            this.inkPerEm = inkPerEm;
            this.boldStrokePerEm = boldStrokePerEm;
        }

        static SourceScript of(String text) {
            int cjk = 0;
            int other = 0;
            for (int index = 0; index < text.length(); ) {
                int codePoint = text.codePointAt(index);
                index += Character.charCount(codePoint);
                if (!Character.isLetter(codePoint)) continue;
                switch (Character.UnicodeScript.of(codePoint)) {
                    case HAN, HIRAGANA, KATAKANA, HANGUL, BOPOMOFO -> cjk++;
                    default -> other++;
                }
            }
            return cjk > other ? CJK : LATIN;
        }
    }

    /**
     * Measures the source glyphs. Ink coverage is the distance from the background relative to the
     * 95th-percentile ink in the region, so summing it along a run of ink pixels gives the stroke
     * width with the antialiased edge counted fractionally; a hard threshold would add about a
     * pixel to every stroke and make small type look bold. Type blurred so far that no pixel
     * reaches full ink is the limit of this: it reads somewhat heavier than it is.
     */
    private static GlyphMetrics glyphMetrics(BufferedImage image, OcrRegion region, Color background) {
        List<Polygon> masks = new ArrayList<>();
        region.masks().forEach(points -> masks.add(OverlaySafetyPolicy.polygon(points)));
        Rectangle reach = null;
        for (Polygon mask : masks) {
            if (reach == null) reach = mask.getBounds();
            else reach.add(mask.getBounds());
        }
        for (OcrWord word : region.words()) {
            if (word.polygon().size() < 4) continue;
            Rectangle box = OverlaySafetyPolicy.polygon(word.polygon()).getBounds();
            if (reach == null) reach = box;
            else reach.add(box);
        }
        if (reach == null) return GlyphMetrics.NONE;
        reach = reach.intersection(new Rectangle(0, 0, image.getWidth(), image.getHeight()));
        if (reach.width <= 0 || reach.height <= 0) return GlyphMetrics.NONE;

        int width = reach.width;
        int height = reach.height;
        boolean[] inside = new boolean[width * height];
        for (Polygon mask : masks) {
            Rectangle box = mask.getBounds().intersection(reach);
            for (int y = box.y; y < box.y + box.height; y++) {
                for (int x = box.x; x < box.x + box.width; x++) {
                    int index = (y - reach.y) * width + x - reach.x;
                    if (!inside[index] && mask.contains(x + .5, y + .5)) inside[index] = true;
                }
            }
        }

        short[] distance = new short[width * height];
        int[] histogram = new int[MAXIMUM_COLOUR_DISTANCE + 1];
        int inkPixels = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(reach.x + x, reach.y + y);
                if ((argb >>> 24) <= 32) continue;
                int red = ((argb >> 16) & 0xff) - background.getRed();
                int green = ((argb >> 8) & 0xff) - background.getGreen();
                int blue = (argb & 0xff) - background.getBlue();
                int squared = red * red + green * green + blue * blue;
                if (squared < MINIMUM_COLOUR_DISTANCE_SQUARED) continue;
                short ink = (short) Math.min(MAXIMUM_COLOUR_DISTANCE, Math.round(Math.sqrt(squared)));
                distance[y * width + x] = ink;
                if (inside[y * width + x]) {
                    histogram[ink]++;
                    inkPixels++;
                }
            }
        }
        if (inkPixels == 0) return GlyphMetrics.NONE;
        int cumulative = 0;
        int strongest = MAXIMUM_COLOUR_DISTANCE;
        for (int value = 0; value <= MAXIMUM_COLOUR_DISTANCE; value++) {
            cumulative += histogram[value];
            if (cumulative >= inkPixels * .95) {
                strongest = value;
                break;
            }
        }

        double stroke = Math.min(
                strokeMedian(distance, inside, width, height, strongest, true),
                strokeMedian(distance, inside, width, height, strongest, false));
        List<Double> inkHeights = new ArrayList<>();
        for (OcrWord word : region.words()) {
            double inkHeight = wordInkHeight(word.polygon(), distance, reach, strongest);
            if (inkHeight > 0) inkHeights.add(inkHeight);
        }
        inkHeights.sort(Double::compare);
        return new GlyphMetrics(
                inkHeights.isEmpty() ? 0 : inkHeights.get(inkHeights.size() / 2),
                inkHeights.size(),
                stroke);
    }

    /**
     * Median length of the runs of ink along the scan lines, summed in coverage so a run is a
     * stroke width in pixels. Zero when the sample holds too few runs.
     */
    private static double strokeMedian(
            short[] distance, boolean[] inside, int width, int height, int strongest, boolean alongRows) {
        int lines = alongRows ? height : width;
        int length = alongRows ? width : height;
        int lineStep = alongRows ? width : 1;
        int pixelStep = alongRows ? 1 : width;
        int[] bins = new int[RUN_BINS];
        int runs = 0;
        for (int line = 0; line < lines; line++) {
            double run = 0;
            for (int position = 0; position <= length; position++) {
                int index = line * lineStep + position * pixelStep;
                if (position < length && inside[index] && distance[index] > 0) {
                    run += Math.min(1d, (double) distance[index] / strongest);
                    continue;
                }
                if (run >= MINIMUM_STROKE_RUN) {
                    bins[Math.min(RUN_BINS - 1, (int) (run * RUN_BINS_PER_PIXEL))]++;
                    runs++;
                }
                run = 0;
            }
        }
        if (runs < MINIMUM_STROKE_RUNS) return 0;
        int cumulative = 0;
        for (int bin = 0; bin < RUN_BINS; bin++) {
            cumulative += bins[bin];
            if (cumulative >= (runs + 1) / 2) return (bin + .5) / RUN_BINS_PER_PIXEL;
        }
        return 0;
    }

    /**
     * Extent of one word's ink across its text line, measured along the line's own normal so a
     * slightly skewed line is not stretched by its slope. Zero when the word is a tall, narrow
     * column, when it holds no ink, or when its ink is a sliver of the box (a dash or a stop).
     */
    private static double wordInkHeight(
            List<OcrPoint> points, short[] distance, Rectangle reach, int strongest) {
        if (points.size() < 4) return 0;
        OcrPoint origin = points.get(0);
        double lineX = points.get(1).x() - origin.x();
        double lineY = points.get(1).y() - origin.y();
        double lineLength = Math.hypot(lineX, lineY);
        if (lineLength < 1) return 0;
        double normalX = -lineY / lineLength;
        double normalY = lineX / lineLength;
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (OcrPoint point : points) {
            double offset = (point.x() - origin.x()) * normalX + (point.y() - origin.y()) * normalY;
            low = Math.min(low, offset);
            high = Math.max(high, offset);
        }
        double boxHeight = high - low;
        if (boxHeight < 2 || boxHeight > lineLength * MAXIMUM_WORD_HEIGHT_TO_WIDTH) return 0;

        Polygon polygon = OverlaySafetyPolicy.polygon(points);
        Rectangle box = polygon.getBounds().intersection(reach);
        int bands = (int) Math.ceil(boxHeight) + 1;
        int[] count = new int[bands];
        short[] peak = new short[bands];
        for (int y = box.y; y < box.y + box.height; y++) {
            for (int x = box.x; x < box.x + box.width; x++) {
                short ink = distance[(y - reach.y) * reach.width + x - reach.x];
                if (ink == 0 || !polygon.contains(x + .5, y + .5)) continue;
                double offset = (x + .5 - origin.x()) * normalX + (y + .5 - origin.y()) * normalY;
                int band = Math.max(0, Math.min(bands - 1, (int) Math.floor(offset - low)));
                count[band]++;
                if (ink > peak[band]) peak[band] = ink;
            }
        }
        int strongestBand = 0;
        for (int value : count) strongestBand = Math.max(strongestBand, value);
        if (strongestBand == 0) return 0;
        // A stray speck beyond the glyphs must not stretch the extent.
        int floor = Math.max(1, strongestBand / 20);
        int first = 0;
        while (count[first] < floor) first++;
        int last = bands - 1;
        while (count[last] < floor) last--;
        double inkHeight = last - first + 1
                - (1 - Math.min(1d, (double) peak[first] / strongest))
                - (1 - Math.min(1d, (double) peak[last] / strongest));
        return inkHeight < 3 || inkHeight < boxHeight * MINIMUM_INK_TO_BOX_HEIGHT ? 0 : inkHeight;
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

    private record PixelSample(List<Color> colours) {
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
