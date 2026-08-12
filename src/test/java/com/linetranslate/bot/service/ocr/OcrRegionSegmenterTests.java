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

    @Test
    void splitsAVisualMenuParagraphIntoIndependentRows() {
        OcrRegion menu = new OcrRegion("menu", "南洋綠咖哩雞飯 110 蔥燒台灣鯛 110 蔥爆牛肉飯 110",
                rectangle(10, 10, 260, 90),
                List.of(
                        word("南洋綠咖哩雞飯", 10, 10, 150, 24), word("110", 225, 10, 35, 24),
                        word("蔥燒台灣鯛", 10, 40, 130, 24), word("110", 225, 40, 35, 24),
                        word("蔥爆牛肉飯", 10, 70, 130, 24), word("110", 225, 70, 35, 24)),
                .98f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("zh", .98f)), 2);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(menu));

        assertThat(result).extracting(OcrRegion::text).containsExactly(
                "南洋綠咖哩雞飯", "110", "蔥燒台灣鯛", "110", "蔥爆牛肉飯", "110");
        assertThat(result).allSatisfy(region -> {
            assertThat(region.id()).startsWith("menu.l");
            assertThat(region.groupId()).isEqualTo("menu");
            assertThat(region.polygon()).hasSize(4);
        });
        assertThat(result.stream().filter(OcrRegion::compactLabel)).hasSize(6);
    }

    @Test
    void splitsDenseMultiColumnCardWithoutMergingColumnsIntoTinyParagraphText() {
        OcrRegion card = new OcrRegion("card", "滷肉飯 鐵板豬排飯 南洋綠咖哩雞飯 鯛魚飯 香酥雞腿飯",
                rectangle(10, 10, 500, 50),
                List.of(
                        word("滷肉飯", 10, 10, 70, 16), word("鐵板豬排飯", 110, 10, 90, 16),
                        word("南洋綠咖哩雞飯", 230, 10, 120, 16),
                        word("鯛魚飯", 380, 10, 60, 16), word("香酥雞腿飯", 460, 10, 50, 16),
                        word("排飯", 10, 34, 70, 16), word("排飯", 110, 34, 90, 16),
                        word("排飯", 230, 34, 120, 16), word("排飯", 380, 34, 60, 16),
                        word("排飯", 460, 34, 50, 16)),
                .97f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("zh", .97f)), 4);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(card));

        assertThat(result).hasSize(10);
        assertThat(result.subList(0, 5)).extracting(OcrRegion::text).containsExactly(
                "滷肉飯", "鐵板豬排飯", "南洋綠咖哩雞飯", "鯛魚飯", "香酥雞腿飯");
        assertThat(result).allMatch(OcrRegion::compactLabel);
    }

    @Test
    void keepsOrdinaryWrappedProseAsOneSemanticRegion() {
        OcrRegion prose = new OcrRegion("prose", "A normal sentence continues on the next visual line",
                rectangle(10, 10, 320, 55),
                List.of(
                        word("A", 10, 10, 12, 18), word("normal", 28, 10, 58, 18),
                        word("sentence", 94, 10, 72, 18), word("continues", 174, 10, 76, 18),
                        word("on", 10, 38, 20, 18), word("the", 38, 38, 30, 18),
                        word("next", 76, 38, 38, 18), word("visual", 122, 38, 50, 18),
                        word("line", 180, 38, 36, 18)),
                .98f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("en", .98f)), 0);

        assertThat(new OcrRegionSegmenter().segment(List.of(prose))).containsExactly(prose);
    }

    @Test
    void usesParagraphConfidenceWhenWordConfidenceIsUnavailable() {
        OcrRegion menu = new OcrRegion("fallback", "雞飯 110 魚飯 90", rectangle(0, 0, 180, 50),
                List.of(
                        new OcrWord("雞飯", rectangle(0, 0, 50, 18), 0, false),
                        new OcrWord("110", rectangle(140, 0, 40, 18), 0, false),
                        new OcrWord("魚飯", rectangle(0, 30, 50, 18), 0, false),
                        new OcrWord("90", rectangle(140, 30, 40, 18), 0, false)),
                .92f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("zh", .92f)), 0);

        assertThat(new OcrRegionSegmenter().segment(List.of(menu)))
                .allSatisfy(region -> {
                    assertThat(region.confidenceKnown()).isTrue();
                    assertThat(region.confidence()).isEqualTo(.92f);
                });
    }

    @Test
    void assignsUniqueReadingOrderAcrossSplitAndUnsplitParagraphs() {
        OcrRegion menu = new OcrRegion("menu-order", "雞飯 110 魚飯 90", rectangle(0, 0, 180, 50),
                List.of(
                        word("雞飯", 0, 0, 50, 18), word("110", 140, 0, 40, 18),
                        word("魚飯", 0, 30, 50, 18), word("90", 140, 30, 40, 18)),
                .98f, true, OcrBlockType.TEXT, List.of(), 0);
        OcrRegion footer = new OcrRegion("footer", "Thank you", rectangle(0, 70, 100, 20),
                List.of(word("Thank", 0, 70, 45, 18), word("you", 50, 70, 30, 18)),
                .98f, true, OcrBlockType.TEXT, List.of(), 1);

        assertThat(new OcrRegionSegmenter().segment(List.of(menu, footer)))
                .extracting(OcrRegion::readingOrder).containsExactly(0, 1, 2, 3, 4);
    }

    private static OcrWord word(String text, int x) {
        return new OcrWord(text, rectangle(x, 10, 20, 30), .98f, true);
    }

    private static OcrWord word(String text, int x, int y, int width, int height) {
        return new OcrWord(text, rectangle(x, y, width, height), .98f, true);
    }

    private static List<OcrPoint> rectangle(int x, int y, int width, int height) {
        return List.of(new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height));
    }
}
