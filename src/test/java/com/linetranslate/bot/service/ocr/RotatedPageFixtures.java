package com.linetranslate.bot.service.ocr;

import java.util.ArrayList;
import java.util.List;

/**
 * Paragraphs of a page that was turned a quarter, built the way the provider reports them.
 *
 * <p>Each paragraph is laid out in the upright frame of the page (glyph boxes left to right, one
 * line per paragraph), and every point is then moved to where a camera that turned the page
 * {@code turns} quarters clockwise sees it, keeping the order of the vertices: vertex 0 stays the
 * top-left of the line as it reads, so the edge from vertex 0 to vertex 1 is the reading direction
 * and points at {@code turns} x 90 degrees. The map here is written from the geometry of turning a
 * picture and shares no code with {@link QuarterTurns}, which is what undoes it in the tests.
 */
final class RotatedPageFixtures {

    static final int GLYPH_WIDTH = 12;
    static final int GLYPH_HEIGHT = 20;

    /** Where a punctuation mark sits inside the line band of its paragraph, in the frame it reads in. */
    enum Marks { NONE, LOW, HIGH }

    /** The frame a symbol's own box is reported in. */
    enum SymbolFrame {
        /** Vertex order follows the line, as the box of a glyph on a turned page does. */
        FOLLOWS_LINE,
        /** Vertex 0 at the top-left of the image, as the box of an upright glyph in a column does. */
        UPRIGHT_IN_IMAGE
    }

    private final int uprightWidth;
    private final int uprightHeight;
    private final int turns;

    /** A page that is {@code uprightWidth x uprightHeight} when upright, turned {@code turns} quarters clockwise. */
    RotatedPageFixtures(int uprightWidth, int uprightHeight, int turns) {
        this.uprightWidth = uprightWidth;
        this.uprightHeight = uprightHeight;
        this.turns = turns;
    }

    /** Width of the turned page, which is the width of the image the provider saw. */
    int pageWidth() {
        return turns % 2 == 1 ? uprightHeight : uprightWidth;
    }

    int pageHeight() {
        return turns % 2 == 1 ? uprightWidth : uprightHeight;
    }

    /** A point of the upright page, where it lands once the page is turned clockwise. */
    OcrPoint turn(int x, int y) {
        return switch (Math.floorMod(turns, 4)) {
            case 1 -> new OcrPoint(uprightHeight - y, x);
            case 2 -> new OcrPoint(uprightWidth - x, uprightHeight - y);
            case 3 -> new OcrPoint(y, uprightWidth - x);
            default -> new OcrPoint(x, y);
        };
    }

    private List<OcrPoint> turnedBox(int x, int y, int width, int height) {
        return List.of(turn(x, y), turn(x + width, y), turn(x + width, y + height), turn(x, y + height));
    }

    /** The axis-aligned box of a turned box, listed from the top-left of the image. */
    private List<OcrPoint> imageBox(int x, int y, int width, int height) {
        List<OcrPoint> corners = turnedBox(x, y, width, height);
        int left = corners.stream().mapToInt(OcrPoint::x).min().orElseThrow();
        int right = corners.stream().mapToInt(OcrPoint::x).max().orElseThrow();
        int top = corners.stream().mapToInt(OcrPoint::y).min().orElseThrow();
        int bottom = corners.stream().mapToInt(OcrPoint::y).max().orElseThrow();
        return List.of(new OcrPoint(left, top), new OcrPoint(right, top),
                new OcrPoint(right, bottom), new OcrPoint(left, bottom));
    }

    OcrRegion latin(String id, String text, int x, int y, float confidence, int order) {
        return paragraph(id, text, "en", x, y, confidence, order, Marks.NONE, SymbolFrame.FOLLOWS_LINE);
    }

    OcrRegion cjk(String id, String text, int x, int y, Marks marks, SymbolFrame frame, int order) {
        return paragraph(id, text, "ja", x, y, .95f, order, marks, frame);
    }

    /**
     * One line of {@code text} starting at ({@code x}, {@code y}) of the upright page. Spaces end a
     * word and take a slot; every other character is a symbol, and 、 and 。 are small marks placed
     * by {@code marks}.
     */
    OcrRegion paragraph(String id, String text, String language, int x, int y, float confidence, int order,
            Marks marks, SymbolFrame frame) {
        List<OcrWord> words = new ArrayList<>();
        List<OcrSymbol> symbols = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int wordStart = x;
        int cursor = x;
        for (int index = 0; index <= text.length(); index++) {
            boolean end = index == text.length() || text.charAt(index) == ' ';
            if (end) {
                if (!symbols.isEmpty()) {
                    words.add(new OcrWord(word.toString(),
                            turnedBox(wordStart, y, cursor - wordStart, GLYPH_HEIGHT),
                            confidence, true, List.copyOf(symbols)));
                    symbols.clear();
                    word.setLength(0);
                }
                cursor += index == text.length() ? 0 : GLYPH_WIDTH;
                wordStart = cursor;
                continue;
            }
            char character = text.charAt(index);
            boolean mark = character == '、' || character == '。';
            int top = y;
            int height = GLYPH_HEIGHT;
            if (mark && marks != Marks.NONE) {
                height = 6;
                top = marks == Marks.LOW ? y + 13 : y + 1;
            }
            symbols.add(new OcrSymbol(String.valueOf(character),
                    frame == SymbolFrame.FOLLOWS_LINE
                            ? turnedBox(cursor, top, GLYPH_WIDTH, height)
                            : imageBox(cursor, top, GLYPH_WIDTH, height),
                    confidence, true));
            word.append(character);
            cursor += GLYPH_WIDTH;
        }
        int width = cursor - x;
        return new OcrRegion(id, text, turnedBox(x, y, width, GLYPH_HEIGHT), words, confidence, true,
                OcrBlockType.TEXT, List.of(new OcrDetectedLanguage(language, confidence)), order);
    }

    /** A word of a block, placed on the upright page. */
    record Placed(String text, int x, int y, int width, int height) {
    }

    /**
     * A paragraph holding several rows and columns of words, such as a menu: each word has the
     * box given, split evenly among its characters, and the paragraph's box is the union.
     */
    OcrRegion block(String id, String language, float confidence, int order, Placed... placed) {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        List<OcrWord> words = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Placed word : placed) {
            left = Math.min(left, word.x());
            top = Math.min(top, word.y());
            right = Math.max(right, word.x() + word.width());
            bottom = Math.max(bottom, word.y() + word.height());
            List<OcrSymbol> symbols = new ArrayList<>();
            int count = word.text().length();
            for (int index = 0; index < count; index++) {
                int from = word.x() + word.width() * index / count;
                int to = word.x() + word.width() * (index + 1) / count;
                symbols.add(new OcrSymbol(String.valueOf(word.text().charAt(index)),
                        turnedBox(from, word.y(), to - from, word.height()), confidence, true));
            }
            words.add(new OcrWord(word.text(), turnedBox(word.x(), word.y(), word.width(), word.height()),
                    confidence, true, symbols));
            text.append(text.length() == 0 ? "" : " ").append(word.text());
        }
        return new OcrRegion(id, text.toString(), turnedBox(left, top, right - left, bottom - top), words,
                confidence, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage(language, confidence)), order);
    }

    /**
     * A paragraph with a single edge pointing at {@code degrees} clockwise from +x, whatever the
     * page was turned by: a region that is off every quarter by a chosen amount.
     */
    static OcrRegion atAngle(String id, String text, double degrees, int x, int y, float confidence, int order) {
        double radians = Math.toRadians(degrees);
        double along = text.length() * GLYPH_WIDTH;
        double ax = Math.cos(radians) * along;
        double ay = Math.sin(radians) * along;
        double bx = -Math.sin(radians) * GLYPH_HEIGHT;
        double by = Math.cos(radians) * GLYPH_HEIGHT;
        List<OcrPoint> polygon = List.of(
                new OcrPoint(x, y),
                new OcrPoint((int) Math.round(x + ax), (int) Math.round(y + ay)),
                new OcrPoint((int) Math.round(x + ax + bx), (int) Math.round(y + ay + by)),
                new OcrPoint((int) Math.round(x + bx), (int) Math.round(y + by)));
        // One word whose symbols all carry the paragraph's own vertices: enough for a provider
        // response, which rebuilds the paragraph's text from its symbols.
        List<OcrSymbol> symbols = text.chars().filter(character -> character != ' ')
                .mapToObj(character -> new OcrSymbol(String.valueOf((char) character), polygon, confidence, true))
                .toList();
        List<OcrWord> words = List.of(new OcrWord(text.replace(" ", ""), polygon, confidence, true, symbols));
        return new OcrRegion(id, text, polygon, words, confidence, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage("en", confidence)), order);
    }
}
