package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ImageTranslationPipelineLayoutTests {

    @Test
    void rotatedRegionUsesItsReadingAxesInsteadOfAxisAlignedBounds() {
        List<OcrPoint> polygon = List.of(
                new OcrPoint(300, 10), new OcrPoint(300, 270),
                new OcrPoint(270, 270), new OcrPoint(270, 10));
        OcrRegion region = new OcrRegion("rotated", "會員染髮", polygon,
                List.of(new OcrWord("會員染髮", polygon, .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0, "menu", true);

        var layout = ImageTranslationPipeline.layout(region);

        assertThat(layout.width()).isEqualTo(260);
        assertThat(layout.height()).isEqualTo(30);
        assertThat(layout.maxLines()).isEqualTo(1);
        assertThat(layout.maxCharacters()).isLessThanOrEqualTo(18);
    }

    @Test
    void proseBudgetNeverAsksTheModelToDropSourceMeaning() {
        List<OcrPoint> polygon = List.of(
                new OcrPoint(10, 10), new OcrPoint(610, 10),
                new OcrPoint(610, 190), new OcrPoint(10, 190));
        String source = "自分に対する抗議集会を受けて頼清徳総統は言い返した。"
                + "民進党が国民党を批判するとき最もよく使う言い回しである。";
        OcrRegion region = new OcrRegion("article", source, polygon,
                List.of(new OcrWord(source, polygon, .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(), 0);

        var layout = ImageTranslationPipeline.layout(region);

        assertThat(layout.maxCharacters())
                .isGreaterThanOrEqualTo(source.codePointCount(0, source.length()) * 2);
    }

    @Test
    void aColumnOfVerticalWritingIsBudgetedAlongTheColumn() {
        // Production geometry: a vertical dish name is 30 wide and 163 tall, five glyphs. Measured
        // across the horizontal axes it would read as four lines of two characters.
        OcrRegion column = VerticalFixtures.singleColumn("column", "糖醋排骨飯", 40, 25, 30, 163);

        var layout = ImageTranslationPipeline.layout(column);

        assertThat(layout.width()).as("the line is as long as the column").isEqualTo(163);
        assertThat(layout.height()).as("and as thick as the column is wide").isEqualTo(30);
        assertThat(layout.maxLines()).isEqualTo(1);
        assertThat(layout.maxCharacters()).isGreaterThanOrEqualTo(5);
        assertThat(layout.x()).isEqualTo(40);
        assertThat(layout.y()).isEqualTo(25);
    }

    @Test
    void aFlowOfColumnsIsBudgetedAcrossItsWidthInColumnPitches() {
        List<OcrPoint> union = VerticalFixtures.rectangle(20, 20, 160, 170);
        OcrRegion flow = new OcrRegion("flow", "字".repeat(36), union,
                VerticalFixtures.column("字".repeat(8), 20, 20, 34, 20, 21), .99f, true, OcrBlockType.TEXT,
                List.of(), 0, "flow", false, WritingMode.VERTICAL_RL, List.of());

        var layout = ImageTranslationPipeline.layout(flow);

        assertThat(layout.width()).isEqualTo(170);
        assertThat(layout.height()).isEqualTo(160);
        assertThat(layout.maxLines()).as("160 across, a column every 1.2 glyphs of 34").isEqualTo(3);
        assertThat(layout.maxCharacters()).isGreaterThanOrEqualTo(3 * (170 / 34));
    }

    @Test
    void aColumnFromTheProviderFrameIsMeasuredInTheUprightFrame() {
        // The same column as Vision lists it: from its top right corner, first edge pointing down.
        List<OcrPoint> provider = List.of(new OcrPoint(70, 25), new OcrPoint(70, 188),
                new OcrPoint(40, 188), new OcrPoint(40, 25));
        OcrRegion column = new OcrRegion("provider", "糖醋排骨飯", provider,
                List.of(new OcrWord("糖醋排骨飯", provider, .99f, true)), .99f, true, OcrBlockType.TEXT,
                List.of(), 0, "provider", false, WritingMode.VERTICAL_RL, List.of());

        var layout = ImageTranslationPipeline.layout(column);

        assertThat(layout.width()).isEqualTo(163);
        assertThat(layout.height()).isEqualTo(30);
        assertThat(layout.maxLines()).isEqualTo(1);
    }

    @Test
    void theBudgetOfARegionThatIsNotVerticalWritingIsUnchangedAcrossTheHorizontalAxes() {
        List<OcrPoint> polygon = VerticalFixtures.rectangle(40, 25, 30, 163);
        OcrRegion column = new OcrRegion("plain", "糖醋排骨飯", polygon,
                List.of(new OcrWord("糖醋排骨飯", polygon, .99f, true)), .99f, true, OcrBlockType.TEXT,
                List.of(), 0, "plain", false);

        var layout = ImageTranslationPipeline.layout(column);

        assertThat(layout.width()).isEqualTo(30);
        assertThat(layout.height()).isEqualTo(163);
    }
}
