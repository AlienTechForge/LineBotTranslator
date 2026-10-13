package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the weight and size thresholds of {@link ImageTranslationStyleEstimator} to rendered type
 * whose real size and weight are known. Each case draws a paragraph in a regular or a bold face,
 * places OCR word polygons around the real glyph bounds as Vision does, and asks the estimator to
 * recover what was drawn. Every installed face from the lists below is exercised; a script with
 * no installed face is skipped.
 */
class ImageTranslationStyleEstimatorCalibrationTests {
    private static final Color INK = new Color(25, 25, 25);
    private static final double TOLERANCE = .15;
    private static final int[] SIZES = {16, 24, 40};

    private static final String[][] LATIN_LINES = {
        {"The", "quick", "brown", "fox", "jumps", "over", "the", "lazy", "dog"},
        {"Pack", "my", "box", "with", "five", "dozen", "liquor", "jugs"},
        {"How", "vexingly", "quick", "daft", "zebras", "jump"}};
    private static final String[][] CJK_LINES = {
        {"日本政府は", "十三日、", "物価高対策として", "新たな経済対策を", "閣議決定した。"},
        {"総額は約二十兆円", "規模となる見通しで、", "電気料金の補助も", "盛り込まれた。"},
        {"与党は来週にも", "補正予算案を", "国会に提出し、", "早期成立を目指す方針だ。"}};

    private enum Weight {
        REGULAR(Font.PLAIN), BOLD(Font.BOLD);

        private final int fontStyle;

        Weight(int fontStyle) {
            this.fontStyle = fontStyle;
        }
    }

    /**
     * Script families, with the faces the thresholds were measured on: faces whose bold reads as
     * bold. WenQuanYi Zen Hei is left out because it has no bold face and Java's synthetic one is no
     * heavier than a regular Noto Sans CJK.
     */
    private enum Script {
        LATIN(LATIN_LINES, List.of("DejaVu Sans", "Liberation Sans")),
        CJK(CJK_LINES, List.of("Noto Sans CJK TC", "Noto Sans CJK SC", "Noto Sans CJK JP",
                "Noto Sans CJK KR", "IPAGothic"));

        private final String[][] lines;
        private final List<String> families;

        Script(String[][] lines, List<String> families) {
            this.lines = lines;
            this.families = families;
        }

        String sample() {
            return Arrays.stream(lines).flatMap(Arrays::stream).collect(Collectors.joining());
        }
    }

    /** One installed face; {@code family} is null when the script has none. */
    private record Face(Script script, String family) {
        Font font(Weight weight, int size) {
            Assumptions.assumeTrue(family != null, "no " + script + " face is installed");
            return new Font(family, weight.fontStyle, size);
        }

        @Override
        public String toString() {
            return script + "/" + family;
        }
    }

    private record Drawing(BufferedImage image, OcrRegion region) {
    }

    private static List<Face> installedFaces() {
        Set<String> installed = Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames())
                .collect(Collectors.toSet());
        List<Face> faces = new ArrayList<>();
        for (Script script : Script.values()) {
            List<Face> found = script.families.stream()
                    .filter(installed::contains)
                    .filter(family -> new Font(family, Font.PLAIN, 20).canDisplayUpTo(script.sample()) < 0)
                    .map(family -> new Face(script, family))
                    .toList();
            if (found.isEmpty() && script == Script.LATIN) {
                found = List.of(new Face(script, Font.SANS_SERIF));
            }
            faces.addAll(found.isEmpty() ? List.of(new Face(script, null)) : found);
        }
        return faces;
    }

    private static Stream<Arguments> faces() {
        return installedFaces().stream().flatMap(face -> IntStream.of(SIZES).boxed()
                .flatMap(size -> Stream.of(Weight.values())
                        .map(weight -> Arguments.of(face, size, weight))));
    }

    private static Stream<Arguments> regularFaces() {
        return installedFaces().stream().flatMap(face -> IntStream.of(SIZES).boxed()
                .map(size -> Arguments.of(face, size)));
    }

    private static Stream<Arguments> skewedFaces() {
        return installedFaces().stream().flatMap(face -> Stream.of(Weight.values())
                .map(weight -> Arguments.of(face, weight)));
    }

    @ParameterizedTest(name = "{0} {1}px {2}")
    @MethodSource("faces")
    void weightIsRecoveredForEveryFaceSizeAndWeight(Face face, int size, Weight weight) {
        Drawing drawing = draw(face, weight, size, 0, 0, face.script().lines);

        ImageTranslationTextStyle style = ImageTranslationStyleEstimator.estimate(
                drawing.image(), drawing.region());

        assertThat(style.fontStyle()).isEqualTo(weight.fontStyle);
    }

    @ParameterizedTest(name = "{0} {1}px {2}")
    @MethodSource("faces")
    void sizeIsRecoveredWithinFifteenPercent(Face face, int size, Weight weight) {
        Drawing drawing = draw(face, weight, size, 0, 0, face.script().lines);

        ImageTranslationTextStyle style = ImageTranslationStyleEstimator.estimate(
                drawing.image(), drawing.region());

        assertSizeWithin(style, size);
    }

    /**
     * Vision's word boxes run about 30 % taller than the glyphs they hold. The estimate must follow
     * the ink, not the box: it used to take the box height and so drew the translation larger
     * than the source.
     */
    @ParameterizedTest(name = "{0} {1}px {2}")
    @MethodSource("faces")
    void polygonsInflatedByThirtyPercentStillRecoverWeightAndSize(
            Face face, int size, Weight weight) {
        Drawing drawing = draw(face, weight, size, .3, 0, face.script().lines);

        ImageTranslationTextStyle style = ImageTranslationStyleEstimator.estimate(
                drawing.image(), drawing.region());

        assertThat(style.fontStyle()).isEqualTo(weight.fontStyle);
        assertSizeWithin(style, size);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("skewedFaces")
    void aSkewedLineIsMeasuredAlongItsOwnBaseline(Face face, Weight weight) {
        Drawing drawing = draw(face, weight, 24, .3, Math.toRadians(4), face.script().lines);

        ImageTranslationTextStyle style = ImageTranslationStyleEstimator.estimate(
                drawing.image(), drawing.region());

        assertThat(style.fontStyle()).isEqualTo(weight.fontStyle);
        assertSizeWithin(style, 24);
    }

    /**
     * The premise of the change: regular type already fills well over the retired 16 % ink share,
     * so ink share could not tell a regular face from a bold one.
     */
    @ParameterizedTest(name = "{0} {1}px")
    @MethodSource("regularFaces")
    void regularTypeAlreadyExceedsTheRetiredInkShareThreshold(Face face, int size) {
        Drawing drawing = draw(face, Weight.REGULAR, size, .3, 0, face.script().lines);

        assertThat(inkShare(drawing)).isGreaterThan(.16);
    }

    @Test
    void aSingleWordKeepsThePolygonBasedSizeButStillReadsItsWeight() {
        Face latin = installedFaces().stream().filter(face -> face.script() == Script.LATIN)
                .findFirst().orElseThrow();
        Drawing drawing = draw(latin, Weight.BOLD, 32, 0, 0, new String[][] {{"SLEEP"}});

        ImageTranslationTextStyle style = ImageTranslationStyleEstimator.estimate(
                drawing.image(), drawing.region());

        List<OcrPoint> box = drawing.region().words().get(0).polygon();
        int boxHeight = box.get(3).y() - box.get(0).y();
        assertThat(style.fontStyle()).isEqualTo(Font.BOLD);
        assertThat(style.maximumFontSize()).isEqualTo((int) Math.round(boxHeight * 1.1));
    }

    @Test
    void wordsThatAreTallNarrowColumnsFallBackToThePolygonBasedSize() {
        BufferedImage image = new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 200, 300);
            graphics.setColor(INK);
            graphics.fillRect(30, 20, 6, 100);
            graphics.fillRect(70, 20, 6, 100);
        } finally {
            graphics.dispose();
        }
        List<OcrWord> columns = List.of(
                new OcrWord("柱", rectangle(24, 14, 24, 112), .99f, true),
                new OcrWord("柱", rectangle(64, 14, 24, 112), .99f, true));
        OcrRegion region = new OcrRegion("columns", "柱柱", rectangle(20, 10, 72, 120), columns,
                .99f, true, OcrBlockType.TEXT, List.of(), 0);

        ImageTranslationTextStyle style = ImageTranslationStyleEstimator.estimate(image, region);

        // 112 px of box height is the length of the column, not a glyph size; the old path stays.
        assertThat(style.maximumFontSize()).isEqualTo((int) Math.round(112 * 1.1));
    }

    @Test
    void aRegionWithoutInkIsPlainAndKeepsThePolygonBasedSize() {
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 200, 100);
        } finally {
            graphics.dispose();
        }
        List<OcrWord> words = List.of(
                new OcrWord("a", rectangle(10, 10, 80, 40), .99f, true),
                new OcrWord("b", rectangle(100, 10, 80, 40), .99f, true));
        OcrRegion region = new OcrRegion("blank", "a b", rectangle(8, 8, 176, 44), words,
                .99f, true, OcrBlockType.TEXT, List.of(), 0);

        ImageTranslationTextStyle style = ImageTranslationStyleEstimator.estimate(image, region);

        assertThat(style.fontStyle()).isEqualTo(Font.PLAIN);
        assertThat(style.maximumFontSize()).isEqualTo(44);
    }

    private static void assertSizeWithin(ImageTranslationTextStyle style, int size) {
        assertThat(style.maximumFontSize())
                .as("estimated size of type drawn at %s px", size)
                .isBetween((int) Math.floor(size * (1 - TOLERANCE)), (int) Math.ceil(size * (1 + TOLERANCE)));
    }

    /**
     * Draws the paragraph word by word. Each word polygon is the real pixel bounds of its glyphs
     * plus two pixels of slack, stretched vertically by {@code inflate}, then turned by
     * {@code angle} together with the drawing.
     */
    private static Drawing draw(
            Face face, Weight weight, int size, double inflate, double angle, String[][] lines) {
        Font font = face.font(weight, size);
        boolean spaced = face.script() == Script.LATIN;
        int lineHeight = (int) Math.round(size * 1.8);
        int originX = 20;
        int originY = 70;
        BufferedImage image = new BufferedImage(
                1500, lineHeight * lines.length + 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        List<OcrWord> words = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        Rectangle all = null;
        AffineTransform rotation = AffineTransform.getRotateInstance(angle, originX, originY);
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            graphics.setColor(INK);
            graphics.setFont(font);
            FontRenderContext context = graphics.getFontRenderContext();
            graphics.transform(rotation);
            double spaceWidth = spaced ? graphics.getFontMetrics().charWidth(' ') : 0;
            for (int row = 0; row < lines.length; row++) {
                int baseline = originY + size + row * lineHeight;
                double x = originX;
                for (String word : lines[row]) {
                    graphics.drawString(word, (float) x, baseline);
                    TextLayout layout = new TextLayout(word, font, context);
                    Rectangle ink = layout.getPixelBounds(context, (float) x, baseline);
                    int stretch = (int) Math.round(ink.height * inflate / 2);
                    Rectangle box = new Rectangle(ink.x - 2, ink.y - 2 - stretch,
                            ink.width + 4, ink.height + 4 + 2 * stretch);
                    words.add(new OcrWord(word, corners(box, rotation), .99f, true));
                    text.append(word).append(spaced ? " " : "");
                    all = all == null ? box : all.union(box);
                    x += layout.getAdvance() + spaceWidth;
                }
            }
        } finally {
            graphics.dispose();
        }
        OcrRegion region = new OcrRegion("calibration", text.toString(), corners(all, rotation),
                words, .99f, true, OcrBlockType.TEXT, List.of(), 0);
        return new Drawing(image, region);
    }

    private static List<OcrPoint> corners(Rectangle box, AffineTransform transform) {
        List<OcrPoint> points = new ArrayList<>();
        for (Point2D corner : List.of(
                new Point2D.Double(box.x, box.y),
                new Point2D.Double(box.x + box.width, box.y),
                new Point2D.Double(box.x + box.width, box.y + box.height),
                new Point2D.Double(box.x, box.y + box.height))) {
            Point2D turned = transform.transform(corner, null);
            points.add(new OcrPoint((int) Math.round(turned.getX()), (int) Math.round(turned.getY())));
        }
        return points;
    }

    /** Share of pixels inside the word polygons that differ clearly from the white page. */
    private static double inkShare(Drawing drawing) {
        int inside = 0;
        int ink = 0;
        for (OcrWord word : drawing.region().words()) {
            Polygon polygon = OverlaySafetyPolicy.polygon(word.polygon());
            Rectangle bounds = polygon.getBounds();
            for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
                for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                    if (!polygon.contains(x + .5, y + .5)) continue;
                    inside++;
                    Color pixel = new Color(drawing.image().getRGB(x, y));
                    int red = 255 - pixel.getRed();
                    int green = 255 - pixel.getGreen();
                    int blue = 255 - pixel.getBlue();
                    if (red * red + green * green + blue * blue >= 45 * 45) ink++;
                }
            }
        }
        return (double) ink / inside;
    }

    private static List<OcrPoint> rectangle(int x, int y, int width, int height) {
        return List.of(new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height));
    }
}
