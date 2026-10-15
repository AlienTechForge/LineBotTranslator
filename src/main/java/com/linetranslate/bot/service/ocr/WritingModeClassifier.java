package com.linetranslate.bot.service.ocr;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides whether a region that stays whole is a single column of upright vertical writing.
 *
 * <p>Vision reads a vertical menu as an axis-aligned paragraph, one glyph per word, so the frame
 * says "horizontal" and only the geometry shows the column. A region is a column of vertical
 * Chinese or Japanese ({@link WritingMode#VERTICAL_RL}) only when all of these hold:
 * <ul>
 *   <li>it is at least {@value #MIN_COLUMN_ASPECT} times taller than it is wide;</li>
 *   <li>at least {@value #MIN_STACKED_SHARE} of its consecutive boxes (words, or the symbols of a
 *       single word) lie below their predecessor and overlap it horizontally by at least
 *       {@value #MIN_STACK_OVERLAP}: a stack, not a block of text wrapped two glyphs per line,
 *       whose nearest neighbour is beside it;</li>
 *   <li>the median box is about as wide as it is tall ({@value #MIN_GLYPH_ASPECT} to
 *       {@value #MAX_GLYPH_ASPECT}): glyphs, not lines;</li>
 *   <li>most of its letters are CJK.</li>
 * </ul>
 * A stack of single Latin letters is {@link WritingMode#VERTICAL_LR}. Anything else keeps the mode
 * its frame gave it, so a region that is not a column is laid out exactly as before.
 */
final class WritingModeClassifier {

    /** An upright height of at least this multiple of the upright width. */
    static final double MIN_COLUMN_ASPECT = 1.8;
    /** Share of consecutive boxes that must be stacked. */
    static final double MIN_STACKED_SHARE = .80;
    /** Horizontal overlap, relative to the narrower box, for two boxes to share a column. */
    static final double MIN_STACK_OVERLAP = .5;
    static final double MIN_GLYPH_ASPECT = .6;
    static final double MAX_GLYPH_ASPECT = 1.6;

    private WritingModeClassifier() {
    }

    /**
     * The mode of a region the segmenter keeps whole: {@code VERTICAL_RL} or {@code VERTICAL_LR}
     * for a column of upright vertical writing, the region's own mode otherwise.
     */
    static WritingMode columnMode(OcrRegion region) {
        if (region == null || region.writingMode() != WritingMode.HORIZONTAL) {
            return region == null ? WritingMode.HORIZONTAL : region.writingMode();
        }
        List<OcrPoint> upright = region.uprightPolygon();
        if (upright.size() < 4) return region.writingMode();
        double width = distance(upright.get(0), upright.get(1));
        double height = distance(upright.get(0), upright.get(3));
        if (width <= 0 || height < width * MIN_COLUMN_ASPECT) return region.writingMode();

        List<Glyph> glyphs = glyphs(region);
        if (glyphs.size() < 2) return region.writingMode();
        List<Glyph> sorted = glyphs.stream()
                .sorted(Comparator.comparingInt((Glyph glyph) -> glyph.box().y)
                        .thenComparingInt(glyph -> glyph.box().x))
                .toList();
        int stacked = 0;
        for (int index = 1; index < sorted.size(); index++) {
            if (stacked(sorted.get(index - 1).box(), sorted.get(index).box())) stacked++;
        }
        if (stacked < Math.ceil((sorted.size() - 1) * MIN_STACKED_SHARE)) return region.writingMode();

        double aspect = medianAspect(glyphs);
        if (aspect < MIN_GLYPH_ASPECT || aspect > MAX_GLYPH_ASPECT) return region.writingMode();

        List<String> texts = glyphs.stream().map(Glyph::text).toList();
        if (isMostlyCjk(texts)) return WritingMode.VERTICAL_RL;
        if (texts.stream().allMatch(WritingModeClassifier::isSingleLatinLetter)) return WritingMode.VERTICAL_LR;
        return region.writingMode();
    }

    /**
     * Whether a strict majority of the letters of {@code texts} are CJK. Letters of the common and
     * inherited scripts, such as the prolonged sound mark of katakana, say nothing about the script
     * and are left out. A tie, or no letters at all, is not mostly CJK.
     */
    static boolean isMostlyCjk(Iterable<String> texts) {
        long cjk = 0;
        long letters = 0;
        for (String value : texts) {
            for (int codePoint : value.codePoints().toArray()) {
                if (!Character.isLetter(codePoint)) continue;
                Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
                if (script == Character.UnicodeScript.COMMON
                        || script == Character.UnicodeScript.INHERITED) continue;
                letters++;
                if (script == Character.UnicodeScript.HAN
                        || script == Character.UnicodeScript.HIRAGANA
                        || script == Character.UnicodeScript.KATAKANA
                        || script == Character.UnicodeScript.HANGUL
                        || script == Character.UnicodeScript.BOPOMOFO) cjk++;
            }
        }
        return letters > 0 && cjk * 2 > letters;
    }

    /** A glyph-sized unit and its box: the words of the region, or the symbols of its one word. */
    private record Glyph(String text, Rectangle box) {
    }

    private static List<Glyph> glyphs(OcrRegion region) {
        List<Glyph> result = new ArrayList<>();
        if (region.words().size() >= 2) {
            for (OcrWord word : region.words()) {
                if (word.polygon().size() < 4 || word.text().isBlank()) return List.of();
                result.add(new Glyph(word.text(), OverlaySafetyPolicy.polygon(word.polygon()).getBounds()));
            }
        } else if (region.words().size() == 1) {
            for (OcrSymbol symbol : region.words().get(0).symbols()) {
                if (symbol.polygon().size() < 4 || symbol.text().isBlank()) return List.of();
                result.add(new Glyph(symbol.text(), OverlaySafetyPolicy.polygon(symbol.polygon()).getBounds()));
            }
        }
        return result;
    }

    /** The next box lies below its predecessor, in its column rather than beside it. */
    private static boolean stacked(Rectangle previous, Rectangle next) {
        if (next.y + next.height / 2 <= previous.y + previous.height / 2) return false;
        int across = Math.min(previous.x + previous.width, next.x + next.width) - Math.max(previous.x, next.x);
        if (across <= 0 || (double) across / Math.max(1, Math.min(previous.width, next.width)) < MIN_STACK_OVERLAP) {
            return false;
        }
        int along = Math.min(previous.y + previous.height, next.y + next.height) - Math.max(previous.y, next.y);
        return along <= 0 || (double) along / Math.max(1, Math.min(previous.height, next.height)) < MIN_STACK_OVERLAP;
    }

    private static double medianAspect(List<Glyph> glyphs) {
        List<Double> values = glyphs.stream()
                .map(glyph -> (double) glyph.box().width / Math.max(1, glyph.box().height))
                .sorted().toList();
        return values.get(values.size() / 2);
    }

    private static boolean isSingleLatinLetter(String value) {
        return value.codePointCount(0, value.length()) == 1
                && Character.isLetter(value.codePointAt(0))
                && Character.UnicodeScript.of(value.codePointAt(0)) == Character.UnicodeScript.LATIN;
    }

    private static double distance(OcrPoint from, OcrPoint to) {
        return Math.hypot(to.x() - from.x(), to.y() - from.y());
    }
}
