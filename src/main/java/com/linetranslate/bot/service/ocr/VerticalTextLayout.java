package com.linetranslate.bot.service.ocr;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;

/**
 * Lays a translation out as vertical writing: upright glyphs top to bottom in columns that run
 * from right to left, with the vertical presentation forms of the punctuation. It sits beside the
 * horizontal engine of {@link ImageTranslationOverlayRenderer} and shares its search (largest size
 * first, every condensing step at a size before the next smaller size, the same floors), but the
 * size is capped by the width of the cell, which is the thickness of a column, not by its height.
 *
 * <p>The text is cut into units that are placed and drawn one by one:
 * <ul>
 *   <li>every Han, kana or Hangul syllable and every full-width character is one upright unit of
 *       one em, and a character with a vertical presentation form is replaced by it first;</li>
 *   <li>{@code ー ～ 〜} have no such form and are drawn turned a quarter clockwise;</li>
 *   <li>one or two ASCII digits are set side by side in one unit of one em (tate-chu-yoko),
 *       narrowed to the width of the column;</li>
 *   <li>any other run of Latin letters, digits and marks is one unit turned a quarter clockwise,
 *       as long as the run is wide, and broken between characters when it is longer than a column;</li>
 *   <li>a space is half an em, and a line feed ends the column.</li>
 * </ul>
 * Not done here, and out of scope: line-breaking rules (kinsoku) and moving small kana. The
 * horizontal engine has neither.
 */
final class VerticalTextLayout {

    /** Distance from one column to the next, in type sizes. */
    static final double PITCH = 1.2;
    /** A space between units, in em. */
    static final double SPACE = .5;
    /** The longest run of digits set as one upright unit. */
    static final int TATE_CHU_YOKO_DIGITS = 2;
    private static final double EPSILON = 1e-6;

    private VerticalTextLayout() {
    }

    enum Kind {
        /** One em, upright. */
        UPRIGHT,
        /** One em, turned a quarter clockwise about its centre. */
        TURNED,
        /** One or two digits side by side in one em. */
        TATE_CHU_YOKO,
        /** A run of text turned a quarter clockwise, as wide as the run is. */
        SIDEWAYS,
        /** Half an em. */
        SPACE,
        /** Ends the column. */
        COLUMN_BREAK
    }

    record Unit(Kind kind, String value) {
    }

    /** A rectangle of the drawing frame that holds columns: left, top, width, height. */
    record Cell(int x, int y, int width, int height) {
    }

    /** A unit and where it starts along its column, in pixels from the top of the column. */
    record Placed(Unit unit, double offset) {
    }

    /** One column: the cell it is in, its place in the cell (0 is the rightmost) and its units. */
    record Column(int cell, int index, List<Placed> units, double length) {
        Column {
            units = List.copyOf(units);
        }
    }

    /**
     * A layout at one size and condensing step. {@code font} is the face at {@code size}, not
     * condensed: the condensing is a scale of the vertical axis applied when drawing.
     */
    record Layout(Font font, int size, double condense, List<Unit> units, List<Column> columns,
            boolean fits) {
        Layout {
            units = List.copyOf(units);
            columns = List.copyOf(columns);
        }
    }

    // --- units -------------------------------------------------------------------------------

    /**
     * The text with the characters that have a vertical presentation form replaced by it. The font
     * has to cover this text, not the one it came from, because these are the glyphs that are drawn.
     */
    static String mapped(String value) {
        StringBuilder result = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> result.appendCodePoint(presentationForm(codePoint)));
        return result.toString();
    }

    /** The vertical presentation form of a character, or the character itself when it has none. */
    static int presentationForm(int codePoint) {
        return switch (codePoint) {
            case '、' -> 0xFE11;
            case '。' -> 0xFE12;
            case '，' -> 0xFE10;
            case '：' -> 0xFE13;
            case '；' -> 0xFE14;
            case '！' -> 0xFE15;
            case '？' -> 0xFE16;
            case '「' -> 0xFE41;
            case '」' -> 0xFE42;
            case '『' -> 0xFE43;
            case '』' -> 0xFE44;
            case '（' -> 0xFE35;
            case '）' -> 0xFE36;
            case '【' -> 0xFE3B;
            case '】' -> 0xFE3C;
            case '《' -> 0xFE3D;
            case '》' -> 0xFE3E;
            case '〈' -> 0xFE3F;
            case '〉' -> 0xFE40;
            case '｛' -> 0xFE37;
            case '｝' -> 0xFE38;
            case '〔' -> 0xFE39;
            case '〕' -> 0xFE3A;
            case '…' -> 0xFE19;
            case '‥' -> 0xFE30;
            case '—', '―' -> 0xFE31;
            case '–' -> 0xFE32;
            default -> codePoint;
        };
    }

    /** Characters that have no vertical form and are drawn turned a quarter clockwise. */
    static boolean turnsInColumn(int codePoint) {
        return codePoint == 'ー' || codePoint == '～' || codePoint == '〜';
    }

    /** Characters that stand upright in a column, one em each. */
    static boolean standsUpright(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
                || script == Character.UnicodeScript.BOPOMOFO) {
            return true;
        }
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.VERTICAL_FORMS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_FORMS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY
                || block == Character.UnicodeBlock.ENCLOSED_CJK_LETTERS_AND_MONTHS
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HIRAGANA;
    }

    /** The units of {@code value}, after the presentation forms are applied. */
    static List<Unit> units(String value) {
        int[] codePoints = mapped(value).codePoints().toArray();
        List<Unit> units = new ArrayList<>();
        int index = 0;
        while (index < codePoints.length) {
            int codePoint = codePoints[index];
            if (codePoint == '\n' || codePoint == '\r') {
                while (index < codePoints.length && (codePoints[index] == '\n' || codePoints[index] == '\r')) {
                    index++;
                }
                if (!units.isEmpty() && units.get(units.size() - 1).kind() != Kind.COLUMN_BREAK) {
                    units.add(new Unit(Kind.COLUMN_BREAK, ""));
                }
            } else if (Character.isWhitespace(codePoint)) {
                while (index < codePoints.length && Character.isWhitespace(codePoints[index])
                        && codePoints[index] != '\n' && codePoints[index] != '\r') {
                    index++;
                }
                units.add(new Unit(Kind.SPACE, " "));
            } else if (standsUpright(codePoint)) {
                units.add(new Unit(turnsInColumn(codePoint) ? Kind.TURNED : Kind.UPRIGHT,
                        new String(Character.toChars(codePoint))));
                index++;
            } else {
                int end = index;
                while (end < codePoints.length && !Character.isWhitespace(codePoints[end])
                        && !standsUpright(codePoints[end])) {
                    end++;
                }
                String run = new String(codePoints, index, end - index);
                units.add(new Unit(isShortDigitRun(run) ? Kind.TATE_CHU_YOKO : Kind.SIDEWAYS, run));
                index = end;
            }
        }
        // A space or a break at the end of the text has nothing after it to separate.
        while (!units.isEmpty() && (units.get(units.size() - 1).kind() == Kind.SPACE
                || units.get(units.size() - 1).kind() == Kind.COLUMN_BREAK)) {
            units.remove(units.size() - 1);
        }
        return List.copyOf(units);
    }

    private static boolean isShortDigitRun(String run) {
        return run.length() <= TATE_CHU_YOKO_DIGITS && run.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    // --- fit ---------------------------------------------------------------------------------

    /** The distance from one column to the next at a type size, in whole pixels. */
    static int pitch(int size) {
        return (int) Math.round(size * PITCH);
    }

    /**
     * The largest layout of {@code value} in {@code cells} that fits, or one that does not fit
     * when nothing does. The search is the horizontal engine's: the largest size first, every
     * condensing step at a size before the next smaller size, a compact label held to a share of
     * its source size unless nothing fits above it, and the readable floor as a last resort. What
     * differs is the cap: a column is as thick as its type, so the widest cell caps the size.
     *
     * @param baseFont       a face that covers the mapped text
     * @param sourceFontSize the size the source was set in
     */
    static Layout fit(Graphics2D graphics, String value, List<Cell> cells, Font baseFont,
            int sourceFontSize, boolean compactLabel) {
        List<Unit> units = units(value);
        int widest = cells.stream().mapToInt(Cell::width).max().orElse(0);
        int maximum = Math.max(ImageTranslationOverlayRenderer.MIN_FONT_SIZE,
                Math.min(sourceFontSize, widest - 2));
        int compactBasis = Math.min(sourceFontSize, widest);
        int compactFloor = compactLabel
                ? Math.min(maximum, Math.max(ImageTranslationOverlayRenderer.MIN_FONT_SIZE,
                        (int) Math.ceil(compactBasis * .55)))
                : ImageTranslationOverlayRenderer.MIN_FONT_SIZE;
        Layout preferred = fitBetween(graphics, units, cells, baseFont, maximum, compactFloor);
        if (preferred != null) return preferred;
        if (compactFloor > ImageTranslationOverlayRenderer.READABLE_LABEL_FLOOR) {
            Layout smaller = fitBetween(graphics, units, cells, baseFont, compactFloor - 1,
                    ImageTranslationOverlayRenderer.READABLE_LABEL_FLOOR);
            if (smaller != null) return smaller;
        }
        double[] steps = ImageTranslationOverlayRenderer.CONDENSE_STEPS;
        return layoutAt(graphics, units, cells, baseFont, ImageTranslationOverlayRenderer.MIN_FONT_SIZE,
                steps[steps.length - 1]);
    }

    private static Layout fitBetween(Graphics2D graphics, List<Unit> units, List<Cell> cells, Font baseFont,
            int highest, int lowest) {
        if (highest < lowest) return null;
        for (int size = highest; size >= lowest; size--) {
            for (double condense : ImageTranslationOverlayRenderer.CONDENSE_STEPS) {
                Layout layout = layoutAt(graphics, units, cells, baseFont, size, condense);
                if (layout.fits()) return layout;
            }
        }
        return null;
    }

    /**
     * The units placed at one size and condensing step: greedily, top to bottom within a column,
     * right to left across the columns of a cell, then cell by cell. A cell holds as many columns
     * as its width takes at the pitch (a column is {@code size} thick, so {@code n} columns need
     * {@code (n - 1) * pitch + size}) of as many ems as its height takes, with a pixel of slack on
     * each axis as the horizontal engine leaves. Fits only if every unit is placed.
     */
    static Layout layoutAt(Graphics2D graphics, List<Unit> units, List<Cell> cells, Font baseFont,
            int size, double condense) {
        Font font = baseFont.deriveFont((float) size);
        FontMetrics metrics = graphics.getFontMetrics(font);
        Filler filler = new Filler(cells, size, condense);
        for (Unit unit : units) {
            if (!filler.place(unit, metrics)) {
                return new Layout(font, size, condense, units, filler.columns(), false);
            }
        }
        return new Layout(font, size, condense, units, filler.columns(), true);
    }

    /** The cursor that fills columns, cell after cell. */
    private static final class Filler {
        private final List<Cell> cells;
        private final int size;
        private final double condense;
        private final int pitch;
        private final List<Column> columns = new ArrayList<>();
        private final List<Placed> current = new ArrayList<>();
        private int cell = -1;
        private int index;
        private double position;
        private double trailingSpace;
        private boolean open;

        Filler(List<Cell> cells, int size, double condense) {
            this.cells = cells;
            this.size = size;
            this.condense = condense;
            this.pitch = pitch(size);
        }

        List<Column> columns() {
            List<Column> all = new ArrayList<>(columns);
            if (open && !current.isEmpty()) all.add(snapshot());
            return all;
        }

        private Column snapshot() {
            return new Column(cell, index, current, position - trailingSpace);
        }

        private int capacity(Cell value) {
            int available = value.width() - 2;
            return available < size ? 0 : (available - size) / pitch + 1;
        }

        private double available() {
            return cells.get(cell).height() - 2;
        }

        /** Starts the next column, in the next cell once this one holds all it can. False at the end. */
        private boolean nextColumn() {
            closeColumn();
            if (cell >= 0 && index + 1 < capacity(cells.get(cell))) {
                index++;
            } else {
                int next = cell + 1;
                while (next < cells.size() && capacity(cells.get(next)) == 0) next++;
                if (next >= cells.size()) {
                    cell = cells.size();
                    open = false;
                    return false;
                }
                cell = next;
                index = 0;
            }
            position = 0;
            trailingSpace = 0;
            open = true;
            return true;
        }

        private void closeColumn() {
            if (open && !current.isEmpty()) columns.add(snapshot());
            current.clear();
            open = false;
        }

        boolean place(Unit unit, FontMetrics metrics) {
            if (cell >= cells.size()) return false;
            if (cell < 0 && !nextColumn()) return false;
            switch (unit.kind()) {
                case COLUMN_BREAK -> {
                    // A break at the very start of a column is nothing to end.
                    if (!current.isEmpty() && !nextColumn()) return false;
                    return true;
                }
                case SPACE -> {
                    double advance = size * condense * SPACE;
                    // A space hangs: it is never the first thing in a column, and a column it does
                    // not fit in simply ends there.
                    if (current.isEmpty()) return true;
                    if (position + advance > available() + EPSILON) return nextColumn();
                    current.add(new Placed(unit, position));
                    position += advance;
                    trailingSpace += advance;
                    return true;
                }
                case SIDEWAYS -> {
                    return placeRun(unit, metrics);
                }
                default -> {
                    return placeOne(unit, size * condense);
                }
            }
        }

        private boolean placeOne(Unit unit, double advance) {
            if (position + advance > available() + EPSILON) {
                if (current.isEmpty() || !nextColumn()) return false;
                if (advance > available() + EPSILON) return false;
            }
            current.add(new Placed(unit, position));
            position += advance;
            trailingSpace = 0;
            return true;
        }

        /**
         * A run of text turned a quarter turn is as long as it is wide. One that does not fit the
         * rest of its column moves to the next, and one longer than a whole column is broken between
         * characters, as the horizontal engine breaks a word wider than a line.
         */
        private boolean placeRun(Unit unit, FontMetrics metrics) {
            double whole = metrics.stringWidth(unit.value()) * condense;
            if (position + whole <= available() + EPSILON) return placeOne(unit, whole);
            if (!current.isEmpty() && whole <= available() + EPSILON) {
                if (!nextColumn()) return false;
                return placeOne(unit, whole);
            }
            if (!current.isEmpty() && !nextColumn()) return false;
            int[] codePoints = unit.value().codePoints().toArray();
            int start = 0;
            while (start < codePoints.length) {
                int end = start + 1;
                while (end < codePoints.length && metrics.stringWidth(new String(codePoints, start, end + 1 - start))
                        * condense + position <= available() + EPSILON) {
                    end++;
                }
                String piece = new String(codePoints, start, end - start);
                double advance = metrics.stringWidth(piece) * condense;
                if (position + advance > available() + EPSILON) {
                    if (current.isEmpty() || !nextColumn()) return false;
                    if (advance > available() + EPSILON) return false;
                }
                current.add(new Placed(new Unit(Kind.SIDEWAYS, piece), position));
                position += advance;
                trailingSpace = 0;
                start = end;
                if (start < codePoints.length && !nextColumn()) return false;
            }
            return true;
        }
    }

    // --- draw --------------------------------------------------------------------------------

    /**
     * Draws a layout in the frame the cells are in. A cell's columns are centred across it as a
     * block, the rightmost first. A layout that is one column in one cell is also centred along
     * its length, as a block of horizontal lines is centred in its cell; any other starts at the
     * top. The caller sets the colour, the clip and the frame.
     */
    static void draw(Graphics2D graphics, Layout layout, List<Cell> cells) {
        graphics.setFont(layout.font());
        FontMetrics metrics = graphics.getFontMetrics();
        int size = layout.size();
        int pitch = pitch(size);
        boolean centred = cells.size() == 1 && layout.columns().size() == 1;
        for (int cellIndex = 0; cellIndex < cells.size(); cellIndex++) {
            Cell cell = cells.get(cellIndex);
            int owner = cellIndex;
            List<Column> inCell = layout.columns().stream().filter(column -> column.cell() == owner).toList();
            if (inCell.isEmpty()) continue;
            double block = (inCell.size() - 1) * (double) pitch + size;
            double left = cell.x() + (cell.width() - block) / 2.0;
            for (Column column : inCell) {
                double centre = left + block - size / 2.0 - column.index() * (double) pitch;
                double top = cell.y() + 1
                        + (centred ? Math.max(0, (cell.height() - 2 - column.length()) / 2.0) : 0);
                for (Placed placed : column.units()) {
                    drawUnit(graphics, metrics, placed.unit(), centre, top + placed.offset(), size,
                            layout.condense());
                }
            }
        }
    }

    private static void drawUnit(Graphics2D graphics, FontMetrics metrics, Unit unit, double centre,
            double top, int size, double condense) {
        if (unit.kind() == Kind.SPACE || unit.kind() == Kind.COLUMN_BREAK) return;
        AffineTransform saved = graphics.getTransform();
        try {
            graphics.translate(centre, top);
            // Glyphs narrow along the line, the axis the units advance on, as a horizontal line's
            // glyphs narrow along theirs.
            graphics.scale(1d, condense);
            double ascent = metrics.getAscent();
            double descent = metrics.getDescent();
            double width = metrics.stringWidth(unit.value());
            switch (unit.kind()) {
                case UPRIGHT -> graphics.drawString(unit.value(), (float) (-width / 2),
                        (float) (ascent - (ascent + descent - size) / 2));
                case TATE_CHU_YOKO -> {
                    graphics.scale(Math.min(1d, size / Math.max(1d, width)), 1d);
                    graphics.drawString(unit.value(), (float) (-width / 2),
                            (float) (ascent - (ascent + descent - size) / 2));
                }
                case TURNED -> {
                    graphics.translate(0, size / 2.0);
                    graphics.rotate(Math.PI / 2);
                    graphics.drawString(unit.value(), (float) (-width / 2), (float) ((ascent - descent) / 2));
                }
                case SIDEWAYS -> {
                    graphics.rotate(Math.PI / 2);
                    graphics.drawString(unit.value(), 0f, (float) ((ascent - descent) / 2));
                }
                default -> {
                }
            }
        } finally {
            graphics.setTransform(saved);
        }
    }
}
