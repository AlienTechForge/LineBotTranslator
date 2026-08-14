package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class PolygonOverlayRendererTests {
    @Test
    void manySparseOverlaysRenderWithinAReasonableLocalCpuBudget() {
        BufferedImage original = new BufferedImage(1536, 2730, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(new Color(38, 38, 38));
            graphics.fillRect(0, 0, original.getWidth(), original.getHeight());
        } finally {
            graphics.dispose();
        }
        List<ImageRegionOverlay> overlays = new java.util.ArrayList<>();
        for (int index = 0; index < 48; index++) {
            int column = index % 6;
            int row = index / 6;
            int x = 80 + column * 240;
            int y = 80 + row * 320;
            List<OcrPoint> polygon = List.of(
                    new OcrPoint(x + 40, y), new OcrPoint(x + 40, y + 180),
                    new OcrPoint(x, y + 180), new OcrPoint(x, y));
            OcrRegion region = new OcrRegion("perf-" + index, "菜單", polygon,
                    List.of(new OcrWord("菜單", polygon, .99f, true)),
                    .99f, true, OcrBlockType.TEXT, List.of(), index);
            overlays.add(new ImageRegionOverlay(region, "Menu"));
        }
        OverlaySafetyPlan plan = new OverlaySafetyPlan(true, "safe", overlays, 0);
        ImageTranslationOverlayRenderer renderer = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans")));

        long started = System.nanoTime();
        RenderedImage rendered = renderer.render(
                new ValidatedImage(new byte[] {1}, "image/png", original), plan);
        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);

        assertThat(rendered.renderedBlockCount()).isEqualTo(48);
        assertThat(elapsedMillis).as("48 overlays on 1536x2730 image").isLessThan(5_000);
    }

    @Test
    void replacementClearsOnlySourceGlyphAreaAndPreservesTableGrid() throws Exception {
        BufferedImage original = new BufferedImage(170, 90, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, original.getWidth(), original.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.drawRect(10, 10, 145, 55);
            graphics.setColor(new Color(0, 90, 210));
            graphics.drawLine(115, 10, 115, 65);
            graphics.setColor(Color.BLACK);
            graphics.setFont(graphics.getFont().deriveFont(java.awt.Font.BOLD, 24f));
            graphics.drawString("ITEM", 20, 48);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        List<OcrPoint> cell = List.of(
                new OcrPoint(10, 10), new OcrPoint(120, 10),
                new OcrPoint(120, 65), new OcrPoint(10, 65));
        List<OcrPoint> word = List.of(
                new OcrPoint(18, 20), new OcrPoint(92, 20),
                new OcrPoint(92, 53), new OcrPoint(18, 53));
        OcrRegion region = new OcrRegion("grid-cell", "ITEM", cell,
                List.of(new OcrWord("ITEM", word, .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(region, "Meal")), 170, 90,
                new ImageTranslationProperties(1000, 200, 20000, .6f, true, .8, .9));

        RenderedImage rendered = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);
        assertThat(rendered.renderedBlockCount()).as("decisions=%s", rendered.decisions()).isEqualTo(1);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(rendered.pngBytes()));

        for (int x = 10; x <= 155; x++) {
            assertThat(output.getRGB(x, 10)).as("top grid at x=%s", x)
                    .isEqualTo(original.getRGB(x, 10));
            assertThat(output.getRGB(x, 65)).as("bottom grid at x=%s", x)
                    .isEqualTo(original.getRGB(x, 65));
        }
        for (int y = 10; y <= 65; y++) {
            assertThat(output.getRGB(10, y)).as("left grid at y=%s", y)
                    .isEqualTo(original.getRGB(10, y));
            assertThat(output.getRGB(115, y)).as("column grid at y=%s", y)
                    .isEqualTo(original.getRGB(115, y));
        }
    }

    @Test
    void sourceGlyphsAreErasedWithTheirOwnLocalBackgroundColours() throws Exception {
        BufferedImage original = new BufferedImage(220, 70, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(new Color(30, 30, 30));
            graphics.fillRect(0, 0, 110, 70);
            graphics.setColor(new Color(82, 82, 82));
            graphics.fillRect(110, 0, 110, 70);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(65, 24, 22, 24);
            graphics.fillRect(155, 24, 22, 24);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrWord word = new OcrWord("AB", rectangle(65, 24, 112, 24), .99f, true, List.of(
                new OcrSymbol("A", rectangle(65, 24, 22, 24), .99f, true),
                new OcrSymbol("B", rectangle(155, 24, 22, 24), .99f, true)));
        OcrRegion region = new OcrRegion("local-background", "AB", rectangle(10, 10, 200, 50),
                List.of(word), .99f, true, OcrBlockType.TEXT, List.of(), 0);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(region, "X")), 220, 70,
                new ImageTranslationProperties(1000, 100, 10000, .6f, true, .8, .9));
        List<java.awt.geom.Area> cleanupAreas = OverlaySafetyPolicy.sourceCleanupAreas(region, 220, 70);
        assertThat(cleanupAreas).hasSize(2);
        assertThat(ImageTranslationStyleEstimator.localBackground(
                original, cleanupAreas.get(0), Color.PINK, Color.WHITE)).isEqualTo(new Color(30, 30, 30));
        assertThat(ImageTranslationStyleEstimator.localBackground(
                original, cleanupAreas.get(1), Color.PINK, Color.WHITE)).isEqualTo(new Color(82, 82, 82));

        RenderedImage rendered = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);
        assertThat(rendered.renderedBlockCount()).as("decisions=%s", rendered.decisions()).isEqualTo(1);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(rendered.pngBytes()));

        assertThat(new Color(output.getRGB(76, 36))).isEqualTo(new Color(30, 30, 30));
        assertThat(new Color(output.getRGB(166, 36))).isEqualTo(new Color(82, 82, 82));
    }

    @Test
    void denseWhiteNeighbouringGlyphsDoNotTurnADarkCleanupAreaWhite() {
        Color dark = new Color(35, 35, 38);
        BufferedImage original = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(dark);
            graphics.fillRect(0, 0, 120, 80);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(36, 26, 48, 28);
        } finally {
            graphics.dispose();
        }
        java.awt.geom.Area cleanup = new java.awt.geom.Area(
                OverlaySafetyPolicy.polygon(rectangle(40, 30, 40, 20)));

        Color sampled = ImageTranslationStyleEstimator.localBackground(
                original, cleanup, dark, Color.WHITE);

        assertThat(sampled.getRed()).isLessThan(90);
        assertThat(sampled.getGreen()).isLessThan(90);
        assertThat(sampled.getBlue()).isLessThan(90);
    }

    @Test
    void neighbouringInkNeverBecomesTheCleanupFillColour() {
        Color paper = Color.WHITE;
        Color ink = new Color(20, 20, 20);
        BufferedImage original = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(paper);
            graphics.fillRect(0, 0, 120, 80);
            // Dense neighbouring glyphs surround the cleanup area on every side.
            graphics.setColor(ink);
            graphics.fillRect(20, 10, 80, 20);
            graphics.fillRect(20, 54, 80, 20);
            graphics.fillRect(20, 30, 16, 24);
            graphics.fillRect(84, 30, 16, 24);
        } finally {
            graphics.dispose();
        }
        java.awt.geom.Area cleanup = new java.awt.geom.Area(
                OverlaySafetyPolicy.polygon(rectangle(40, 34, 40, 16)));

        Color darkPaper = ImageTranslationStyleEstimator.localBackground(original, cleanup, paper, ink);
        Color invertedPaper = ImageTranslationStyleEstimator.localBackground(original, cleanup, ink, paper);

        assertThat(darkPaper).as("ink-dominated perimeter must fall back to the region background")
                .isEqualTo(paper);
        assertThat(invertedPaper).as("sample matching the region background is still trusted")
                .isEqualTo(ink);
    }

    @Test
    void oneVisualGroupRendersEveryCellAtTheSameFontSize() throws Exception {
        BufferedImage original = whiteImage(360, 120);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion shortCell = groupedRegion("cell-short", "染髮", 20, 20, 300, 34);
        OcrRegion longCell = groupedRegion("cell-long", "洗髮剪髮頭皮隔離", 20, 66, 300, 34);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(shortCell, "Dye"),
                        new ImageRegionOverlay(longCell, "Shampoo, haircut and scalp isolation")),
                360, 120, new ImageTranslationProperties(1000, 400, 100000, .6f, true, .8, .9));

        RenderedImage rendered = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);

        assertThat(rendered.renderedBlockCount()).as("decisions=%s", rendered.decisions()).isEqualTo(2);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(rendered.pngBytes()));
        int shortHeight = inkHeight(output, 20, 20, 300, 34);
        int longHeight = inkHeight(output, 20, 66, 300, 34);
        assertThat(shortHeight).isPositive();
        assertThat(longHeight).isPositive();
        assertThat(Math.abs(shortHeight - longHeight))
                .as("group cells must share one type size (short=%s, long=%s)", shortHeight, longHeight)
                .isLessThanOrEqualTo(2);
    }

    @Test
    void narrowCellCondensesGlyphWidthInsteadOfAbandoningTheWholeCell() throws Exception {
        BufferedImage original = whiteImage(320, 60);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        // A single-line menu cell: plenty of width for the source, far too little for a literal
        // English translation unless glyphs may be narrowed.
        OcrRegion cell = groupedRegion("menu-cell", "南洋綠咖哩雞飯", 10, 14, 250, 30);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(cell, "Nanyang Green Curry Chicken Rice")),
                320, 60, new ImageTranslationProperties(1000, 400, 100000, .6f, true, .8, .9));

        RenderedImage rendered = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);

        assertThat(rendered.decisions())
                .extracting(OverlayRenderDecision::reason)
                .doesNotContain("text-fit");
        assertThat(rendered.renderedBlockCount()).isEqualTo(1);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(rendered.pngBytes()));
        assertThat(inkHeight(output, 10, 14, 250, 30))
                .as("condensed text keeps readable height")
                .isGreaterThanOrEqualTo(6);
    }

    @Test
    void condensingNeverSpillsInkOutsideTheApprovedCell() throws Exception {
        BufferedImage original = whiteImage(320, 60);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion cell = groupedRegion("tight-cell", "蔥爆牛肉", 40, 20, 120, 20);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(cell, "Stir-Fried Beef with Scallions Rice")),
                320, 60, new ImageTranslationProperties(1000, 400, 100000, .6f, true, .8, .9));

        RenderedImage rendered = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(rendered.pngBytes()));

        for (int y = 0; y < 60; y++) {
            for (int x = 0; x < 320; x++) {
                boolean inside = x >= 38 && x <= 162 && y >= 18 && y <= 42;
                if (inside) continue;
                assertThat(new Color(output.getRGB(x, y)))
                        .as("pixel outside the cell at %s,%s", x, y)
                        .isEqualTo(Color.WHITE);
            }
        }
    }

    @Test
    void shortLabelFitsACellWhoseHeightMatchesTheProductionRatio() throws Exception {
        // Production geometry: sourceFontSize is ~1.3x cell height, and these cells failed for
        // translations as short as one character, which cannot be a width problem.
        BufferedImage original = whiteImage(200, 60);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion cell = compactCell("tight-row", "會員", 20, 20, 43, 14);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(cell, "Mem")), 200, 60,
                new ImageTranslationProperties(1000, 400, 100000, .6f, true, .8, .9));

        RenderedImage rendered = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);

        assertThat(rendered.decisions())
                .extracting(OverlayRenderDecision::reason)
                .doesNotContain("text-fit");
        assertThat(rendered.renderedBlockCount()).isEqualTo(1);
    }

    @Test
    void wideCompactCellMayShrinkBelowTheSourceGlyphBoxRatioToFitOneLine() throws Exception {
        BufferedImage original = whiteImage(300, 60);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion cell = compactCell("wide-row", "洗髮剪髮頭皮隔離染髮", 20, 16, 234, 27);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(cell,
                        "Shampoo, Haircut, Scalp Care and Dye")), 300, 60,
                new ImageTranslationProperties(1000, 400, 100000, .6f, true, .8, .9));

        RenderedImage rendered = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);

        assertThat(rendered.decisions())
                .extracting(OverlayRenderDecision::reason)
                .doesNotContain("text-fit");
        assertThat(rendered.renderedBlockCount()).isEqualTo(1);
    }

    @Test
    void cellShorterThanTheMinimumReadableSizeStillPreservesItsSource() throws Exception {
        BufferedImage original = whiteImage(200, 40);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion sliver = groupedRegion("sliver", "る。", 20, 16, 81, 7);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(sliver, "and so on")), 200, 40,
                new ImageTranslationProperties(1000, 400, 100000, .6f, true, .8, .9));

        RenderedImage rendered = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);

        assertThat(rendered.renderedBlockCount())
                .as("a 7px cell cannot hold readable type and must keep its source")
                .isZero();
    }

    @Test
    void longTranslationInARoomyCompactCellMayGoBelowTheCompactFloor() throws Exception {
        // Production geometry: 341x44 with a 43px glyph box. The compact floor lands at 24, where
        // forty characters need two lines and 61px of height, but size 17 fits on one line.
        BufferedImage original = whiteImage(400, 80);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion cell = compactCell("roomy-row", "洗髮剪髮頭皮隔離染髮日本三劑式結構護髮", 20, 18, 341, 44);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(cell,
                        "Shampoo, Haircut, Scalp Care, Dye and Care")), 400, 80,
                new ImageTranslationProperties(1000, 400, 100000, .6f, true, .8, .9));

        RenderedImage rendered = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);

        assertThat(rendered.decisions())
                .extracting(OverlayRenderDecision::reason)
                .doesNotContain("text-fit");
        assertThat(rendered.renderedBlockCount()).isEqualTo(1);
    }

    @Test
    void shortCompactLabelStillPrefersTypeAboveTheCompactFloor() throws Exception {
        BufferedImage original = whiteImage(300, 80);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion cell = compactCell("short-row", "染髮", 20, 18, 240, 44);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(cell, "Dye")), 300, 80,
                new ImageTranslationProperties(1000, 400, 100000, .6f, true, .8, .9));

        RenderedImage rendered = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(rendered.pngBytes()));

        assertThat(rendered.renderedBlockCount()).isEqualTo(1);
        assertThat(inkHeight(output, 20, 18, 240, 44))
                .as("a short label must not be shrunk to the readability minimum")
                .isGreaterThanOrEqualTo(14);
    }

    @Test
    void polygonSkewIsZeroForRectanglesAndParallelogramsButNotForTrapezoids() {
        assertThat(ImageTranslationOverlayRenderer.polygonSkew(rectangle(10, 10, 200, 40)))
                .as("axis-aligned rectangle").isZero();

        List<OcrPoint> parallelogram = List.of(
                new OcrPoint(10, 10), new OcrPoint(210, 30),
                new OcrPoint(230, 90), new OcrPoint(30, 70));
        assertThat(ImageTranslationOverlayRenderer.polygonSkew(parallelogram))
                .as("rotated/sheared parallelogram is reproducible by translate+rotate")
                .isLessThan(.001);

        // Perspective view of a plane: the far edge is shorter than the near edge.
        List<OcrPoint> trapezoid = List.of(
                new OcrPoint(10, 10), new OcrPoint(210, 10),
                new OcrPoint(180, 70), new OcrPoint(40, 70));
        assertThat(ImageTranslationOverlayRenderer.polygonSkew(trapezoid))
                .as("trapezoid cannot be reproduced by translate+rotate")
                .isGreaterThan(.2);
    }

    @Test
    void residualInkRatioDistinguishesACleanEraseFromSurvivingSourceGlyphs() {
        Color paper = Color.WHITE;
        Color ink = new Color(20, 20, 20);
        BufferedImage erased = whiteImage(60, 40);
        java.awt.geom.Area cleanup = new java.awt.geom.Area(
                OverlaySafetyPolicy.polygon(rectangle(10, 10, 40, 20)));

        assertThat(ImageTranslationOverlayRenderer.residualInkRatio(
                erased, List.of(cleanup), ink, paper))
                .as("fully erased area").isZero();

        BufferedImage partly = whiteImage(60, 40);
        var graphics = partly.createGraphics();
        try {
            graphics.setColor(ink);
            graphics.fillRect(10, 10, 20, 20);
        } finally {
            graphics.dispose();
        }

        assertThat(ImageTranslationOverlayRenderer.residualInkRatio(
                partly, List.of(cleanup), ink, paper))
                .as("half the cleanup area still holds source ink")
                .isBetween(.45, .55);
    }

    @Test
    void narrowColumnRunsLatinTextAlongTheColumnAndStaysInsideIt() throws Exception {
        // Production geometry: a vertical CJK dish name is about 30px wide and 163px tall. Laid
        // out across 30px the translation collapses to one or two characters per line.
        BufferedImage original = whiteImage(140, 220);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion column = groupedRegion("column", "糖醋排骨飯", 40, 25, 30, 163);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(column, "Sweet and Sour Pork Ribs Rice")),
                140, 220, new ImageTranslationProperties(1000, 400, 100000, .6f, true, .8, .9));

        RenderedImage rendered = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);

        assertThat(rendered.decisions())
                .extracting(OverlayRenderDecision::reason)
                .doesNotContain("text-fit");
        assertThat(rendered.renderedBlockCount()).isEqualTo(1);

        BufferedImage output = ImageIO.read(new ByteArrayInputStream(rendered.pngBytes()));
        for (int y = 0; y < 220; y++) {
            for (int x = 0; x < 140; x++) {
                boolean inside = x >= 38 && x <= 72 && y >= 23 && y <= 190;
                if (inside) continue;
                assertThat(new Color(output.getRGB(x, y)))
                        .as("pixel outside the column at %s,%s", x, y)
                        .isEqualTo(Color.WHITE);
            }
        }
        int[] ink = inkBounds(output, 38, 23, 36, 170);
        assertThat(ink[3])
                .as("ink must run down the column (%sx%s), not across it", ink[2], ink[3])
                .isGreaterThan(ink[2] * 2);
    }

    /** Width and height of the ink bounding box inside a cell, as {x, y, width, height}. */
    private static int[] inkBounds(BufferedImage image, int x, int y, int width, int height) {
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                Color colour = new Color(image.getRGB(column, row));
                if (colour.getRed() < 128 && colour.getGreen() < 128 && colour.getBlue() < 128) {
                    left = Math.min(left, column);
                    right = Math.max(right, column);
                    top = Math.min(top, row);
                    bottom = Math.max(bottom, row);
                }
            }
        }
        return right < left
                ? new int[] {0, 0, 0, 0}
                : new int[] {left, top, right - left + 1, bottom - top + 1};
    }

    @Test
    void aWideCellIsNeverTurnedOnItsSide() throws Exception {
        BufferedImage original = whiteImage(300, 90);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion row = groupedRegion("row", "糖醋排骨飯", 20, 25, 240, 34);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(row, "Sweet and Sour Pork Ribs Rice")),
                300, 90, new ImageTranslationProperties(1000, 400, 100000, .6f, true, .8, .9));

        RenderedImage rendered = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(rendered.pngBytes()));

        assertThat(rendered.renderedBlockCount()).isEqualTo(1);
        // Upright text in a wide cell keeps its ink within one line height.
        assertThat(inkHeight(output, 20, 25, 240, 34)).isLessThan(34);
    }

    private static OcrRegion compactCell(
            String id, String text, int x, int y, int width, int height) {
        List<OcrPoint> polygon = rectangle(x, y, width, height);
        return new OcrRegion(id, text, polygon,
                List.of(new OcrWord(text, polygon, .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0, id, true);
    }

    private static BufferedImage whiteImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static OcrRegion groupedRegion(
            String id, String text, int x, int y, int width, int height) {
        List<OcrPoint> polygon = rectangle(x, y, width, height);
        return new OcrRegion(id, text, polygon,
                List.of(new OcrWord(text, polygon, .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0, "price-table", false);
    }

    /** Height of the ink bounding box inside a cell, used as a proxy for the rendered type size. */
    private static int inkHeight(BufferedImage image, int x, int y, int width, int height) {
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                Color colour = new Color(image.getRGB(column, row));
                if (colour.getRed() < 128 && colour.getGreen() < 128 && colour.getBlue() < 128) {
                    top = Math.min(top, row);
                    bottom = Math.max(bottom, row);
                    break;
                }
            }
        }
        return bottom < top ? 0 : bottom - top + 1;
    }

    @Test
    void laterNeighbourCleanupDoesNotEraseAnEarlierTranslation() throws Exception {
        Color dark = new Color(35, 35, 38);
        BufferedImage original = new BufferedImage(190, 60, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(dark);
            graphics.fillRect(0, 0, original.getWidth(), original.getHeight());
            graphics.setColor(Color.WHITE);
            graphics.setFont(new java.awt.Font("DejaVu Sans", java.awt.Font.BOLD, 28));
            graphics.drawString("A", 20, 40);
            graphics.drawString("B", 86, 40);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion first = new OcrRegion("first", "A", rectangle(10, 8, 80, 42),
                List.of(new OcrWord("A", rectangle(18, 14, 26, 30), .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0, "row", true);
        OcrRegion second = new OcrRegion("second", "B", rectangle(90, 8, 80, 42),
                List.of(new OcrWord("B", rectangle(84, 14, 26, 30), .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 1, "row", true);
        OverlaySafetyPolicy safety = new OverlaySafetyPolicy();
        ImageTranslationProperties properties = new ImageTranslationProperties(
                1000, 200, 20000, .6f, true, .8, .9);
        ImageTranslationOverlayRenderer renderer = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans")));

        BufferedImage firstOnly = ImageIO.read(new ByteArrayInputStream(renderer.render(source,
                safety.evaluate(List.of(new ImageRegionOverlay(first, "II")), 190, 60, properties))
                .pngBytes()));
        BufferedImage both = ImageIO.read(new ByteArrayInputStream(renderer.render(source,
                safety.evaluate(List.of(
                        new ImageRegionOverlay(first, "II"),
                        new ImageRegionOverlay(second, "B")), 190, 60, properties))
                .pngBytes()));

        int comparedTranslationPixels = 0;
        for (int y = 10; y < 50; y++) for (int x = 10; x < 90; x++) {
            if (firstOnly.getRGB(x, y) != original.getRGB(x, y)) {
                comparedTranslationPixels++;
                assertThat(both.getRGB(x, y)).as("first translation pixel (%s,%s)", x, y)
                        .isEqualTo(firstOnly.getRGB(x, y));
            }
        }
        assertThat(comparedTranslationPixels).isPositive();
    }

    @Test
    void translatedTextUsesWholeParagraphInsteadOfSparseSourceWordMasks() throws Exception {
        BufferedImage original = new BufferedImage(240, 100, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, original.getWidth(), original.getHeight());
            graphics.setColor(new Color(92, 72, 220));
            graphics.setFont(graphics.getFont().deriveFont(java.awt.Font.BOLD, 34f));
            graphics.drawString("SLEEP", 22, 58);
            graphics.drawString("SCORE", 168, 58);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion region = new OcrRegion("r-sparse", "SLEEP SCORE", List.of(
                new OcrPoint(20, 15), new OcrPoint(220, 15),
                new OcrPoint(220, 75), new OcrPoint(20, 75)), List.of(
                        new OcrWord("SLEEP", List.of(
                                new OcrPoint(20, 20), new OcrPoint(72, 20),
                                new OcrPoint(72, 66), new OcrPoint(20, 66)), .99f, true),
                        new OcrWord("SCORE", List.of(
                                new OcrPoint(168, 20), new OcrPoint(220, 20),
                                new OcrPoint(220, 66), new OcrPoint(168, 66)), .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("en", .99f)), 0);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(region, "TRANSLATED")), 240, 100,
                new ImageTranslationProperties(1000, 100, 10000, .6f, true, .7, .8));

        BufferedImage output = ImageIO.read(new ByteArrayInputStream(
                new ImageTranslationOverlayRenderer(new ImageTranslationFontProvider(
                        Set.of("dejavu sans"))).render(source, plan).pngBytes()));

        int changedInGap = 0;
        for (int y = 15; y < 75; y++) for (int x = 80; x < 160; x++) {
            if (output.getRGB(x, y) != original.getRGB(x, y)) changedInGap++;
        }
        assertThat(changedInGap).as("translated glyphs in the gap between source word masks").isPositive();
    }

    @Test
    void replacementPreservesDominantSourceTextColour() throws Exception {
        Color sourceColour = new Color(92, 72, 220);
        BufferedImage original = new BufferedImage(180, 80, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, original.getWidth(), original.getHeight());
            graphics.setColor(sourceColour);
            graphics.setFont(graphics.getFont().deriveFont(java.awt.Font.BOLD, 32f));
            graphics.drawString("SLEEP", 20, 52);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion region = new OcrRegion("r-colour", "SLEEP", List.of(
                new OcrPoint(16, 14), new OcrPoint(130, 14),
                new OcrPoint(130, 62), new OcrPoint(16, 62)), List.of(
                        new OcrWord("SLEEP", List.of(
                                new OcrPoint(16, 14), new OcrPoint(130, 14),
                                new OcrPoint(130, 62), new OcrPoint(16, 62)), .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(region, "REST")), 180, 80,
                new ImageTranslationProperties(1000, 100, 10000, .6f, true, .6, .7));

        BufferedImage output = ImageIO.read(new ByteArrayInputStream(
                new ImageTranslationOverlayRenderer(new ImageTranslationFontProvider(
                        Set.of("dejavu sans"))).render(source, plan).pngBytes()));

        int matchingPixels = 0;
        for (int y = 14; y < 62; y++) for (int x = 16; x < 130; x++) {
            Color pixel = new Color(output.getRGB(x, y));
            if (Math.abs(pixel.getRed() - sourceColour.getRed()) <= 12
                    && Math.abs(pixel.getGreen() - sourceColour.getGreen()) <= 12
                    && Math.abs(pixel.getBlue() - sourceColour.getBlue()) <= 12) {
                matchingPixels++;
            }
        }
        assertThat(matchingPixels).as("replacement pixels close to the source text colour").isPositive();
    }

    @Test
    void clearsSourceAntialiasFringeImmediatelyOutsideProviderPolygon() throws Exception {
        BufferedImage original = new BufferedImage(130, 70, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, original.getWidth(), original.getHeight());
            graphics.setColor(new Color(92, 72, 220));
            graphics.fillRect(91, 32, 1, 2);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        List<OcrPoint> polygon = List.of(
                new OcrPoint(10, 10), new OcrPoint(90, 10),
                new OcrPoint(90, 50), new OcrPoint(10, 50));
        OcrRegion region = new OcrRegion("r-fringe", "SOURCE", polygon,
                List.of(new OcrWord("SOURCE", polygon, .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(region, "OK")), 130, 70,
                new ImageTranslationProperties(1000, 100, 10000, .6f, true, .6, .7));

        BufferedImage output = ImageIO.read(new ByteArrayInputStream(
                new ImageTranslationOverlayRenderer(new ImageTranslationFontProvider(
                        Set.of("dejavu sans"))).render(source, plan).pngBytes()));

        assertThat(output.getRGB(91, 32)).isEqualTo(Color.WHITE.getRGB());
        assertThat(output.getRGB(91, 33)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void rotatedOverlayNeverChangesPixelsOutsideValidatedPolygon() throws Exception {
        BufferedImage original = new BufferedImage(180, 140, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < original.getHeight(); y++) for (int x = 0; x < original.getWidth(); x++)
            original.setRGB(x, y, new Color((x * 3) % 255, (y * 5) % 255, 90).getRGB());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion rotated = new OcrRegion("r-rotated", "Cheese", List.of(
                new OcrPoint(50, 30), new OcrPoint(135, 70),
                new OcrPoint(120, 102), new OcrPoint(35, 62)), List.of(),
                .98f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("en", .99f)), 0);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(rotated, "Translated cheese")), 180, 140,
                new ImageTranslationProperties(10_485_760, 4096, 16_000_000, .6f, true, .2, .35));
        RenderedImage rendered = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(rendered.pngBytes()));
        java.awt.geom.Area mask = OverlaySafetyPolicy.mask(rotated);
        int changedInside = 0;
        for (int y = 0; y < original.getHeight(); y++) for (int x = 0; x < original.getWidth(); x++) {
            if (!mask.contains(x + .5, y + .5)) {
                assertThat(output.getRGB(x, y)).as("outside mask (%s,%s)", x, y)
                        .isEqualTo(original.getRGB(x, y));
            } else if (output.getRGB(x, y) != original.getRGB(x, y)) changedInside++;
        }
        assertThat(plan.safe()).isTrue();
        assertThat(rendered.renderedBlockCount()).isEqualTo(1);
        assertThat(rendered.decisions()).containsExactly(
                new OverlayRenderDecision("r-rotated", OverlayRenderStatus.RENDERED, "rendered"));
        assertThat(changedInside).isPositive();
    }

    @Test
    void missingGlyphCoveragePreservesEveryPixel() throws Exception {
        BufferedImage original = new BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion region = new OcrRegion("r-font", "hello", List.of(
                new OcrPoint(10, 10), new OcrPoint(80, 10), new OcrPoint(80, 40), new OcrPoint(10, 40)),
                List.of(), .98f, true, OcrBlockType.TEXT, List.of(), 0);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(region, "中文")), 100, 60,
                new ImageTranslationProperties(1000, 100, 10000, .6f, true, .5, .6));
        ImageTranslationOverlayRenderer renderer = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of()));

        RenderedImage result = renderer.render(source, plan);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(result.pngBytes()));

        assertThat(result.renderedBlockCount()).isZero();
        assertThat(result.degradation().count(OverlayDegradationReason.FONT)).isEqualTo(1);
        assertThat(result.degradation().count(OverlayDegradationReason.LOW_CONFIDENCE)).isZero();
        assertThat(result.decisions()).containsExactly(
                new OverlayRenderDecision("r-font", OverlayRenderStatus.PRESERVED, "font-coverage"));
        for (int y = 0; y < 60; y++) for (int x = 0; x < 100; x++)
            assertThat(output.getRGB(x, y)).isEqualTo(original.getRGB(x, y));
    }

    @Test
    void replacementThatCannotFitAtReadableSizePreservesOriginalInsteadOfEllipsizing() throws Exception {
        BufferedImage original = new BufferedImage(90, 36, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 90, 36);
            graphics.setColor(Color.BLACK);
            graphics.drawString("日", 10, 22);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion region = new OcrRegion("tiny", "日", List.of(
                new OcrPoint(8, 5), new OcrPoint(30, 5), new OcrPoint(30, 28), new OcrPoint(8, 28)),
                List.of(), .99f, true, OcrBlockType.TEXT, List.of(), 0);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(region, "Sunday with very long overflow")), 90, 36,
                new ImageTranslationProperties(1000, 100, 10000, .6f, true, .5, .6));

        RenderedImage result = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(result.pngBytes()));

        assertThat(result.renderedBlockCount()).isZero();
        assertThat(result.degradation().count(OverlayDegradationReason.TEXT_FIT)).isEqualTo(1);
        for (int y = 0; y < 36; y++) for (int x = 0; x < 90; x++)
            assertThat(output.getRGB(x, y)).isEqualTo(original.getRGB(x, y));
    }

    @Test
    void compactLabelDropsToTheReadableFloorInsteadOfKeepingItsSource() throws Exception {
        BufferedImage original = new BufferedImage(150, 55, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 150, 55);
            graphics.setColor(Color.BLACK);
            graphics.setFont(graphics.getFont().deriveFont(28f));
            graphics.drawString("染髮", 12, 37);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion region = new OcrRegion("readable", "染髮", rectangle(8, 6, 125, 38),
                List.of(new OcrWord("染髮", rectangle(10, 8, 50, 30), .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0, "menu", true);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(region, "Long menu label")), 150, 55,
                new ImageTranslationProperties(1000, 100, 10000, .6f, true, .8, .9));

        RenderedImage result = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);

        // Superseded decision: a compact label used to be preserved whenever it needed to shrink at
        // all. It now drops to READABLE_LABEL_FLOOR rather than leaving the cell untranslated.
        assertThat(result.renderedBlockCount()).isEqualTo(1);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(result.pngBytes()));
        assertThat(inkHeight(output, 8, 6, 125, 38))
                .as("label must land on the readable floor, not shrink into noise")
                .isGreaterThanOrEqualTo(7);
    }

    @Test
    void compactEnglishLabelMayUseAReadableModerateScaleReduction() throws Exception {
        BufferedImage original = new BufferedImage(150, 55, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 150, 55);
            graphics.setColor(Color.BLACK);
            graphics.setFont(graphics.getFont().deriveFont(28f));
            graphics.drawString("染髮", 12, 37);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion region = new OcrRegion("moderate", "染髮", rectangle(8, 6, 125, 38),
                List.of(new OcrWord("染髮", rectangle(10, 8, 50, 30), .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0, "menu", true);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(region, "Hair colour")), 150, 55,
                new ImageTranslationProperties(1000, 100, 10000, .6f, true, .8, .9));

        RenderedImage result = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);

        assertThat(result.renderedBlockCount()).isEqualTo(1);
    }

    @Test
    void oneOverlongCompactCellDoesNotPreventOtherCellsFromRendering() throws Exception {
        BufferedImage original = new BufferedImage(220, 100, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 220, 100);
            graphics.setColor(Color.BLACK);
            graphics.setFont(graphics.getFont().deriveFont(26f));
            graphics.drawString("染髮", 12, 36);
            graphics.drawString("剪髮", 12, 82);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion longCell = new OcrRegion("long", "染髮", rectangle(8, 5, 34, 38),
                List.of(new OcrWord("染髮", rectangle(10, 8, 50, 30), .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0, "menu", true);
        OcrRegion shortCell = new OcrRegion("short", "剪髮", rectangle(8, 52, 125, 38),
                List.of(new OcrWord("剪髮", rectangle(10, 55, 50, 30), .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 1, "menu", true);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(List.of(
                new ImageRegionOverlay(longCell, "Extremely long translated hair package"),
                new ImageRegionOverlay(shortCell, "Cut")), 220, 100,
                new ImageTranslationProperties(1000, 100, 10000, .6f, true, .8, .9));

        RenderedImage result = new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(Set.of("dejavu sans"))).render(source, plan);

        assertThat(result.renderedBlockCount()).isEqualTo(1);
        assertThat(result.decisions()).contains(
                new OverlayRenderDecision("long", OverlayRenderStatus.PRESERVED, "text-fit"),
                new OverlayRenderDecision("short", OverlayRenderStatus.RENDERED, "rendered"));
    }

    private static List<OcrPoint> rectangle(int x, int y, int width, int height) {
        return List.of(new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height));
    }

    @Test
    void transparentPngKeepsAlphaOutsideEdgeTouchingMask() throws Exception {
        BufferedImage original = new BufferedImage(80, 50, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        ValidatedImage source = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes.toByteArray(), "image/png");
        OcrRegion edge = new OcrRegion("r-edge", "text", List.of(
                new OcrPoint(0, 0), new OcrPoint(55, 0), new OcrPoint(55, 25), new OcrPoint(0, 25)),
                List.of(), .99f, true, OcrBlockType.TEXT, List.of(), 0);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(
                List.of(new ImageRegionOverlay(edge, "translated")), 80, 50,
                new ImageTranslationProperties(1000, 100, 10000, .6f, true, .4, .5));

        BufferedImage output = ImageIO.read(new ByteArrayInputStream(
                new ImageTranslationOverlayRenderer().render(source, plan).pngBytes()));

        assertThat(output.getColorModel().hasAlpha()).isTrue();
        assertThat((output.getRGB(70, 40) >>> 24) & 0xff).isZero();
        assertThat(output.getRGB(70, 40)).isEqualTo(original.getRGB(70, 40));
    }
}
