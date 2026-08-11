package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class TranslatedBlockMapperTests {

    @Test
    void mapsTranslatedLinesToReliableBlocksInReadingOrder() {
        OcrService.TextBlock second = block("world", 120, 10, 80, 30, 0.95f);
        OcrService.TextBlock first = block("hello", 10, 10, 80, 30, 0.98f);

        List<ImageOverlayBlock> result = TranslatedBlockMapper.map(
                List.of(second, first), "你好\n世界", 0.60f);

        assertThat(result).extracting(value -> value.source().getText())
                .containsExactly("hello", "world");
        assertThat(result).extracting(ImageOverlayBlock::replacement)
                .containsExactly("你好", "世界");
    }

    @Test
    void preservesLowConfidenceAndUnmappedBlocksWithoutInventingTranslation() {
        OcrService.TextBlock reliable = block("safe", 10, 10, 80, 30, 0.95f);
        OcrService.TextBlock uncertain = block("maybe", 10, 60, 80, 30, 0.30f);
        OcrService.TextBlock remaining = block("last", 10, 110, 80, 30, 0.90f);

        List<ImageOverlayBlock> result = TranslatedBlockMapper.map(
                List.of(reliable, uncertain, remaining), "安全", 0.60f);

        assertThat(result).extracting(ImageOverlayBlock::replacement)
                .containsExactly("安全", "", "");
    }

    @Test
    void keepsProviderLineExpansionInsideTheLastReliableBlock() {
        List<ImageOverlayBlock> result = TranslatedBlockMapper.map(
                List.of(
                        block("first", 10, 10, 80, 30, 0.95f),
                        block("second", 10, 60, 80, 50, 0.95f)),
                "第一\n第二之一\n第二之二",
                0.60f);

        assertThat(result).extracting(ImageOverlayBlock::replacement)
                .containsExactly("第一", "第二之一\n第二之二");
    }

    private static OcrService.TextBlock block(
            String text, int x, int y, int width, int height, float confidence) {
        return new OcrService.TextBlock(text, x, y, width, height, confidence);
    }
}
