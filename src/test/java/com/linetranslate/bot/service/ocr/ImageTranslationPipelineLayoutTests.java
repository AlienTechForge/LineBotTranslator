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
}
