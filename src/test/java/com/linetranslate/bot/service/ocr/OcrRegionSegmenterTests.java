package com.linetranslate.bot.service.ocr;

import static com.linetranslate.bot.service.ocr.OcrArticleFixtures.GLYPH;
import static com.linetranslate.bot.service.ocr.OcrArticleFixtures.LEFT;
import static com.linetranslate.bot.service.ocr.OcrArticleFixtures.LINE_WIDTH;
import static com.linetranslate.bot.service.ocr.OcrArticleFixtures.PITCH;
import static com.linetranslate.bot.service.ocr.OcrArticleFixtures.nextLineTop;
import static com.linetranslate.bot.service.ocr.OcrArticleFixtures.paragraph;
import static com.linetranslate.bot.service.ocr.OcrArticleFixtures.tail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class OcrRegionSegmenterTests {
    private static final Pattern VERDICT = Pattern.compile("^Trailing fragment merge decision: verdict=([a-z-]+),");

    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(OcrRegionSegmenter.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    /** The verdict of every fragment merge decision logged so far, in order. */
    private List<String> verdicts() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .map(VERDICT::matcher).filter(Matcher::find).map(match -> match.group(1)).toList();
    }

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
    void mergesAChineseSentenceEndingBackWithoutInsertingASpace() {
        OcrRegion paragraph = new OcrRegion("article-main",
                "今天的天氣非常晴朗而且空氣清新所以我們決定一起到附近的公園散步順便買些水果回",
                rectangle(20, 20, 540, 80),
                List.of(
                        word("今天的天氣非常晴朗而且空氣清新", 20, 20, 330, 26),
                        word("所以我們決定一起到附近的公園散步順便買些水果回", 20, 70, 400, 26)),
                .98f, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage("zh", .98f)), 0);
        OcrRegion ending = new OcrRegion("article-ending", "家了。",
                rectangle(20, 125, 72, 26),
                List.of(word("家了。", 20, 125, 72, 26)),
                .97f, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage("zh", .97f)), 1);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(paragraph, ending));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).text()).endsWith("買些水果回家了。").doesNotContain(" ");
    }

    @Test
    void mergesAKoreanSentenceEndingBackWithASpaceBetweenTheWords() {
        OcrRegion paragraph = new OcrRegion("article-main",
                "오늘 날씨가 정말 맑고 하늘도 높아서 산책하기에 아주",
                rectangle(20, 20, 540, 80),
                List.of(
                        word("오늘 날씨가 정말 맑고 하늘도", 20, 20, 330, 26),
                        word("높아서 산책하기에 아주", 20, 70, 250, 26)),
                .98f, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage("ko", .98f)), 0);
        OcrRegion ending = new OcrRegion("article-ending", "좋다.",
                rectangle(20, 125, 60, 26),
                List.of(word("좋다.", 20, 125, 60, 26)),
                .97f, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage("ko", .97f)), 1);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(paragraph, ending));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).text())
                .isEqualTo("오늘 날씨가 정말 맑고 하늘도 높아서 산책하기에 아주 좋다.");
        assertThat(result.get(0).words()).hasSize(3);
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

    // --- an orphan line that wrapped out of its paragraph --------------------------------------
    //
    // Vision returned a Japanese article as three paragraph blocks and a fourth block holding only
    // the characters that wrapped onto a line of their own. The fourth block was dropped before
    // translation, so the image showed three Chinese paragraphs and, under them, the orphan still
    // in Japanese. These tests use the geometry of that page.

    @Test
    void qualificationAloneDropsTheOrphanLineThatWrappedOutOfAParagraph() {
        OcrRegionQualificationPolicy policy = new OcrRegionQualificationPolicy();
        OcrRegion orphan = tail("orphan", "る。", nextLineTop(780, 6), 3);

        // Two characters with no detected language are an untrusted isolated glyph; this is where
        // the fourth region left the translation request, so the segmenter has to get there first.
        assertThat(policy.decide(orphan, .6f).qualification()).isEqualTo(OcrQualification.REJECT);
        assertThat(policy.decide(orphan, .6f).reason()).isEqualTo("untrusted-isolated-glyph");
    }

    @Test
    void mergesAnOrphanLineBackIntoItsParagraphSoItIsTranslatedAndErasedWithIt() {
        OcrRegionQualificationPolicy policy = new OcrRegionQualificationPolicy();
        OcrRegion first = paragraph("p1", 100, 4, 0);
        OcrRegion second = paragraph("p2", 440, 4, 1);
        OcrRegion third = paragraph("p3", 780, 6, 2);
        OcrRegion orphan = tail("orphan", "る。", nextLineTop(780, 6), 3);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(first, second, third, orphan));

        assertThat(result).extracting(OcrRegion::id).containsExactly("p1", "p2", "p3");
        assertThat(result).extracting(OcrRegion::readingOrder).containsExactly(0, 1, 2);
        assertThat(result.get(0)).isEqualTo(first);
        assertThat(result.get(1)).isEqualTo(second);
        // Every region that reaches qualification is now translated, including the last line.
        assertThat(result).allSatisfy(region ->
                assertThat(policy.decide(region, .6f).qualification()).isEqualTo(OcrQualification.TRANSLATE));

        OcrRegion merged = result.get(2);
        assertThat(merged.text()).isEqualTo(third.text() + "る。");
        assertThat(merged.compactLabel()).isFalse();
        assertThat(merged.languages()).isEqualTo(third.languages());
        // Words and visual lines are rebuilt: the orphan is a seventh line of the same paragraph.
        assertThat(merged.words()).hasSize(third.words().size() + 1);
        assertThat(merged.words().get(merged.words().size() - 1).text()).isEqualTo("る。");
        OcrRegionSegmenter.LocalFrame frame = OcrRegionSegmenter.LocalFrame.from(merged);
        assertThat(OcrRegionSegmenter.visualLines(merged.words(), frame)).hasSize(7);
        // The polygon spans the paragraph and the orphan, and keeps the paragraph's left and top.
        java.awt.Rectangle bounds = OverlaySafetyPolicy.polygon(merged.polygon()).getBounds();
        assertThat(bounds.x).isEqualTo(LEFT);
        assertThat(bounds.y).isEqualTo(780);
        assertThat(bounds.width).isEqualTo(LINE_WIDTH);
        assertThat(bounds.y + bounds.height).isEqualTo(nextLineTop(780, 6) + GLYPH);
        // The cleanup mask covers the orphan's glyphs, so the translation replaces them.
        assertThat(OverlaySafetyPolicy.sourceAreas(merged)).anyMatch(area ->
                area.contains(LEFT + GLYPH, nextLineTop(780, 6) + GLYPH / 2));
    }

    @Test
    void mergesTheOrphanWhenVisionListsItBeforeItsParagraph() {
        OcrRegion first = paragraph("p1", 100, 4, 0);
        OcrRegion third = paragraph("p3", 780, 6, 1);
        OcrRegion orphan = tail("orphan", "る。", nextLineTop(780, 6), 2);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(first, orphan, third));

        assertThat(result).extracting(OcrRegion::id).containsExactly("p1", "p3");
        assertThat(result.get(1).text()).isEqualTo(third.text() + "る。");
    }

    @Test
    void mergesTheOrphanWhenVisionCallsTheKanjiHeavyParagraphChinese() {
        OcrRegion third = paragraph("p3", 780, 6, "zh", 0);
        OcrRegion orphan = withLanguage(tail("orphan", "る。", nextLineTop(780, 6), 1), "ja");

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(third, orphan));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).text()).endsWith("る。");
    }

    @Test
    void mergesTheOrphanWhoseVerticesStartAtAnotherCorner() {
        OcrRegion third = paragraph("p3", 780, 6, 0);
        int top = nextLineTop(780, 6);
        // The same box, listed from the top-right corner downwards: by its first edge the block
        // reads as vertical text, but its words are laid out as one horizontal line.
        List<OcrPoint> polygon = List.of(new OcrPoint(LEFT + 2 * GLYPH, top),
                new OcrPoint(LEFT + 2 * GLYPH, top + GLYPH), new OcrPoint(LEFT, top + GLYPH),
                new OcrPoint(LEFT, top));
        OcrRegion orphan = new OcrRegion("orphan", "る。", polygon,
                List.of(new OcrWord("る。", polygon, .95f, true)), .95f, true, OcrBlockType.TEXT,
                List.of(), 1);
        assertThat(orphan.orientation()).isEqualTo(OcrOrientation.VERTICAL);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(third, orphan));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).text()).endsWith("る。");
    }

    @Test
    void mergesAnOrphanAFullLinePitchBelowTheLastLine() {
        OcrRegion third = paragraph("p3", 780, 6, 0);
        // The last line ends at 1212; the orphan starts one pitch (76px) below that.
        OcrRegion orphan = tail("orphan", "る。", 780 + 5 * PITCH + GLYPH + PITCH, 1);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(third, orphan));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).text()).endsWith("る。");
        assertThat(verdicts()).containsExactly("merged");
    }

    @Test
    void mergesAnOrphanBelowAParagraphThatIsSlightlyTilted() {
        OcrRegion third = tilted(paragraph("p3", 780, 6, 0), 3, 600, 780);
        OcrRegion orphan = tilted(tail("orphan", "る。", nextLineTop(780, 6), 1), 3, 600, 780);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(third, orphan));

        assertThat(result).hasSize(1);
        OcrRegion merged = result.get(0);
        assertThat(merged.text()).isEqualTo(third.text() + "る。");
        // The merged box keeps the paragraph's own axes instead of becoming an upright rectangle.
        assertThat(merged.rotationDegrees()).isCloseTo(third.rotationDegrees(), within(.2));
        assertThat(OverlaySafetyPolicy.sourceAreas(merged)).anyMatch(area ->
                area.contains(orphan.words().get(0).polygon().get(0).x() + 20,
                        orphan.words().get(0).polygon().get(0).y() + 20));
        assertThat(verdicts()).containsExactly("merged");
    }

    @Test
    void mergesALoneFullStopThatWrappedOntoALineOfItsOwn() {
        OcrRegion third = paragraph("p3", 780, 6, 0);
        int top = nextLineTop(780, 6);
        // A full stop sits low and to the left of its cell, so its box is small.
        OcrRegion stop = new OcrRegion("stop", "。", rectangle(LEFT, top + 32, 20, 20),
                List.of(new OcrWord("。", rectangle(LEFT, top + 32, 20, 20), .95f, true)),
                .95f, true, OcrBlockType.TEXT, List.of(), 1);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(third, stop));

        assertThat(result).hasSize(1);
        // Punctuation closes the text it follows, with no space in front of it.
        assertThat(result.get(0).text()).isEqualTo(third.text() + "。");
    }

    @Test
    void erasesAnOrphanThatCameWithoutWordBoxes() {
        OcrRegion third = paragraph("p3", 780, 6, 0);
        int top = nextLineTop(780, 6);
        OcrRegion orphan = new OcrRegion("orphan", "る。", rectangle(LEFT, top, 2 * GLYPH, GLYPH),
                List.of(), .95f, true, OcrBlockType.TEXT, List.of(), 1);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(third, orphan));

        assertThat(result).hasSize(1);
        // Without a box of its own the orphan would be missing from the cleanup mask.
        assertThat(OverlaySafetyPolicy.sourceAreas(result.get(0))).anyMatch(area ->
                area.contains(LEFT + GLYPH, top + GLYPH / 2));
    }

    @Test
    void aLowConfidenceTailDoesNotPullALongParagraphUnderTheThreshold() {
        OcrRegionQualificationPolicy policy = new OcrRegionQualificationPolicy();
        OcrRegion third = paragraph("p3", 780, 6, 0);
        int top = nextLineTop(780, 6);
        OcrRegion orphan = new OcrRegion("orphan", "る。", rectangle(LEFT, top, 2 * GLYPH, GLYPH),
                List.of(new OcrWord("る。", rectangle(LEFT, top, 2 * GLYPH, GLYPH), .55f, true)),
                .55f, true, OcrBlockType.TEXT, List.of(), 1);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(third, orphan));

        assertThat(result).hasSize(1);
        // Weighted by length: two characters cannot veto 132, and the paragraph stays translatable.
        assertThat(result.get(0).confidence()).isGreaterThan(.95f);
        assertThat(policy.decide(result.get(0), .6f).qualification()).isEqualTo(OcrQualification.TRANSLATE);
    }

    @Test
    void doesNotMergeATailWhoseConfidenceIsUnknownSoTheParagraphStaysTrusted() {
        OcrRegionQualificationPolicy policy = new OcrRegionQualificationPolicy();
        OcrRegion third = paragraph("p3", 780, 6, 0);
        int top = nextLineTop(780, 6);
        OcrRegion orphan = new OcrRegion("orphan", "る。", rectangle(LEFT, top, 2 * GLYPH, GLYPH),
                List.of(new OcrWord("る。", rectangle(LEFT, top, 2 * GLYPH, GLYPH), 0f, false)),
                0f, false, OcrBlockType.TEXT, List.of(), 1);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(third, orphan));

        // Merged, the unknown confidence would make the whole paragraph untrusted.
        assertThat(result).extracting(OcrRegion::id).containsExactly("p3", "orphan");
        assertThat(policy.decide(result.get(0), .6f).qualification()).isEqualTo(OcrQualification.TRANSLATE);
        assertThat(verdicts()).containsExactly("kept-confidence-unknown");
    }

    @Test
    void doesNotMergeATailWhoseSymbolConfidenceIsUnknownSoTheParagraphStaysTrusted() {
        OcrRegionQualificationPolicy policy = new OcrRegionQualificationPolicy();
        OcrRegion third = withSymbols(paragraph("p3", 780, 6, 0), .98f, true);
        int top = nextLineTop(780, 6);
        // Region and word confidence are known; only the symbols under the word are not. Vision
        // records them independently, and qualification rejects a region with any unknown symbol.
        OcrRegion orphan = withSymbols(tail("orphan", "る。", top, 1), 0f, false);
        assertThat(orphan.confidenceKnown()).isTrue();
        assertThat(orphan.words()).allMatch(OcrWord::confidenceKnown);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(third, orphan));

        assertThat(result).extracting(OcrRegion::id).containsExactly("p3", "orphan");
        assertThat(policy.decide(result.get(0), .6f).qualification()).isEqualTo(OcrQualification.TRANSLATE);
        assertThat(verdicts()).containsExactly("kept-confidence-unknown");
    }

    @Test
    void mergesATailWhoseSymbolsAreKnownAndTheParagraphStaysTranslatable() {
        OcrRegionQualificationPolicy policy = new OcrRegionQualificationPolicy();
        OcrRegion third = withSymbols(paragraph("p3", 780, 6, 0), .98f, true);
        OcrRegion orphan = withSymbols(tail("orphan", "る。", nextLineTop(780, 6), 1), .95f, true);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(third, orphan));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).words().get(result.get(0).words().size() - 1).symbols()).hasSize(2);
        assertThat(policy.decide(result.get(0), .6f).qualification()).isEqualTo(OcrQualification.TRANSLATE);
        assertThat(verdicts()).containsExactly("merged");
    }

    @Test
    void doesNotMergeATailWithSymbolsIntoAParagraphThatHasNone() {
        // Qualification averages symbols over the whole region. With none of its own, the host
        // would be judged by the tail's two symbols alone.
        OcrRegion third = paragraph("p3", 780, 6, 0);
        OcrRegion orphan = withSymbols(tail("orphan", "る。", nextLineTop(780, 6), 1), .95f, true);

        assertThat(new OcrRegionSegmenter().segment(List.of(third, orphan)))
                .extracting(OcrRegion::id).containsExactly("p3", "orphan");
        assertThat(verdicts()).containsExactly("kept-symbols-unmatched");
    }

    // A tail that scores low drags the merged paragraph's scores down. Qualification is what
    // decides, with the threshold the pipeline applies (0.60 by default), so the merge is only
    // kept when the merged paragraph would still be translated.

    @Test
    void doesNotMergeALowConfidenceTailThatWouldPullTheParagraphUnderTheThreshold() {
        OcrRegionQualificationPolicy policy = new OcrRegionQualificationPolicy();
        // 20 characters at 0.61 and a 4-character tail at 0.01 average 0.51, below 0.60.
        OcrRegion host = scored(paragraph("p", 100, 2, 10, "ja", 0), .61f, .98f);
        OcrRegion tail = scored(tail("tail", "ている。", nextLineTop(100, 2), 1), .01f, .01f);
        assertThat(host.text().codePointCount(0, host.text().length())).isEqualTo(20);
        assertThat(policy.decide(host, .6f).qualification()).isEqualTo(OcrQualification.TRANSLATE);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(host, tail));

        assertThat(result).extracting(OcrRegion::id).containsExactly("p", "tail");
        assertThat(result.get(0).text()).isEqualTo(host.text());
        assertThat(policy.decide(result.get(0), .6f).qualification()).isEqualTo(OcrQualification.TRANSLATE);
        assertThat(verdicts()).containsExactly("kept-merged-region-unsafe");
    }

    @Test
    void doesNotMergeATailWhoseWordConfidenceAloneWouldFailTheWordCheck() {
        OcrRegionQualificationPolicy policy = new OcrRegionQualificationPolicy();
        // The paragraph's score is fine, but its ten words average 0.50 and the tail's one word at
        // 0.01 takes the average under 0.48 (0.8 x the threshold).
        OcrRegion host = scored(paragraph("p", 100, 2, 10, "ja", 0), .97f, .50f);
        OcrRegion tail = scored(tail("tail", "ている。", nextLineTop(100, 2), 1), .97f, .01f);
        assertThat(policy.decide(host, .6f).qualification()).isEqualTo(OcrQualification.TRANSLATE);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(host, tail));

        assertThat(result).extracting(OcrRegion::id).containsExactly("p", "tail");
        assertThat(policy.decide(result.get(0), .6f).qualification()).isEqualTo(OcrQualification.TRANSLATE);
        assertThat(verdicts()).containsExactly("kept-merged-region-unsafe");
    }

    @Test
    void doesNotMergeATailWhoseSymbolConfidenceAloneWouldFailTheSymbolCheck() {
        OcrRegionQualificationPolicy policy = new OcrRegionQualificationPolicy();
        // Twenty symbols at 0.50 pass (0.75 x 0.60 = 0.45); four more at 0.01 average 0.42.
        OcrRegion host = withSymbols(paragraph("p", 100, 2, 10, "ja", 0), .50f, true);
        OcrRegion tail = withSymbols(tail("tail", "ている。", nextLineTop(100, 2), 1), .01f, true);
        assertThat(policy.decide(host, .6f).qualification()).isEqualTo(OcrQualification.TRANSLATE);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(host, tail));

        assertThat(result).extracting(OcrRegion::id).containsExactly("p", "tail");
        assertThat(policy.decide(result.get(0), .6f).qualification()).isEqualTo(OcrQualification.TRANSLATE);
        assertThat(verdicts()).containsExactly("kept-merged-region-unsafe");
    }

    @Test
    void mergesATailThatTheStricterThresholdOfTheDeploymentStillAccepts() {
        // The same tail is judged against the configured threshold, not a built-in one.
        OcrRegionQualificationPolicy policy = new OcrRegionQualificationPolicy();
        OcrRegion host = scored(paragraph("p", 100, 2, 10, "ja", 0), .90f, .98f);
        OcrRegion tail = scored(tail("tail", "ている。", nextLineTop(100, 2), 1), .80f, .98f);

        assertThat(new OcrRegionSegmenter(policy, threshold(.85f)).segment(List.of(host, tail)))
                .extracting(OcrRegion::id).containsExactly("p");
        logs.list.clear();
        assertThat(new OcrRegionSegmenter(policy, threshold(.89f)).segment(List.of(host, tail)))
                .extracting(OcrRegion::id).containsExactly("p", "tail");
        assertThat(verdicts()).containsExactly("kept-merged-region-unsafe");
    }

    // A block between the paragraph and the fragment is in the way: the fragment is then the line
    // after that block, not the one after the paragraph, however small the gaps are.

    @Test
    void aShortHeadingBetweenTheParagraphAndTheFragmentStopsTheMerge() {
        OcrRegion third = paragraph("p3", 780, 6, 0);
        int headingTop = nextLineTop(780, 6);
        OcrRegion heading = new OcrRegion("heading", "まとめ", rectangle(LEFT, headingTop, 3 * GLYPH, GLYPH),
                List.of(new OcrWord("まとめ", rectangle(LEFT, headingTop, 3 * GLYPH, GLYPH), .97f, true)),
                .97f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("ja", .97f)), 1);
        // One pitch below the heading: 100px under the paragraph's last line, inside the 1.5 line
        // heights (114px) that would otherwise be allowed.
        OcrRegion orphan = tail("orphan", "る。", headingTop + PITCH, 2);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(third, heading, orphan));

        assertThat(result).extracting(OcrRegion::id).containsExactly("p3", "heading", "orphan");
        assertThat(result.get(0).text()).isEqualTo(third.text());
        assertThat(verdicts()).doesNotContain("merged");
    }

    @Test
    void aCenteredHeadingBetweenTheParagraphAndTheFragmentIsReportedAsABlockInTheWay() {
        OcrRegion third = paragraph("p3", 780, 6, 0);
        int headingTop = nextLineTop(780, 6);
        // Centered under the paragraph, clear of the fragment's own column, so it is not a
        // candidate host and only the paragraph is judged.
        OcrRegion heading = shifted(new OcrRegion("heading", "まとめ",
                rectangle(LEFT, headingTop, 3 * GLYPH, GLYPH),
                List.of(new OcrWord("まとめ", rectangle(LEFT, headingTop, 3 * GLYPH, GLYPH), .97f, true)),
                .97f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("ja", .97f)), 1), 500, 0);
        OcrRegion orphan = tail("orphan", "る。", headingTop + PITCH, 2);

        assertThat(new OcrRegionSegmenter().segment(List.of(third, heading, orphan)))
                .extracting(OcrRegion::id).containsExactly("p3", "heading", "orphan");
        assertThat(verdicts()).containsExactly("kept-block-between");
    }

    @Test
    void aBlockBesideTheColumnDoesNotStopTheMerge() {
        OcrRegion third = paragraph("p3", 780, 6, 0);
        int top = nextLineTop(780, 6);
        // A caption level with the gap but wholly outside the paragraph's column.
        OcrRegion beside = shifted(tail("beside", "図1", top - 40, 1), LINE_WIDTH + 80, 0);
        OcrRegion orphan = tail("orphan", "る。", top, 2);

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(third, beside, orphan));

        assertThat(result).extracting(OcrRegion::id).containsExactly("p3", "beside");
        assertThat(result.get(0).text()).isEqualTo(third.text() + "る。");
    }

    // The negative cases: a short block next to a paragraph is not always the paragraph's tail.

    @Test
    void doesNotMergeAHeadingSetInLargerType() {
        OcrRegion third = paragraph("p3", 780, 6, 0);
        int top = nextLineTop(780, 6);
        OcrRegion heading = new OcrRegion("heading", "まとめ！", rectangle(LEFT, top, 4 * 80, 80),
                List.of(new OcrWord("まとめ！", rectangle(LEFT, top, 4 * 80, 80), .97f, true)),
                .97f, true, OcrBlockType.TEXT, List.of(), 1);

        assertThat(new OcrRegionSegmenter().segment(List.of(third, heading)))
                .extracting(OcrRegion::id).containsExactly("p3", "heading");
        assertThat(verdicts()).containsExactly("kept-glyph-height-differs");
    }

    @Test
    void doesNotMergeABulletThatOpensItsOwnItem() {
        OcrRegion third = paragraph("p3", 780, 6, 0);
        OcrRegion bullet = tail("bullet", "・以上。", nextLineTop(780, 6), 1);

        assertThat(new OcrRegionSegmenter().segment(List.of(third, bullet)))
                .extracting(OcrRegion::id).containsExactly("p3", "bullet");
        assertThat(verdicts()).isEmpty();
    }

    @Test
    void doesNotMergeATailSetInSmallerType() {
        OcrRegion third = paragraph("p3", 780, 6, 0);
        int top = nextLineTop(780, 6);
        OcrRegion note = new OcrRegion("note", "注。", rectangle(LEFT, top, 2 * 36, 36),
                List.of(new OcrWord("注。", rectangle(LEFT, top, 2 * 36, 36), .97f, true)),
                .97f, true, OcrBlockType.TEXT, List.of(), 1);

        assertThat(new OcrRegionSegmenter().segment(List.of(third, note)))
                .extracting(OcrRegion::id).containsExactly("p3", "note");
        assertThat(verdicts()).containsExactly("kept-glyph-height-differs");
    }

    @Test
    void doesNotMergeABlockFromAnotherColumn() {
        OcrRegion third = paragraph("p3", 780, 6, 0);
        int top = nextLineTop(780, 6);
        // Under the paragraph but indented far past its left edge.
        OcrRegion indented = tail("indented", "る。", top, 1);
        indented = shifted(indented, 600, 0);
        // Beside the paragraph, wholly outside its column.
        OcrRegion beside = shifted(tail("beside", "る。", top, 2), LINE_WIDTH + 80, 0);

        assertThat(new OcrRegionSegmenter().segment(List.of(third, indented)))
                .extracting(OcrRegion::id).containsExactly("p3", "indented");
        assertThat(verdicts()).containsExactly("kept-left-edge-misaligned");
        assertThat(new OcrRegionSegmenter().segment(List.of(third, beside)))
                .extracting(OcrRegion::id).containsExactly("p3", "beside");
        // The second block was never a candidate, so it added no decision of its own.
        assertThat(verdicts()).containsExactly("kept-left-edge-misaligned");
    }

    @Test
    void doesNotMergeAfterAParagraphThatEndedBeforeTheRightEdge() {
        // The last line stops two thirds across: the paragraph is finished, not wrapped.
        OcrRegion full = paragraph("p3", 780, 6, 0);
        List<OcrWord> words = new java.util.ArrayList<>(full.words());
        int shortLine = 0;
        for (int index = words.size() - 1; index >= 0 && shortLine < 4; index--, shortLine++) {
            words.remove(index);
        }
        OcrRegion ended = new OcrRegion("p3", full.text(), full.polygon(), words, full.confidence(),
                true, OcrBlockType.TEXT, full.languages(), 0);
        OcrRegion next = tail("next", "はい。", nextLineTop(780, 6), 1);

        assertThat(new OcrRegionSegmenter().segment(List.of(ended, next)))
                .extracting(OcrRegion::id).containsExactly("p3", "next");
        assertThat(verdicts()).containsExactly("kept-last-line-short");
    }

    @Test
    void doesNotMergeABlockSeveralLinesBelowTheParagraph() {
        OcrRegion third = paragraph("p3", 780, 6, 0);
        OcrRegion later = tail("later", "る。", nextLineTop(780, 6) + 2 * PITCH, 1);

        assertThat(new OcrRegionSegmenter().segment(List.of(third, later)))
                .extracting(OcrRegion::id).containsExactly("p3", "later");
        assertThat(verdicts()).containsExactly("kept-gap-out-of-range");
    }

    @Test
    void doesNotMergeATailInAnotherLanguage() {
        OcrRegion third = paragraph("p3", 780, 6, 0);
        OcrRegion korean = withLanguage(tail("korean", "좋다.", nextLineTop(780, 6), 1), "ko");

        assertThat(new OcrRegionSegmenter().segment(List.of(third, korean)))
                .extracting(OcrRegion::id).containsExactly("p3", "korean");
        assertThat(verdicts()).containsExactly("kept-language-conflict");
    }

    @Test
    void doesNotMergeIntoAParagraphThatIsNotHorizontal() {
        // The same paragraph turned a quarter turn, with the orphan under its new bounding box.
        OcrRegion upright = paragraph("p3", 780, 6, 0);
        OcrRegion turned = tilted(upright, 90, 600, 780);
        java.awt.Rectangle box = OverlaySafetyPolicy.polygon(turned.polygon()).getBounds();
        assertThat(turned.orientation()).isEqualTo(OcrOrientation.VERTICAL);
        OcrRegion orphan = tail("orphan", "る。", box.y + box.height + 24, 1);
        orphan = shifted(orphan, box.x - LEFT, 0);

        assertThat(new OcrRegionSegmenter().segment(List.of(turned, orphan)))
                .extracting(OcrRegion::id).containsExactly("p3", "orphan");
        assertThat(verdicts()).containsExactly("kept-host-not-horizontal");
    }

    @Test
    void doesNotMergeAFragmentIntoAShortParagraph() {
        OcrRegion caption = paragraph("caption", 100, 1, 0);
        List<OcrWord> half = caption.words().subList(0, 4);
        OcrRegion shortCaption = new OcrRegion("caption", "自分に対する抗議集", rectangle(LEFT, 100, 4 * 104, GLYPH),
                half, .97f, true, OcrBlockType.TEXT, caption.languages(), 0);
        OcrRegion orphan = tail("orphan", "る。", 100 + PITCH, 1);

        assertThat(new OcrRegionSegmenter().segment(List.of(shortCaption, orphan)))
                .extracting(OcrRegion::id).containsExactly("caption", "orphan");
        assertThat(verdicts()).containsExactly("kept-host-too-short");
    }

    @Test
    void logsTheMergeDecisionWithGeometryOnly() {
        OcrRegion third = paragraph("p3", 780, 6, 0);
        OcrRegion orphan = tail("orphan", "る。", nextLineTop(780, 6), 1);

        new OcrRegionSegmenter().segment(List.of(third, orphan));

        List<String> lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("Trailing fragment merge decision")).toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).startsWith("Trailing fragment merge decision: verdict=merged, "
                + "hostWords=66, hostLines=6, fragmentWords=1, fragmentChars=2, gapRatio=0.32, "
                + "leftOffsetRatio=0.00, heightRatio=1.00, shortfallRatio=0.00, lineHeight=76, "
                + "hostWidth=1144, hostHeight=432");
        // Counts and ratios only: none of the text the regions carry may reach the log.
        assertThat(lines.get(0)).doesNotContain("る").doesNotContain("。")
                .doesNotContain(third.text().substring(0, 4));
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
        // Two columns of vertical CJK, two dish names each. Vision lists the left column first,
        // but vertical CJK is read from the rightmost column to the leftmost, then top to bottom
        // within a column, so the segmenter must emit the right column first.
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
                .containsExactly("香酥飯", "經典飯", "澎湃飯", "風味飯");
        // Ids and reading order follow the emitted order: column 1 is the rightmost one.
        assertThat(result).extracting(OcrRegion::id)
                .containsExactly("card.v1.1", "card.v1.2", "card.v2.1", "card.v2.2");
        assertThat(result).extracting(OcrRegion::readingOrder).containsExactly(0, 1, 2, 3);
        // Each item keeps its own column, so the two columns stay horizontally separated and the
        // first two items sit to the right of the last two.
        assertThat(result.get(0).polygon().get(0).x()).isGreaterThan(result.get(2).polygon().get(0).x());
    }

    @Test
    void keepsLatinTopToBottomColumnsLeftToRight() {
        // Latin stacked in columns is not vertical Japanese or Chinese text, so the page order
        // stays left to right.
        OcrRegion card = verticalCard("latin", "en", "TEA", "BAR");

        List<OcrRegion> result = new OcrRegionSegmenter().segment(List.of(card));

        assertThat(result).extracting(OcrRegionSegmenterTests::compactText)
                .containsExactly("TEA", "BAR");
        assertThat(result).extracting(OcrRegion::id).containsExactly("latin.v1.1", "latin.v2.1");
    }

    @Test
    void decidesTheColumnDirectionFromAllWordsOfTheParagraph() {
        // The direction is chosen once per paragraph from the majority of its letters, never per
        // column, so a stray Latin column travels with the CJK columns around it.
        OcrRegion mostlyCjk = verticalCard("cjk", "zh", "香酥飯", "TEA", "經典飯", "澎湃飯");
        OcrRegion mostlyLatin = verticalCard("lat", "en", "香酥飯", "TEA", "BAR", "BOX");

        assertThat(new OcrRegionSegmenter().segment(List.of(mostlyCjk)))
                .extracting(OcrRegionSegmenterTests::compactText)
                .containsExactly("澎湃飯", "經典飯", "TEA", "香酥飯");
        assertThat(new OcrRegionSegmenter().segment(List.of(mostlyLatin)))
                .extracting(OcrRegionSegmenterTests::compactText)
                .containsExactly("香酥飯", "TEA", "BAR", "BOX");
    }

    @Test
    void anEvenSplitBetweenCjkAndLatinKeepsThePageOrder() {
        OcrRegion tie = verticalCard("tie", "zh", "香酥飯", "TEA");

        assertThat(new OcrRegionSegmenter().segment(List.of(tie)))
                .extracting(OcrRegionSegmenterTests::compactText)
                .containsExactly("香酥飯", "TEA");
    }

    @Test
    void prolongedSoundMarksDoNotTipAKatakanaColumnIntoLeftToRight() {
        // The mark is a letter of the common script: half of these glyphs are marks, but they say
        // nothing about the script, so the three kana still make the paragraph CJK.
        OcrRegion katakana = verticalCard("kana", "ja", "コーヒー", "ビー");

        assertThat(new OcrRegionSegmenter().segment(List.of(katakana)))
                .extracting(OcrRegionSegmenterTests::compactText)
                .containsExactly("ビー", "コーヒー");
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

    /**
     * One glyph per word and one column per argument, 50px apart from the left. Words are listed
     * column by column, so the leftmost column comes first, as Vision lists it.
     */
    private static OcrRegion verticalCard(String id, String language, String... columns) {
        List<OcrWord> words = new java.util.ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int column = 0; column < columns.length; column++) {
            int row = 0;
            for (int codePoint : columns[column].codePoints().toArray()) {
                words.add(word(new String(Character.toChars(codePoint)),
                        10 + column * 50, 10 + row++ * 24, 22, 22));
            }
            text.append(text.isEmpty() ? "" : " ").append(columns[column]);
        }
        return new OcrRegion(id, text.toString(), rectangle(5, 5, columns.length * 50 + 5, 100), words,
                .98f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage(language, .98f)), 0);
    }

    /** The region text without the spaces the segmenter puts between non-CJK glyphs. */
    private static String compactText(OcrRegion region) {
        return region.text().replace(" ", "");
    }

    private static OcrRegion withLanguage(OcrRegion region, String code) {
        return new OcrRegion(region.id(), region.text(), region.polygon(), region.words(),
                region.confidence(), region.confidenceKnown(), region.blockType(),
                List.of(new OcrDetectedLanguage(code, .9f)), region.readingOrder(), region.groupId(),
                region.compactLabel());
    }

    /** The pipeline's properties with only the OCR confidence threshold changed. */
    private static ImageTranslationProperties threshold(float lowConfidenceThreshold) {
        return new ImageTranslationProperties(
                10_485_760, 4_096, 16_000_000, lowConfidenceThreshold, true, .12, .35);
    }

    /** The same region with the confidence of the region and of each of its words replaced. */
    private static OcrRegion scored(OcrRegion region, float regionConfidence, float wordConfidence) {
        List<OcrWord> words = region.words().stream().map(word -> new OcrWord(word.text(),
                word.polygon(), wordConfidence, true, word.symbols())).toList();
        return new OcrRegion(region.id(), region.text(), region.polygon(), words, regionConfidence,
                true, region.blockType(), region.languages(), region.readingOrder(), region.groupId(),
                region.compactLabel());
    }

    /** Every word given one symbol per character, with the confidence Vision would report for it. */
    private static OcrRegion withSymbols(OcrRegion region, float confidence, boolean known) {
        List<OcrWord> words = region.words().stream().map(word -> new OcrWord(word.text(),
                word.polygon(), word.confidence(), word.confidenceKnown(),
                word.text().codePoints().mapToObj(codePoint -> new OcrSymbol(
                        new String(Character.toChars(codePoint)), word.polygon(), confidence, known))
                        .toList())).toList();
        return new OcrRegion(region.id(), region.text(), region.polygon(), words, region.confidence(),
                region.confidenceKnown(), region.blockType(), region.languages(), region.readingOrder(),
                region.groupId(), region.compactLabel());
    }

    private static OcrRegion shifted(OcrRegion region, int dx, int dy) {
        return mapped(region, point -> new OcrPoint(point.x() + dx, point.y() + dy));
    }

    /** The region turned clockwise about a pivot, polygon and word boxes alike. */
    private static OcrRegion tilted(OcrRegion region, double degrees, int pivotX, int pivotY) {
        double cos = Math.cos(Math.toRadians(degrees));
        double sin = Math.sin(Math.toRadians(degrees));
        return mapped(region, point -> {
            double dx = point.x() - pivotX;
            double dy = point.y() - pivotY;
            return new OcrPoint((int) Math.round(pivotX + dx * cos - dy * sin),
                    (int) Math.round(pivotY + dx * sin + dy * cos));
        });
    }

    private static OcrRegion mapped(OcrRegion region, UnaryOperator<OcrPoint> move) {
        List<OcrWord> words = region.words().stream().map(word -> new OcrWord(word.text(),
                word.polygon().stream().map(move).toList(), word.confidence(),
                word.confidenceKnown())).toList();
        return new OcrRegion(region.id(), region.text(), region.polygon().stream().map(move).toList(),
                words, region.confidence(), region.confidenceKnown(), region.blockType(),
                region.languages(), region.readingOrder(), region.groupId(), region.compactLabel());
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
