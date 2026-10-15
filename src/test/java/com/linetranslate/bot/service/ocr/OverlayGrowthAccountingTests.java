package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * A cell that takes the empty space under it changes pixels the safety policy never approved, so
 * growing has to be accounted for like any other mask: charged against the plan's coverage limits,
 * kept clear of every other grown cell, and given back when group normalisation leaves the cell
 * with a smaller layout than the one it grew for. {@link OverlaySmallLabelGrowthTests} covers the
 * growth itself.
 */
class OverlayGrowthAccountingTests {

    private static final int IMAGE_WIDTH = 200;
    private static final int IMAGE_HEIGHT = 140;
    private static final int X = 20;
    private static final int WIDTH = 61;
    /** 57x9 once the 2px insets are taken: the cell of the production log. */
    private static final int HEIGHT = 13;
    private static final Color INK = new Color(30, 30, 30);
    private static final ImageTranslationProperties AMPLE =
            new ImageTranslationProperties(1000, 400, 100000, .6f, true, .8, .9);
    private static final ImageTranslationFontProvider FONTS =
            new ImageTranslationFontProvider(Set.of("dejavu sans"));

    // ---- total and per-region budget -------------------------------------------------------

    @Test
    void labelsNearTheTotalLimitAreDeniedGrowthAndKeepTheirSourceText() throws Exception {
        BufferedImage original = page();
        drawSource(original, 20);
        List<ImageRegionOverlay> overlays = List.of(overlay(region("label", "g", 20, HEIGHT, true), "ABCD"));
        double accepted = evaluate(original, overlays, AMPLE).acceptedRatio();
        // The plan has room for the cell itself and next to nothing else.
        // The single-region limit is no tighter than the total, so it is the total that decides.
        ImageTranslationProperties nearlyFull = new ImageTranslationProperties(
                1000, 400, 100000, .6f, true, accepted + .0005, accepted + .0005);

        RenderedImage result = render(original, overlays, nearlyFull);

        assertThat(result.decisions()).containsExactly(
                new OverlayRenderDecision("label", OverlayRenderStatus.PRESERVED, "text-fit"));
        assertThat(differingPixels(original, decoded(result))).isZero();
    }

    @Test
    void theSameLabelGrowsWhenThePlanStillHasRoomForTheBand() throws Exception {
        BufferedImage original = page();
        drawSource(original, 20);
        List<ImageRegionOverlay> overlays = List.of(overlay(region("label", "g", 20, HEIGHT, true), "ABCD"));
        double accepted = evaluate(original, overlays, AMPLE).acceptedRatio();
        ImageTranslationProperties roomy = new ImageTranslationProperties(
                1000, 400, 100000, .6f, true, .06, accepted + .03);

        RenderedImage result = render(original, overlays, roomy);

        assertThat(result.decisions()).containsExactly(
                new OverlayRenderDecision("label", OverlayRenderStatus.RENDERED, "rendered"));
    }

    @Test
    void growthIsChargedSoManyShortLabelsCannotNearlyDoubleTheMask() throws Exception {
        // Five labels in a column, each needing about the same band. The room left after the
        // labels themselves pays for the first two or three; the rest keep their source text.
        BufferedImage original = page();
        java.util.List<ImageRegionOverlay> overlays = new java.util.ArrayList<>();
        for (int index = 0; index < 5; index++) {
            int y = 4 + index * 26;
            drawSource(original, y);
            overlays.add(overlay(region("label-" + index, "g" + index, y, HEIGHT, true), "ABCD"));
        }
        double accepted = evaluate(original, overlays, AMPLE).acceptedRatio();
        double oneBand = .0125;
        ImageTranslationProperties limits = new ImageTranslationProperties(
                1000, 400, 100000, .6f, true, .12, accepted + oneBand * 2.5);

        RenderedImage result = render(original, overlays, limits);

        long grown = result.decisions().stream()
                .filter(decision -> decision.status() == OverlayRenderStatus.RENDERED).count();
        long kept = result.decisions().stream()
                .filter(decision -> decision.status() == OverlayRenderStatus.PRESERVED).count();
        assertThat(grown).as("decisions=%s", result.decisions()).isBetween(1L, 3L);
        assertThat(kept).isEqualTo(5 - grown);
        assertThat(result.decisions()).filteredOn(decision -> decision.status() == OverlayRenderStatus.PRESERVED)
                .allSatisfy(decision -> assertThat(decision.reason()).isEqualTo("text-fit"));
    }

    @Test
    void labelPastTheSingleRegionLimitWithItsBandIsDeniedGrowth() throws Exception {
        BufferedImage original = page();
        drawSource(original, 20);
        // The cell is 61x13 of a 200x140 image: 2.83%. 2.9% leaves no room for a row of band.
        ImageTranslationProperties tight = new ImageTranslationProperties(
                1000, 400, 100000, .6f, true, .029, .9);

        RenderedImage result = render(original, List.of(overlay(region("label", "g", 20, HEIGHT, true), "ABCD")),
                tight);

        assertThat(result.decisions()).containsExactly(
                new OverlayRenderDecision("label", OverlayRenderStatus.PRESERVED, "text-fit"));
        assertThat(differingPixels(original, decoded(result))).isZero();
    }

    @Test
    void planWithoutAccountingNeverGrowsItsRegions() throws Exception {
        BufferedImage original = page();
        drawSource(original, 20);
        ImageRegionOverlay label = overlay(region("label", "g", 20, HEIGHT, true), "ABCD");
        OverlaySafetyPlan unaccounted = new OverlaySafetyPlan(true, "safe", List.of(label), 0);

        RenderedImage result = new ImageTranslationOverlayRenderer(FONTS).render(validated(original), unaccounted);

        assertThat(result.decisions()).containsExactly(
                new OverlayRenderDecision("label", OverlayRenderStatus.PRESERVED, "text-fit"));
    }

    // ---- bands granted to one cell are not given to another ---------------------------------

    /**
     * An upright cell and an upside-down one 6px under it grow towards each other. Neither cell's
     * mask is in the other's way, but both bands would cover the row between them: the first to
     * grow keeps it, and the second, left with two rows where it needs three for its smallest
     * worthwhile type, keeps its source text instead of printing over the first one's band.
     */
    @Test
    void twoCellsGrowingTowardsEachOtherCannotBothTakeTheSameRows() throws Exception {
        BufferedImage original = page();
        drawSource(original, 20);
        drawSource(original, 39);

        RenderedImage result = render(original, List.of(
                overlay(region("upright", "a", 20, HEIGHT, true), "ABCD"),
                overlay(upsideDown("upside-down", "b", 39), "EFGH")), AMPLE);

        assertThat(result.decisions()).as("decisions=%s", result.decisions())
                .contains(new OverlayRenderDecision("upright", OverlayRenderStatus.RENDERED, "rendered"))
                .contains(new OverlayRenderDecision("upside-down", OverlayRenderStatus.PRESERVED, "text-fit"));
    }

    @Test
    void theSameTwoCellsBothGrowWhenTheRowsEachTakesAreItsOwn() throws Exception {
        // Control for the test above: five pixels further apart the cells share the gap without
        // sharing a row, and both are translated.
        BufferedImage original = page();
        drawSource(original, 20);
        drawSource(original, 44);

        RenderedImage result = render(original, List.of(
                overlay(region("upright", "a", 20, HEIGHT, true), "ABCD"),
                overlay(upsideDown("upside-down", "b", 44), "EFGH")), AMPLE);

        assertThat(result.decisions()).as("decisions=%s", result.decisions())
                .contains(new OverlayRenderDecision("upright", OverlayRenderStatus.RENDERED, "rendered"))
                .contains(new OverlayRenderDecision("upside-down", OverlayRenderStatus.RENDERED, "rendered"));
    }

    // ---- group normalisation ----------------------------------------------------------------

    /**
     * One member of the group fits only at 8px, which is under what a grown cell is worth (9px).
     * The group would shrink the grown cell below that, so it keeps its source text instead of
     * keeping a band for a layout it no longer has.
     */
    @Test
    void grownLabelThatGroupNormalisationShrinksBelowTheGrowthTargetKeepsItsSourceText() throws Exception {
        BufferedImage original = page();
        drawSource(original, 20);
        drawSource(original, 80);
        // 14px tall: 10px inside, where only the 8px type has a line box that fits.
        OcrRegion tight = region("tight", "table", 80, 14, false);

        RenderedImage result = render(original, List.of(
                overlay(region("grown", "table", 20, HEIGHT, true), "ABCD"), overlay(tight, "EFGH")), AMPLE);

        assertThat(result.decisions()).as("decisions=%s", result.decisions())
                .contains(new OverlayRenderDecision("grown", OverlayRenderStatus.PRESERVED, "text-fit"))
                .contains(new OverlayRenderDecision("tight", OverlayRenderStatus.RENDERED, "rendered"));
        BufferedImage output = decoded(result);
        for (int y = 0; y < 60; y++) {
            for (int x = 0; x < IMAGE_WIDTH; x++) {
                assertThat(output.getRGB(x, y)).as("pixel %s,%s of the label that kept its text", x, y)
                        .isEqualTo(original.getRGB(x, y));
            }
        }
    }

    /**
     * The group settles on 10px, still worth growing for, but the cell grew for 14px. Laid out
     * again at 10px it needs four rows instead of eight, and sits at the top of the cell with no
     * slack, not centred in rows it no longer uses.
     */
    @Test
    void grownLabelNormalisedToASmallerSizeIsLaidOutAgainForTheHeightItUses() throws Exception {
        BufferedImage alone = page();
        drawSource(alone, 20);
        RenderedImage unnormalised = render(alone,
                List.of(overlay(region("grown", "table", 20, HEIGHT, true), "ABCD")), AMPLE);

        BufferedImage original = page();
        drawSource(original, 20);
        drawSource(original, 80);
        // 17px tall: 13 inside, the largest cell whose line box fits 10px type but not 11px.
        OcrRegion member = region("member", "table", 80, 17, false);
        RenderedImage normalised = render(original, List.of(
                overlay(region("grown", "table", 20, HEIGHT, true), "ABCD"),
                overlay(member, "EFGH")), AMPLE);

        assertThat(normalised.decisions()).as("decisions=%s", normalised.decisions())
                .contains(new OverlayRenderDecision("grown", OverlayRenderStatus.RENDERED, "rendered"))
                .contains(new OverlayRenderDecision("member", OverlayRenderStatus.RENDERED, "rendered"));
        int[] big = inkRows(decoded(unnormalised), 0, 60);
        int[] small = inkRows(decoded(normalised), 0, 60);
        assertThat(small[1] - small[0]).as("glyphs are smaller at the group's size")
                .isLessThan(big[1] - big[0]);
        // Top of the cell + 2px inset + 10px ascent is the baseline of a block with no slack, so
        // its caps end on the row above the baseline: row 31. Centred in the eight rows the cell
        // grew for (4px of slack) they would end on row 33.
        assertThat(small[1]).as("ink rows %s of the normalised label, %s of the one alone",
                        java.util.Arrays.toString(small), java.util.Arrays.toString(big))
                .isLessThanOrEqualTo(32);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static ImageRegionOverlay overlay(OcrRegion region, String replacement) {
        return new ImageRegionOverlay(region, replacement);
    }

    private static OcrRegion region(String id, String group, int y, int height, boolean compact) {
        List<OcrPoint> polygon = List.of(
                new OcrPoint(X, y), new OcrPoint(X + WIDTH, y),
                new OcrPoint(X + WIDTH, y + height), new OcrPoint(X, y + height));
        return new OcrRegion(id, "nguon", polygon,
                List.of(new OcrWord("nguon", polygon, .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0, group, compact);
    }

    /** A cell turned half a circle: its text runs right to left and its "down" is the image's up. */
    private static OcrRegion upsideDown(String id, String group, int top) {
        List<OcrPoint> polygon = List.of(new OcrPoint(X + WIDTH, top + HEIGHT), new OcrPoint(X, top + HEIGHT),
                new OcrPoint(X, top), new OcrPoint(X + WIDTH, top));
        return new OcrRegion(id, "nguon", polygon,
                List.of(new OcrWord("nguon", polygon, .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0, group, true);
    }

    private static OverlaySafetyPlan evaluate(
            BufferedImage original, List<ImageRegionOverlay> overlays, ImageTranslationProperties limits) {
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                overlays, original.getWidth(), original.getHeight(), limits);
        assertThat(plan.safe()).as("plan=%s", plan).isTrue();
        assertThat(plan.overlays()).as("every region is approved by the policy").hasSameSizeAs(overlays);
        return plan;
    }

    private static RenderedImage render(
            BufferedImage original, List<ImageRegionOverlay> overlays, ImageTranslationProperties limits)
            throws Exception {
        return new ImageTranslationOverlayRenderer(FONTS).render(
                validated(original), evaluate(original, overlays, limits));
    }

    private static ValidatedImage validated(BufferedImage original) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        return new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
    }

    private static BufferedImage page() {
        BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /** Stands in for the source text of a cell: a dark bar across most of it. */
    private static void drawSource(BufferedImage image, int y) {
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(INK);
            graphics.fillRect(X + 4, y + 3, WIDTH - 8, 6);
        } finally {
            graphics.dispose();
        }
    }

    private static BufferedImage decoded(RenderedImage result) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(result.pngBytes()));
    }

    private static int differingPixels(BufferedImage left, BufferedImage right) {
        int differing = 0;
        for (int y = 0; y < left.getHeight(); y++) {
            for (int x = 0; x < left.getWidth(); x++) {
                if (left.getRGB(x, y) != right.getRGB(x, y)) differing++;
            }
        }
        return differing;
    }

    /** First and last row, from {@code fromY} up to {@code toY}, holding dark ink in the cell columns. */
    private static int[] inkRows(BufferedImage image, int fromY, int toY) {
        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        for (int y = fromY; y < toY; y++) {
            for (int x = X; x < X + WIDTH; x++) {
                Color colour = new Color(image.getRGB(x, y));
                if (colour.getRed() < 128 && colour.getGreen() < 128 && colour.getBlue() < 128) {
                    first = Math.min(first, y);
                    last = Math.max(last, y);
                    break;
                }
            }
        }
        return last < first ? new int[] {-1, -1} : new int[] {first, last};
    }
}
