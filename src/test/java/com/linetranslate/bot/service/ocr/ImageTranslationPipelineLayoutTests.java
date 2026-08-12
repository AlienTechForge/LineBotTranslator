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
}
