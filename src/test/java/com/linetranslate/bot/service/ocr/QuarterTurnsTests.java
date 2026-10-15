package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class QuarterTurnsTests {

    // Odd sizes, and width != height, so a swapped axis cannot cancel out.
    private static final int WIDTH = 101;
    private static final int HEIGHT = 57;

    // --- polygons --------------------------------------------------------------------------------

    @Test
    void aPolygonPointRoundTripsExactlyForEveryQuarter() {
        for (int quarters = 1; quarters <= 3; quarters++) {
            for (int x = 0; x <= WIDTH; x += 7) {
                for (int y = 0; y <= HEIGHT; y += 5) {
                    OcrPoint point = new OcrPoint(x, y);

                    OcrPoint upright = QuarterTurns.toUpright(point, quarters, WIDTH, HEIGHT);
                    OcrPoint back = QuarterTurns.fromUpright(upright, quarters, WIDTH, HEIGHT);

                    assertThat(back).as("q=%d (%d,%d)", quarters, x, y).isEqualTo(point);
                }
            }
        }
    }

    @Test
    void theMapsAreTheOnesTheDesignTabulates() {
        assertThat(QuarterTurns.toUpright(new OcrPoint(30, 10), 1, WIDTH, HEIGHT))
                .isEqualTo(new OcrPoint(10, WIDTH - 30));
        assertThat(QuarterTurns.toUpright(new OcrPoint(30, 10), 2, WIDTH, HEIGHT))
                .isEqualTo(new OcrPoint(WIDTH - 30, HEIGHT - 10));
        assertThat(QuarterTurns.toUpright(new OcrPoint(30, 10), 3, WIDTH, HEIGHT))
                .isEqualTo(new OcrPoint(HEIGHT - 10, 30));
        assertThat(QuarterTurns.toUpright(new OcrPoint(30, 10), 0, WIDTH, HEIGHT))
                .isEqualTo(new OcrPoint(30, 10));
    }

    @Test
    void everyRegionPolygonWordAndSymbolMovesAndComesBackExactly() {
        for (int turns = 1; turns <= 3; turns++) {
            RotatedPageFixtures page = new RotatedPageFixtures(240, 160, turns);
            OcrRegion region = page.latin("r", "Hello brave world", 20, 30, .95f, 3);
            OcrRegion withFlow = new OcrRegion(region.id(), region.text(), region.polygon(), region.words(),
                    region.confidence(), true, region.blockType(), region.languages(), 3, "group", false,
                    null, List.of(region.polygon()));
            int width = page.pageWidth();
            int height = page.pageHeight();

            List<OcrRegion> upright = QuarterTurns.toUpright(List.of(withFlow), turns, width, height);
            List<OcrRegion> back = QuarterTurns.fromUpright(upright, turns, width, height);

            assertThat(back).as("turns=%d", turns).containsExactly(withFlow);
            OcrRegion moved = upright.get(0);
            assertThat(moved.id()).isEqualTo("r");
            assertThat(moved.text()).isEqualTo(region.text());
            assertThat(moved.readingOrder()).isEqualTo(3);
            assertThat(moved.groupId()).isEqualTo("group");
            assertThat(moved.words()).hasSameSizeAs(region.words());
            assertThat(moved.words().get(0).symbols()).hasSameSizeAs(region.words().get(0).symbols());
            assertThat(moved.flowCells()).containsExactly(moved.polygon());
        }
    }

    @Test
    void aMappedRotatedParagraphReadsAlongPlusXWithVertexZeroAtItsTopLeft() {
        for (int turns = 1; turns <= 3; turns++) {
            RotatedPageFixtures page = new RotatedPageFixtures(240, 160, turns);
            OcrRegion provider = page.latin("r", "Hello brave world", 20, 30, .95f, 0);
            assertThat(provider.writingMode().quarterTurns()).as("provider frame, turns=%d", turns).isEqualTo(turns);

            OcrRegion upright = QuarterTurns.toUpright(List.of(provider), turns, page.pageWidth(), page.pageHeight()).get(0);

            assertThat(upright.writingMode()).isEqualTo(WritingMode.HORIZONTAL);
            assertThat(upright.rotationDegrees()).isZero();
            // The upright polygon is the one the fixture laid out before it turned the page.
            assertThat(upright.polygon()).containsExactly(
                    new OcrPoint(20, 30), new OcrPoint(20 + 17 * 12, 30),
                    new OcrPoint(20 + 17 * 12, 50), new OcrPoint(20, 50));
            assertThat(upright.words().get(0).polygon()).containsExactly(
                    new OcrPoint(20, 30), new OcrPoint(20 + 5 * 12, 30),
                    new OcrPoint(20 + 5 * 12, 50), new OcrPoint(20, 50));
            assertThat(upright.words().get(0).symbols().get(0).polygon()).containsExactly(
                    new OcrPoint(20, 30), new OcrPoint(32, 30), new OcrPoint(32, 50), new OcrPoint(20, 50));
        }
    }

    @Test
    void theAreaOfAMappedRectangleCoversExactlyTheMappedPixels() {
        int x0 = 13;
        int y0 = 9;
        int x1 = 61;
        int y1 = 40;
        List<OcrPoint> box = List.of(new OcrPoint(x0, y0), new OcrPoint(x1, y0),
                new OcrPoint(x1, y1), new OcrPoint(x0, y1));
        Shape before = OverlaySafetyPolicy.polygon(box);
        for (int quarters = 1; quarters <= 3; quarters++) {
            int turns = quarters;
            Shape after = OverlaySafetyPolicy.polygon(box.stream()
                    .map(point -> QuarterTurns.toUpright(point, turns, WIDTH, HEIGHT)).toList());
            Rectangle bounds = after.getBounds();
            int covered = 0;
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    OcrPoint moved = pixel(x, y, turns);
                    boolean inside = before.contains(x + .5, y + .5);
                    assertThat(after.contains(moved.x() + .5, moved.y() + .5))
                            .as("q=%d pixel (%d,%d)", turns, x, y).isEqualTo(inside);
                    if (inside) covered++;
                }
            }
            assertThat(covered).isEqualTo((x1 - x0) * (y1 - y0));
            assertThat((long) bounds.width * bounds.height).isEqualTo((long) covered);
        }
    }

    // --- rasters ---------------------------------------------------------------------------------

    @Test
    void aRasterRoundTripsPixelForPixelInRgbAndArgb() {
        for (int type : new int[] {BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB}) {
            BufferedImage source = noise(WIDTH, HEIGHT, type);
            for (int quarters = 1; quarters <= 3; quarters++) {
                BufferedImage upright = QuarterTurns.toUpright(source, quarters);
                BufferedImage back = QuarterTurns.restore(upright, quarters);

                int[] size = uprightSize(quarters, WIDTH, HEIGHT);
                assertThat(upright.getWidth()).isEqualTo(size[0]);
                assertThat(upright.getHeight()).isEqualTo(size[1]);
                assertThat(back.getWidth()).isEqualTo(WIDTH);
                assertThat(back.getHeight()).isEqualTo(HEIGHT);
                assertSamePixels(back, source);
            }
        }
    }

    @Test
    void everyPixelLandsWhereTheCoordinateMapSaysItDoes() {
        BufferedImage source = noise(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int quarters = 1; quarters <= 3; quarters++) {
            BufferedImage upright = QuarterTurns.toUpright(source, quarters);
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    OcrPoint moved = pixel(x, y, quarters);
                    assertThat(upright.getRGB(moved.x(), moved.y()))
                            .as("q=%d (%d,%d)", quarters, x, y).isEqualTo(source.getRGB(x, y));
                }
            }
        }
    }

    @Test
    void theRasterKeepsAlphaOnlyWhenTheSourceHasIt() {
        assertThat(QuarterTurns.toUpright(noise(8, 5, BufferedImage.TYPE_INT_RGB), 1).getColorModel().hasAlpha())
                .isFalse();
        assertThat(QuarterTurns.toUpright(noise(8, 5, BufferedImage.TYPE_INT_ARGB), 1).getColorModel().hasAlpha())
                .isTrue();
        // A source of another raster type is read the same way.
        BufferedImage bytes = new BufferedImage(8, 5, BufferedImage.TYPE_3BYTE_BGR);
        bytes.setRGB(2, 3, 0x336699);
        BufferedImage upright = QuarterTurns.toUpright(bytes, 1);
        OcrPoint moved = pixel(2, 3, 1, 8, 5);
        assertThat(upright.getRGB(moved.x(), moved.y()) & 0xFFFFFF).isEqualTo(0x336699);
    }

    @Test
    void theSourceRasterIsNotTouched() {
        BufferedImage source = noise(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        BufferedImage copy = noise(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);

        QuarterTurns.toUpright(source, 1);
        QuarterTurns.restore(source, 3);

        assertSamePixels(source, copy);
    }

    @Test
    void onlyOneToThreeQuartersTurnARaster() {
        BufferedImage source = noise(4, 4, BufferedImage.TYPE_INT_RGB);

        assertThatThrownBy(() -> QuarterTurns.toUpright(source, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuarterTurns.restore(source, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThat(QuarterTurns.isTurn(0)).isFalse();
        assertThat(QuarterTurns.isTurn(1)).isTrue();
        assertThat(QuarterTurns.isTurn(3)).isTrue();
        assertThat(QuarterTurns.isTurn(4)).isFalse();
    }

    // --- raster and polygons agree about where the page went -------------------------------------

    @Test
    void aTurnedPageHasItsTextUprightInTheTurnedRaster() {
        for (int turns = 1; turns <= 3; turns++) {
            RotatedPageFixtures page = new RotatedPageFixtures(200, 120, turns);
            OcrRegion region = page.latin("r", "Hello", 20, 30, .95f, 0);
            BufferedImage provider = new BufferedImage(page.pageWidth(), page.pageHeight(), BufferedImage.TYPE_INT_RGB);
            // Ink exactly where the provider says the line is.
            Shape line = OverlaySafetyPolicy.polygon(region.polygon());
            for (int y = 0; y < provider.getHeight(); y++) {
                for (int x = 0; x < provider.getWidth(); x++) {
                    provider.setRGB(x, y, line.contains(x + .5, y + .5) ? 0x000000 : 0xFFFFFF);
                }
            }

            BufferedImage upright = QuarterTurns.toUpright(provider, turns);
            OcrRegion moved = QuarterTurns.toUpright(List.of(region), turns, page.pageWidth(), page.pageHeight()).get(0);

            assertThat(upright.getWidth()).isEqualTo(200);
            assertThat(upright.getHeight()).isEqualTo(120);
            Shape expected = OverlaySafetyPolicy.polygon(moved.polygon());
            for (int y = 0; y < upright.getHeight(); y++) {
                for (int x = 0; x < upright.getWidth(); x++) {
                    assertThat((upright.getRGB(x, y) & 0xFFFFFF) == 0)
                            .as("turns=%d (%d,%d)", turns, x, y).isEqualTo(expected.contains(x + .5, y + .5));
                }
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** The size a width x height raster takes when it is turned by the given quarters. */
    private static int[] uprightSize(int quarters, int width, int height) {
        return quarters == 1 || quarters == 3 ? new int[] {height, width} : new int[] {width, height};
    }

    private static OcrPoint pixel(int x, int y, int quarters) {
        return pixel(x, y, quarters, WIDTH, HEIGHT);
    }

    /** The pixel (x, y) of a width x height raster after the documented counter-clockwise turn. */
    private static OcrPoint pixel(int x, int y, int quarters, int width, int height) {
        return switch (quarters) {
            case 1 -> new OcrPoint(y, width - 1 - x);
            case 2 -> new OcrPoint(width - 1 - x, height - 1 - y);
            case 3 -> new OcrPoint(height - 1 - y, x);
            default -> new OcrPoint(x, y);
        };
    }

    private static BufferedImage noise(int width, int height, int type) {
        Random random = new Random(7);
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        return image;
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
