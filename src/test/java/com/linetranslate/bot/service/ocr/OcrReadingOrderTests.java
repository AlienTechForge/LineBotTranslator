package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class OcrReadingOrderTests {

    @Test
    void sortsHorizontalBlocksTopToBottomThenLeftToRight() {
        OcrService.TextBlock lower = block("third", 10, 80, 80, 20);
        OcrService.TextBlock upperRight = block("second", 120, 10, 80, 20);
        OcrService.TextBlock upperLeft = block("first", 10, 12, 80, 20);

        assertThat(OcrReadingOrder.sort(List.of(lower, upperRight, upperLeft)))
                .extracting(OcrService.TextBlock::getText)
                .containsExactly("first", "second", "third");
    }

    @Test
    void sortsVerticalColumnsRightToLeftThenTopToBottom() {
        OcrService.TextBlock left = block("last", 20, 10, 20, 100);
        OcrService.TextBlock rightLower = block("second", 160, 80, 20, 100);
        OcrService.TextBlock rightUpper = block("first", 162, 5, 20, 60);

        assertThat(OcrReadingOrder.sort(List.of(left, rightLower, rightUpper)))
                .extracting(OcrService.TextBlock::getText)
                .containsExactly("first", "second", "last");
    }

    private static OcrService.TextBlock block(String text, int x, int y, int width, int height) {
        return new OcrService.TextBlock(text, x, y, width, height, 0.95f);
    }
}
