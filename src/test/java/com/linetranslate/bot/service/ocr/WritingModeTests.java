package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class WritingModeTests {

    @Test
    void theFrameDefaultNamesTheQuarterTheLineEdgePointsAt() {
        assertThat(WritingMode.ofFrame(edge(1000, 0))).isEqualTo(WritingMode.HORIZONTAL);
        assertThat(WritingMode.ofFrame(edge(0, 1000))).isEqualTo(WritingMode.ROTATED_90);
        assertThat(WritingMode.ofFrame(edge(-1000, 0))).isEqualTo(WritingMode.ROTATED_180);
        assertThat(WritingMode.ofFrame(edge(0, -1000))).isEqualTo(WritingMode.ROTATED_270);
        assertThat(WritingMode.ofFrame(edge(1000, 1000))).isEqualTo(WritingMode.SKEWED);
        assertThat(WritingMode.ofFrame(edge(1000, -400))).isEqualTo(WritingMode.SKEWED);
    }

    @Test
    void theFrameDefaultNeverSaysVerticalWriting() {
        for (int degrees = -180; degrees <= 180; degrees += 3) {
            assertThat(WritingMode.ofFrame(edgeAt(degrees)).vertical()).as("%d degrees", degrees).isFalse();
        }
    }

    /**
     * The default is derived with the bands {@link OcrRegion#orientation()} always used, so a
     * region that was not horizontal before is not horizontal now and no decision taken on an
     * upright page changes. Swept in half degrees around every boundary.
     */
    @Test
    void theFrameDefaultAgreesWithTheLegacyOrientationBandsEverywhere() {
        for (double degrees = -180; degrees <= 180; degrees += .5) {
            OcrRegion region = regionAt(degrees);
            WritingMode mode = region.writingMode();

            switch (region.orientation()) {
                case HORIZONTAL -> assertThat(mode).as("%s degrees", degrees)
                        .isIn(WritingMode.HORIZONTAL, WritingMode.ROTATED_180);
                case VERTICAL -> assertThat(mode).as("%s degrees", degrees)
                        .isIn(WritingMode.ROTATED_90, WritingMode.ROTATED_270);
                case ROTATED -> assertThat(mode).as("%s degrees", degrees).isEqualTo(WritingMode.SKEWED);
            }
        }
    }

    @Test
    void theFrameToleranceIsTheLegacySevenDegrees() {
        assertThat(WritingMode.FRAME_TOLERANCE_DEGREES).isEqualTo(7);
        assertThat(WritingMode.ofAngle(7, WritingMode.FRAME_TOLERANCE_DEGREES)).isEqualTo(WritingMode.HORIZONTAL);
        assertThat(WritingMode.ofAngle(7.01, WritingMode.FRAME_TOLERANCE_DEGREES)).isEqualTo(WritingMode.SKEWED);
        assertThat(WritingMode.ofAngle(83, WritingMode.FRAME_TOLERANCE_DEGREES)).isEqualTo(WritingMode.ROTATED_90);
        assertThat(WritingMode.ofAngle(-97, WritingMode.FRAME_TOLERANCE_DEGREES)).isEqualTo(WritingMode.ROTATED_270);
        assertThat(WritingMode.ofAngle(173, WritingMode.FRAME_TOLERANCE_DEGREES)).isEqualTo(WritingMode.ROTATED_180);
        assertThat(WritingMode.ofAngle(10, 10)).isEqualTo(WritingMode.HORIZONTAL);
        assertThat(WritingMode.ofAngle(100, 10)).isEqualTo(WritingMode.ROTATED_90);
    }

    @Test
    void aPolygonWithoutAnEdgeIsHorizontal() {
        assertThat(WritingMode.ofFrame(null)).isEqualTo(WritingMode.HORIZONTAL);
        assertThat(WritingMode.ofFrame(List.of())).isEqualTo(WritingMode.HORIZONTAL);
        assertThat(WritingMode.ofFrame(List.of(new OcrPoint(1, 1)))).isEqualTo(WritingMode.HORIZONTAL);
    }

    @Test
    void quarterTurnsAndFamiliesFollowTheMode() {
        assertThat(WritingMode.HORIZONTAL.quarterTurns()).isZero();
        assertThat(WritingMode.VERTICAL_RL.quarterTurns()).isZero();
        assertThat(WritingMode.VERTICAL_LR.quarterTurns()).isZero();
        assertThat(WritingMode.ROTATED_90.quarterTurns()).isEqualTo(1);
        assertThat(WritingMode.ROTATED_180.quarterTurns()).isEqualTo(2);
        assertThat(WritingMode.ROTATED_270.quarterTurns()).isEqualTo(3);
        assertThat(WritingMode.SKEWED.quarterTurns()).isEqualTo(-1);

        assertThat(WritingMode.HORIZONTAL.upright()).isTrue();
        assertThat(WritingMode.VERTICAL_RL.upright()).isTrue();
        assertThat(WritingMode.ROTATED_90.upright()).isFalse();
        assertThat(WritingMode.SKEWED.upright()).isFalse();
        assertThat(WritingMode.VERTICAL_LR.vertical()).isTrue();
        assertThat(WritingMode.HORIZONTAL.vertical()).isFalse();
    }

    // --- the region carries it ---------------------------------------------------------------------

    @Test
    void aRegionDerivesItsModeFromItsPolygonUnlessGivenOne() {
        List<OcrPoint> down = List.of(new OcrPoint(50, 0), new OcrPoint(50, 90),
                new OcrPoint(20, 90), new OcrPoint(20, 0));

        OcrRegion derived = region(down, null);
        OcrRegion given = region(down, WritingMode.VERTICAL_RL);

        assertThat(derived.writingMode()).isEqualTo(WritingMode.ROTATED_90);
        assertThat(given.writingMode()).isEqualTo(WritingMode.VERTICAL_RL);
        assertThat(derived.flowCells()).isEmpty();
    }

    @Test
    void theShorterConstructorsStillBuildRegionsWithADerivedMode() {
        List<OcrPoint> flat = List.of(new OcrPoint(0, 0), new OcrPoint(90, 0),
                new OcrPoint(90, 20), new OcrPoint(0, 20));

        OcrRegion nine = new OcrRegion("a", "text", flat, List.of(), .9f, true,
                OcrBlockType.TEXT, List.of(), 0);
        OcrRegion eleven = new OcrRegion("a", "text", flat, List.of(), .9f, true,
                OcrBlockType.TEXT, List.of(), 0, "g", true);

        assertThat(nine.writingMode()).isEqualTo(WritingMode.HORIZONTAL);
        assertThat(eleven.writingMode()).isEqualTo(WritingMode.HORIZONTAL);
        assertThat(eleven.groupId()).isEqualTo("g");
        assertThat(eleven.compactLabel()).isTrue();
        assertThat(nine.flowCells()).isEmpty();
    }

    @Test
    void withGeometryDerivesAgainFromTheNewPolygonAndKeepsEverythingElse() {
        List<OcrPoint> down = List.of(new OcrPoint(50, 0), new OcrPoint(50, 90),
                new OcrPoint(20, 90), new OcrPoint(20, 0));
        List<OcrPoint> flat = List.of(new OcrPoint(0, 0), new OcrPoint(90, 0),
                new OcrPoint(90, 30), new OcrPoint(0, 30));
        OcrRegion turned = new OcrRegion("a", "text", down, List.of(), .9f, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage("en", .9f)), 4, "group", true);

        OcrRegion upright = turned.withGeometry(flat, List.of(), null);
        OcrRegion forced = turned.withGeometry(flat, List.of(), WritingMode.VERTICAL_LR);
        OcrRegion later = turned.withReadingOrder(9);

        assertThat(turned.writingMode()).isEqualTo(WritingMode.ROTATED_90);
        assertThat(upright.writingMode()).isEqualTo(WritingMode.HORIZONTAL);
        assertThat(upright.polygon()).isEqualTo(flat);
        assertThat(forced.writingMode()).isEqualTo(WritingMode.VERTICAL_LR);
        assertThat(upright.id()).isEqualTo("a");
        assertThat(upright.groupId()).isEqualTo("group");
        assertThat(upright.compactLabel()).isTrue();
        assertThat(upright.readingOrder()).isEqualTo(4);
        assertThat(upright.languages()).isEqualTo(turned.languages());
        assertThat(later.readingOrder()).isEqualTo(9);
        assertThat(later.writingMode()).isEqualTo(WritingMode.ROTATED_90);
        assertThat(later.polygon()).isEqualTo(down);
    }

    @Test
    void flowCellsAreCopiedAndDefaultToNone() {
        List<OcrPoint> cell = new java.util.ArrayList<>(List.of(new OcrPoint(0, 0), new OcrPoint(9, 0),
                new OcrPoint(9, 9), new OcrPoint(0, 9)));
        List<List<OcrPoint>> cells = new java.util.ArrayList<>(List.of(cell));
        OcrRegion region = new OcrRegion("a", "text", cell, List.of(), .9f, true, OcrBlockType.TEXT,
                List.of(), 0, "a", false, null, cells);

        cell.clear();
        cells.clear();

        assertThat(region.flowCells()).hasSize(1);
        assertThat(region.flowCells().get(0)).hasSize(4);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static List<OcrPoint> edge(int dx, int dy) {
        return List.of(new OcrPoint(0, 0), new OcrPoint(dx, dy), new OcrPoint(dx, dy + 5), new OcrPoint(0, 5));
    }

    private static List<OcrPoint> edgeAt(double degrees) {
        return edge((int) Math.round(10_000 * Math.cos(Math.toRadians(degrees))),
                (int) Math.round(10_000 * Math.sin(Math.toRadians(degrees))));
    }

    private static OcrRegion regionAt(double degrees) {
        return region(edgeAt(degrees), null);
    }

    private static OcrRegion region(List<OcrPoint> polygon, WritingMode mode) {
        return new OcrRegion("r", "text", polygon, List.of(), .9f, true, OcrBlockType.TEXT,
                List.of(), 0, "r", false, mode, List.of());
    }
}
