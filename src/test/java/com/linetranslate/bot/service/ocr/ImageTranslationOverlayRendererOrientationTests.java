package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * The renderer draws on the upright raster of a turned page and hands back the raster turned
 * clockwise by the page's quarters, so the delivered image is in the frame of the original.
 */
class ImageTranslationOverlayRendererOrientationTests {

    private static final int WIDTH = 220;
    private static final int HEIGHT = 130;

    private final ImageTranslationOverlayRenderer renderer =
            new ImageTranslationOverlayRenderer(new ImageTranslationFontProvider(Set.of("dejavu sans")));

    @Test
    void theOutputIsTheUprightDrawingTurnedBackByTheQuartersOfThePage() throws Exception {
        ValidatedImage source = upright();
        OverlaySafetyPlan plan = plan();
        RenderedImage baseline = renderer.render(source, plan);
        assertThat(baseline.renderedBlockCount()).isEqualTo(2);

        for (int quarters = 1; quarters <= 3; quarters++) {
            RenderedImage turned = renderer.render(source, plan, quarters);

            int[] size = uprightSize(quarters, WIDTH, HEIGHT);
            BufferedImage delivered = decode(turned);
            assertThat(delivered.getWidth()).as("q=%d", quarters).isEqualTo(size[0]);
            assertThat(delivered.getHeight()).isEqualTo(size[1]);
            assertThat(turned.renderedBlockCount()).isEqualTo(baseline.renderedBlockCount());
            assertThat(turned.decisions()).isEqualTo(baseline.decisions());
            // Turned counter-clockwise again, it is the very picture drawn upright.
            assertSamePixels(QuarterTurns.toUpright(delivered, quarters), decode(baseline));
            assertThat(turned.raster()).isNotNull();
            assertSamePixels(turned.raster(), delivered);
        }
    }

    @Test
    void theSourceRasterIsNotTouchedWhenThePageIsTurnedBack() {
        ValidatedImage source = upright();
        BufferedImage before = copyOf(source.image());

        renderer.render(source, plan(), 1);

        assertSamePixels(source.image(), before);
    }

    @Test
    void noQuartersMeansTheUnchangedPath() throws Exception {
        ValidatedImage source = upright();
        OverlaySafetyPlan plan = plan();

        RenderedImage plain = renderer.render(source, plan);
        RenderedImage none = renderer.render(source, plan, 0);

        assertThat(none.bytes()).isEqualTo(plain.bytes());
        assertThat(none.renderedBlockCount()).isEqualTo(plain.renderedBlockCount());
        assertThat(none.contentType()).isEqualTo(plain.contentType());
    }

    @Test
    void aPlanThatIsNotSafeStillDeliversTheOriginalFrame() throws Exception {
        ValidatedImage source = upright();

        for (int quarters = 1; quarters <= 3; quarters++) {
            RenderedImage unsafe = renderer.render(source, (OverlaySafetyPlan) null, quarters);
            RenderedImage refused = renderer.render(source, new OverlaySafetyPlan(false, "unsafe", List.of(), 2), quarters);

            for (RenderedImage rendered : List.of(unsafe, refused)) {
                BufferedImage delivered = decode(rendered);
                assertThat(delivered.getWidth()).isEqualTo(uprightSize(quarters, WIDTH, HEIGHT)[0]);
                assertThat(rendered.renderedBlockCount()).isZero();
                assertSamePixels(QuarterTurns.toUpright(delivered, quarters), source.image());
            }
        }
    }

    @Test
    void anImageWithAlphaKeepsItsAlphaThroughTheTurns() throws Exception {
        BufferedImage translucent = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                translucent.setRGB(x, y, (x * 7 + y * 3) % 256 << 24 | (x * 5) % 256 << 16 | (y * 9) % 256 << 8 | 0x40);
            }
        }

        RenderedImage rendered = renderer.render(
                new ValidatedImage(new byte[] {1}, "image/png", translucent), (OverlaySafetyPlan) null, 1);

        BufferedImage delivered = decode(rendered);
        assertThat(delivered.getColorModel().hasAlpha()).isTrue();
        assertSamePixels(QuarterTurns.toUpright(delivered, 1), translucent);
    }

    /** The size a width x height raster takes when it is turned by the given quarters. */
    private static int[] uprightSize(int quarters, int width, int height) {
        return quarters == 1 || quarters == 3 ? new int[] {height, width} : new int[] {width, height};
    }

    @Test
    void aCellPreservedByFitSaysHowItsLinesRunAndWhetherTheLayoutWasTurned() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ImageTranslationOverlayRenderer.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        String tooLong = "A translation far too long for any of these cells ".repeat(8);
        List<OcrPoint> wide = List.of(new OcrPoint(20, 20), new OcrPoint(80, 20),
                new OcrPoint(80, 40), new OcrPoint(20, 40));
        List<OcrPoint> column = List.of(new OcrPoint(120, 10), new OcrPoint(150, 10),
                new OcrPoint(150, 100), new OcrPoint(120, 100));
        List<OcrPoint> lyingDown = List.of(new OcrPoint(200, 10), new OcrPoint(200, 70),
                new OcrPoint(180, 70), new OcrPoint(180, 10));
        List<ImageRegionOverlay> overlays = List.of(
                new ImageRegionOverlay(regionOf("wide", wide), tooLong),
                new ImageRegionOverlay(regionOf("column", column), tooLong),
                new ImageRegionOverlay(regionOf("lying", lyingDown), tooLong));
        try {
            renderer.render(upright(), new OverlaySafetyPlan(true, "safe", overlays, 0));
        } finally {
            logger.detachAppender(logs);
        }

        List<String> lines = logs.list.stream().map(event -> event.getFormattedMessage())
                .filter(line -> line.startsWith("Overlay cell preserved by fit")).toList();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).endsWith("writingMode=HORIZONTAL, layout=horizontal");
        assertThat(lines.get(1)).endsWith("writingMode=HORIZONTAL, layout=turned");
        assertThat(lines.get(2)).endsWith("writingMode=ROTATED_90, layout=horizontal");
    }

    private static OcrRegion regionOf(String id, List<OcrPoint> polygon) {
        return new OcrRegion(id, "hello", polygon, List.of(new OcrWord("hello", polygon, .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("en", .99f)), 0);
    }

    private OverlaySafetyPlan plan() {
        List<ImageRegionOverlay> overlays = List.of(
                new ImageRegionOverlay(region("a", 20, 20, 150, 28), "Greetings"),
                new ImageRegionOverlay(region("b", 20, 80, 150, 28), "Planet"));
        return new OverlaySafetyPlan(true, "safe", overlays, 0);
    }

    private static OcrRegion region(String id, int x, int y, int width, int height) {
        List<OcrPoint> box = List.of(new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height));
        return new OcrRegion(id, "hello", box, List.of(new OcrWord("hello", box, .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("en", .99f)), 0);
    }

    /** An upright page: light paper, two dark printed words, and a mark in a corner. */
    private static ValidatedImage upright() {
        BufferedImage page = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = page.createGraphics();
        try {
            graphics.setColor(new Color(244, 240, 230));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            graphics.setColor(new Color(30, 30, 30));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
            graphics.drawString("hello", 24, 42);
            graphics.drawString("hello", 24, 102);
            graphics.setColor(new Color(200, 30, 30));
            graphics.fillRect(0, 0, 9, 9);
        } finally {
            graphics.dispose();
        }
        return new ValidatedImage(new byte[] {1}, "image/png", page);
    }

    private static BufferedImage decode(RenderedImage rendered) throws Exception {
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(rendered.bytes()));
        assertThat(decoded).isNotNull();
        return decoded;
    }

    private static BufferedImage copyOf(BufferedImage image) {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), image.getType());
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                copy.setRGB(x, y, image.getRGB(x, y));
            }
        }
        return copy;
    }

    private static void assertSamePixels(BufferedImage actual, BufferedImage expected) {
        assertThat(actual.getWidth()).isEqualTo(expected.getWidth());
        assertThat(actual.getHeight()).isEqualTo(expected.getHeight());
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertThat(actual.getRGB(x, y)).as("pixel %d,%d", x, y).isEqualTo(expected.getRGB(x, y));
            }
        }
    }
}
