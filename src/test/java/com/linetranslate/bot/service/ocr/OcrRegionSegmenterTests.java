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
    void splitsARotatedVisualMenuInItsLocalReadingCoordinates() {
        OcrRegion menu = new OcrRegion("rotated-menu", "染髮 2000 1900 2300 剪髮 2700 3400 3900",
                rotatedRectangle(300, 10, 260, 60),
                List.of(
                        rotatedWord("染髮", 300, 10, 10, 0, 80, 20),
                        rotatedWord("2000", 300, 10, 135, 0, 35, 20),
                        rotatedWord("1900", 300, 10, 225, 0, 35, 20),
                        rotatedWord("剪髮", 300, 10, 10, 35, 80, 20),
                        rotatedWord("2700", 300, 10, 135, 35, 35, 20),
                        rotatedWord("3400", 300, 10, 225, 35, 35, 20)),
                .98f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("zh", .98f)), 2);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(menu));

        assertThat(result).extracting(OcrRegion::text).containsExactly(
                "染髮", "2000", "1900", "剪髮", "2700", "3400");
        assertThat(result).allSatisfy(region -> {
            assertThat(region.id()).startsWith("rotated-menu.l");
            assertThat(region.groupId()).isEqualTo("rotated-menu");
            assertThat(region.orientation()).isEqualTo(OcrOrientation.VERTICAL);
            assertThat(region.compactLabel()).isTrue();
        });
    }

    @Test
    void splitsARotatedSingleMenuRowWhenSeparatedColumnsContainPrices() {
        OcrRegion row = new OcrRegion("rotated-price-row", "會員染髮 2000 2300 2600 2900",
                rotatedRectangle(300, 10, 260, 24),
                List.of(
                        rotatedWord("會員染髮", 300, 10, 0, 0, 80, 20),
                        rotatedWord("2000", 300, 10, 105, 0, 30, 20),
                        rotatedWord("2300", 300, 10, 150, 0, 30, 20),
                        rotatedWord("2600", 300, 10, 195, 0, 30, 20),
                        rotatedWord("2900", 300, 10, 230, 0, 30, 20)),
                .98f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("zh", .98f)), 2);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(row));

        assertThat(result).extracting(OcrRegion::text)
                .containsExactly("會員染髮", "2000", "2300", "2600", "2900");
        assertThat(result).allMatch(OcrRegion::compactLabel);
    }

    @Test
    void isolatesSizeBadgesAndPricesFromTheTranslatableMenuLabel() {
        OcrRegion row = new OcrRegion("sized-price-row", "會員染髮 S 2000 M 2300 L 2600 XL 2900",
                rotatedRectangle(300, 10, 300, 24),
                List.of(
                        rotatedWord("會員染髮", 300, 10, 0, 0, 80, 20),
                        rotatedWord("S", 300, 10, 92, 0, 14, 20),
                        rotatedWord("2000", 300, 10, 110, 0, 30, 20),
                        rotatedWord("M", 300, 10, 150, 0, 16, 20),
                        rotatedWord("2300", 300, 10, 170, 0, 30, 20),
                        rotatedWord("L", 300, 10, 210, 0, 14, 20),
                        rotatedWord("2600", 300, 10, 228, 0, 30, 20),
                        rotatedWord("XL", 300, 10, 264, 0, 16, 20),
                        rotatedWord("2900", 300, 10, 282, 0, 18, 20)),
                .98f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("zh", .98f)), 2);

        assertThat(new OcrRegionSegmenter().segment(List.of(row))).extracting(OcrRegion::text)
                .containsExactly("會員染髮", "S", "2000", "M", "2300", "L", "2600", "XL", "2900");
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
    void keepsRaggedCjkArticleLinesAsOneSemanticRegion() {
        OcrRegion prose = new OcrRegion("jp-article",
                "自分に対する抗議集会を受けて頼清徳総統は言い返した民進党が国民党を批判する",
                rectangle(10, 10, 570, 100),
                List.of(
                        word("自分に対する抗議集会を受けて", 10, 10, 185, 24),
                        word("頼清徳総統は", 232, 10, 125, 24),
                        word("言い返した", 405, 10, 105, 24),
                        word("民進党が国民党を", 10, 45, 150, 24),
                        word("批判するとき", 199, 45, 115, 24),
                        word("最もよく使う", 371, 45, 120, 24),
                        word("中国共産党の仲間だ", 10, 80, 175, 24),
                        word("というものだ", 244, 80, 105, 24),
                        word("異なる意見を攻撃する", 401, 80, 165, 24)),
                .98f, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage("ja", .98f)), 0);

        assertThat(new OcrRegionSegmenter().segment(List.of(prose))).containsExactly(prose);
    }

    @Test
    void splitsACompactSingleColumnMenuIntoIndependentRows() {
        OcrRegion menu = new OcrRegion("receipt-items",
                "南洋綠咖哩雞飯 蔥燒台灣鯛 蔥爆牛肉飯 酥炸大雞排飯",
                rectangle(10, 10, 180, 112),
                List.of(
                        word("南洋綠咖哩雞飯", 16, 12, 130, 20),
                        word("蔥燒台灣鯛", 16, 40, 105, 20),
                        word("蔥爆牛肉飯", 16, 68, 105, 20),
                        word("酥炸大雞排飯", 16, 96, 120, 20)),
                .98f, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage("zh", .98f)), 0);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(menu));

        assertThat(result).extracting(OcrRegion::text).containsExactly(
                "南洋綠咖哩雞飯", "蔥燒台灣鯛", "蔥爆牛肉飯", "酥炸大雞排飯");
        assertThat(result).allMatch(OcrRegion::compactLabel);
        assertThat(result).allSatisfy(region -> {
            java.awt.Rectangle bounds = OverlaySafetyPolicy.polygon(region.polygon()).getBounds();
            assertThat(bounds.x).isGreaterThanOrEqualTo(12);
            assertThat(bounds.getMaxX()).isLessThanOrEqualTo(190);
        });
    }

    @Test
    void mergesASeparatedCjkSentenceEndingBackIntoThePreviousParagraph() {
        OcrRegion paragraph = new OcrRegion("article-main",
                "同じ陣營に屬するはずの人からもこのような提言が出されるほどレッテル張りは深刻なのであ",
                rectangle(20, 20, 540, 150),
                List.of(
                        word("同じ陣營に屬するはずの人からも", 20, 20, 330, 26),
                        word("このような提言が出されるほど", 20, 70, 300, 26),
                        word("レッテル張りは深刻なのであ", 20, 120, 280, 26)),
                .98f, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage("ja", .98f)), 0);
        OcrRegion ending = new OcrRegion("article-ending", "る。",
                rectangle(20, 175, 48, 26),
                List.of(word("る。", 20, 175, 48, 26)),
                .97f, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage("ja", .97f)), 1);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(paragraph, ending));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).text()).endsWith("なのである。");
        assertThat(result.get(0).words()).hasSize(4);
        assertThat(OverlaySafetyPolicy.polygon(result.get(0).polygon()).getBounds().getMaxY())
                .isGreaterThanOrEqualTo(201);
    }

    @Test
    void doesNotMergeANearbyShortLabelWithoutSentenceEndingPunctuation() {
        OcrRegion description = new OcrRegion("description",
                "洗髮剪髮頭皮隔離染髮護理完整服務內容說明",
                rectangle(20, 20, 360, 70),
                List.of(word("洗髮剪髮頭皮隔離染髮護理完整服務內容說明", 20, 20, 340, 26)),
                .98f, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage("zh", .98f)), 0);
        OcrRegion label = new OcrRegion("label", "原價",
                rectangle(20, 96, 60, 26), List.of(word("原價", 20, 96, 60, 26)),
                .98f, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage("zh", .98f)), 1);

        assertThat(new OcrRegionSegmenter().segment(List.of(description, label)))
                .extracting(OcrRegion::text)
                .containsExactly(description.text(), label.text());
    }

    @Test
    void oneDateInsideProseDoesNotTurnTheParagraphIntoATable() {
        OcrRegion prose = new OcrRegion("dated-prose", "Report published 2026 followed by ordinary prose",
                rectangle(10, 10, 400, 90),
                List.of(
                        word("Report", 10, 10, 70, 22), word("published", 105, 10, 85, 22),
                        word("2026", 300, 10, 50, 22),
                        word("followed", 10, 42, 75, 22), word("by", 115, 42, 25, 22),
                        word("ordinary", 185, 42, 80, 22),
                        word("prose", 10, 74, 55, 16), word("continues", 110, 74, 75, 16),
                        word("here", 240, 74, 45, 16)),
                .98f, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage("en", .98f)), 0);

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

    @Test
    void splitsTopToBottomColumnsIntoOneRegionPerItem() {
        // Two columns of vertical CJK, two dish names each, in Vision's reading order:
        // top to bottom within a column, then on to the next column.
        List<OcrWord> words = List.of(
                word("澎", 10, 10, 22, 22), word("湃", 10, 34, 22, 22), word("飯", 10, 58, 22, 22),
                word("風", 10, 110, 22, 22), word("味", 10, 134, 22, 22), word("飯", 10, 158, 22, 22),
                word("香", 60, 10, 22, 22), word("酥", 60, 34, 22, 22), word("飯", 60, 58, 22, 22),
                word("經", 60, 110, 22, 22), word("典", 60, 134, 22, 22), word("飯", 60, 158, 22, 22));
        OcrRegion card = new OcrRegion("card", "澎湃飯 風味飯 香酥飯 經典飯",
                rectangle(5, 5, 90, 185), words,
                .98f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("zh", .98f)), 0);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(card));

        assertThat(result).hasSize(4).extracting(OcrRegion::text)
                .containsExactly("澎湃飯", "風味飯", "香酥飯", "經典飯");
        assertThat(result).allSatisfy(region -> assertThat(region.id()).startsWith("card.v"));
        // Each item keeps its own column, so the two columns stay horizontally separated.
        assertThat(result.get(0).polygon().get(0).x()).isLessThan(result.get(2).polygon().get(0).x());
    }

    @Test
    void singleGlyphHorizontalTextIsNotMistakenForColumns() {
        // Real proportions from a Japanese article page: every word is one glyph, but the nearest
        // neighbour below is the next line (31px away) while the neighbour beside is 6px away.
        List<OcrWord> words = new java.util.ArrayList<>();
        for (int line = 0; line < 3; line++) {
            for (int column = 0; column < 5; column++) {
                words.add(word("字", 10 + column * 30, 10 + line * 55, 24, 24));
            }
        }
        OcrRegion prose = new OcrRegion("prose", "字字字字字", rectangle(5, 5, 170, 175), words,
                .99f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("ja", .99f)), 0);

        assertThat(new OcrRegionSegmenter().segment(List.of(prose)))
                .allSatisfy(region -> assertThat(region.id()).doesNotContain(".v"));
    }

    @Test
    void horizontalParagraphIsNeverSplitAlongTheVerticalAxis() {
        List<OcrWord> words = List.of(
                word("The", 10, 10, 40, 20), word("quick", 60, 10, 60, 20),
                word("brown", 130, 10, 70, 20), word("fox", 210, 10, 40, 20),
                word("jumps", 10, 40, 60, 20), word("over", 80, 40, 50, 20),
                word("the", 140, 40, 40, 20), word("lazy", 190, 40, 45, 20));
        OcrRegion paragraph = new OcrRegion("prose", "The quick brown fox jumps over the lazy",
                rectangle(5, 5, 250, 60), words,
                .99f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("en", .99f)), 0);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(paragraph));

        assertThat(result).allSatisfy(region ->
                assertThat(region.id()).doesNotContain(".v"));
    }

    private static OcrWord word(String text, int x) {
        return new OcrWord(text, rectangle(x, 10, 20, 30), .98f, true);
    }

    private static OcrWord word(String text, int x, int y, int width, int height) {
        return new OcrWord(text, rectangle(x, y, width, height), .98f, true);
    }

    private static OcrWord rotatedWord(String text, int originX, int originY,
            int x, int y, int width, int height) {
        return new OcrWord(text, rotatedRectangle(originX, originY, x, y, width, height), .98f, true);
    }

    private static List<OcrPoint> rotatedRectangle(int originX, int originY, int width, int height) {
        return rotatedRectangle(originX, originY, 0, 0, width, height);
    }

    private static List<OcrPoint> rotatedRectangle(int originX, int originY,
            int x, int y, int width, int height) {
        return List.of(
                rotatedPoint(originX, originY, x, y),
                rotatedPoint(originX, originY, x + width, y),
                rotatedPoint(originX, originY, x + width, y + height),
                rotatedPoint(originX, originY, x, y + height));
    }

    private static OcrPoint rotatedPoint(int originX, int originY, int x, int y) {
        return new OcrPoint(originX - y, originY + x);
    }

    private static List<OcrPoint> rectangle(int x, int y, int width, int height) {
        return List.of(new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height));
    }
}
