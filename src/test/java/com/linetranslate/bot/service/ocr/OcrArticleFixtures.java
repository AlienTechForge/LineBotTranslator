package com.linetranslate.bot.service.ocr;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry of a Japanese article screenshot, shaped like one that lost the last line of a paragraph
 * in production (1260x1308, three paragraphs of four, four and six lines). Glyphs are 52px square
 * and a line is 76px from the next, so the paragraphs come out 280, 280 and 432px high, as Vision
 * reported them. Words abut one another and each line runs the full width of the column, the way a
 * justified paragraph does.
 */
final class OcrArticleFixtures {
    static final int LEFT = 39;
    static final int GLYPH = 52;
    static final int PITCH = 76;
    static final int GLYPHS_PER_LINE = 22;
    static final int LINE_WIDTH = GLYPH * GLYPHS_PER_LINE;
    private static final int GLYPHS_PER_WORD = 2;
    private static final String SAMPLE =
            "自分に対する抗議集会を受けて頼清徳総統は言い返した民進党が国民党を批判するとき最もよく使う言い回しである";

    private OcrArticleFixtures() {
    }

    /** A paragraph of {@code lines} full lines starting at {@code top}, detected as Japanese. */
    static OcrRegion paragraph(String id, int top, int lines, int order) {
        return paragraph(id, top, lines, "ja", order);
    }

    static OcrRegion paragraph(String id, int top, int lines, String language, int order) {
        return paragraph(id, top, lines, GLYPHS_PER_LINE, language, order);
    }

    /** A narrower column: every line is {@code glyphsPerLine} glyphs (an even number) wide. */
    static OcrRegion paragraph(
            String id, int top, int lines, int glyphsPerLine, String language, int order) {
        List<OcrWord> words = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int line = 0; line < lines; line++) {
            for (int word = 0; word < glyphsPerLine / GLYPHS_PER_WORD; word++) {
                int glyphs = (line * glyphsPerLine) + word * GLYPHS_PER_WORD;
                String value = glyphs(glyphs, GLYPHS_PER_WORD);
                words.add(new OcrWord(value,
                        rectangle(LEFT + word * GLYPH * GLYPHS_PER_WORD, top + line * PITCH,
                                GLYPH * GLYPHS_PER_WORD, GLYPH),
                        .98f, true));
                text.append(value);
            }
        }
        return new OcrRegion(id, text.toString(),
                rectangle(LEFT, top, GLYPH * glyphsPerLine, height(lines)),
                words, .97f, true, OcrBlockType.TEXT,
                language == null ? List.of() : List.of(new OcrDetectedLanguage(language, .97f)),
                order);
    }

    /**
     * A block holding only the wrapped end of the paragraph above: one word, aligned with the
     * paragraph's left edge, one glyph high and with no detected language, as Vision reports a
     * couple of characters.
     */
    static OcrRegion tail(String id, String text, int top, int order) {
        int width = GLYPH * text.codePointCount(0, text.length());
        return new OcrRegion(id, text, rectangle(LEFT, top, width, GLYPH),
                List.of(new OcrWord(text, rectangle(LEFT, top, width, GLYPH), .95f, true)),
                .95f, true, OcrBlockType.TEXT, List.of(), order);
    }

    static int height(int lines) {
        return (lines - 1) * PITCH + GLYPH;
    }

    /** Where the line after a paragraph of {@code lines} lines starting at {@code top} begins. */
    static int nextLineTop(int top, int lines) {
        return top + lines * PITCH;
    }

    static List<OcrPoint> rectangle(int x, int y, int width, int height) {
        return List.of(new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height));
    }

    private static String glyphs(int start, int count) {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < count; index++) {
            value.append(SAMPLE.charAt((start + index) % SAMPLE.length()));
        }
        return value.toString();
    }
}
