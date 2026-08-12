package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class OcrRegionSegmenterTests {

    @Test
    void splitsWidelySpacedCompactUiLabelsAndKeepsAGroupIdentity() {
        OcrRegion paragraph = new OcrRegion("weekdays", "六 日 一 二 三 四 五", rectangle(10, 10, 330, 30),
                List.of(
                        word("六", 10), word("日", 60), word("一", 110), word("二", 160),
                        word("三", 210), word("四", 260), word("五", 310)),
                .98f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("zh", .98f)), 3);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(paragraph));

        assertThat(result).hasSize(7).extracting(OcrRegion::text)
                .containsExactly("六", "日", "一", "二", "三", "四", "五");
        assertThat(result).allSatisfy(region -> {
            assertThat(region.id()).startsWith("weekdays.s");
            assertThat(region.groupId()).isEqualTo("weekdays");
            assertThat(region.compactLabel()).isTrue();
            assertThat(region.polygon().get(1).x() - region.polygon().get(0).x()).isGreaterThan(20);
        });
    }

    @Test
    void keepsNormalSentenceWordsInOneRegion() {
        OcrRegion sentence = new OcrRegion("sentence", "Sleep Score", rectangle(10, 10, 150, 30),
                List.of(new OcrWord("Sleep", rectangle(10, 10, 60, 30), .99f, true),
                        new OcrWord("Score", rectangle(80, 10, 70, 30), .99f, true)),
                .99f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("en", .99f)), 0);

        assertThat(new OcrRegionSegmenter().segment(List.of(sentence))).containsExactly(sentence);
    }

    @Test
    void keepsTwoShortWordsTogetherEvenWhenTheGapIsWide() {
        OcrRegion phrase = new OcrRegion("phrase", "In To", rectangle(10, 10, 150, 30),
                List.of(word("In", 10), word("To", 110)),
                .99f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("en", .99f)), 0);

        assertThat(new OcrRegionSegmenter().segment(List.of(phrase))).containsExactly(phrase);
    }

    private static OcrWord word(String text, int x) {
        return new OcrWord(text, rectangle(x, 10, 20, 30), .98f, true);
    }

    private static List<OcrPoint> rectangle(int x, int y, int width, int height) {
        return List.of(new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height));
    }
}
