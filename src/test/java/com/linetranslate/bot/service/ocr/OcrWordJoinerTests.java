package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class OcrWordJoinerTests {

    @Test
    void koreanWordsKeepTheirSpaces() {
        assertThat(join("오늘", "날씨가", "좋다")).isEqualTo("오늘 날씨가 좋다");
    }

    @Test
    void koreanAndHanjaNeighboursKeepASpaceInBothOrders() {
        assertThat(join("大韓民國", "헌법")).isEqualTo("大韓民國 헌법");
        assertThat(join("헌법", "大韓民國")).isEqualTo("헌법 大韓民國");
    }

    @Test
    void chineseWordsStillJoinWithoutSpaces() {
        assertThat(join("今天", "天氣", "很好")).isEqualTo("今天天氣很好");
    }

    @Test
    void japaneseWordsStillJoinWithoutSpaces() {
        assertThat(join("今日は", "天気が", "いい", "デス")).isEqualTo("今日は天気がいいデス");
    }

    @Test
    void koreanNextToLatinOrDigitsGetsASpace() {
        assertThat(join("커피", "latte", "3잔")).isEqualTo("커피 latte 3잔");
        assertThat(join("Hello", "세계")).isEqualTo("Hello 세계");
    }

    @Test
    void latinAndCjkBoundariesAreUnchanged() {
        assertThat(join("Green", "curry", "rice")).isEqualTo("Green curry rice");
        assertThat(join("套餐", "NT$110")).isEqualTo("套餐 NT$110");
        assertThat(join("NT$110", "套餐")).isEqualTo("NT$110 套餐");
    }

    @Test
    void emptySideNeverGetsASeparator() {
        assertThat(OcrWordJoiner.scriptSeparator("", "오늘")).isEmpty();
        assertThat(OcrWordJoiner.scriptSeparator("오늘", "")).isEmpty();
    }

    @Test
    void segmenterKeepsTheSpaceInAKoreanMenuLabelItSplitsFromItsPrices() {
        OcrRegion row = new OcrRegion("korean-price-row", "회원 염색 2000 2300 2600 2900",
                rectangle(10, 10, 300, 24),
                List.of(
                        word("회원", 10, 10, 40, 20), word("염색", 56, 10, 40, 20),
                        word("2000", 130, 10, 40, 20), word("2300", 190, 10, 40, 20),
                        word("2600", 250, 10, 40, 20), word("2900", 310, 10, 40, 20)),
                .98f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("ko", .98f)), 0);

        assertThat(new OcrRegionSegmenter().segment(List.of(row))).extracting(OcrRegion::text)
                .containsExactly("회원 염색", "2000", "2300", "2600", "2900");
    }

    private static String join(String... words) {
        StringBuilder text = new StringBuilder();
        for (String word : words) {
            text.append(OcrWordJoiner.scriptSeparator(text, word)).append(word);
        }
        return text.toString();
    }

    private static OcrWord word(String text, int x, int y, int width, int height) {
        return new OcrWord(text, rectangle(x, y, width, height), .98f, true);
    }

    private static List<OcrPoint> rectangle(int x, int y, int width, int height) {
        return List.of(new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height));
    }
}
