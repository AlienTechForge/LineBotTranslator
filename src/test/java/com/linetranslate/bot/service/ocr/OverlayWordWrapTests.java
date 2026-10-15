package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OverlayWordWrapTests {

    private Graphics2D graphics;
    private FontMetrics metrics;

    @BeforeEach
    void createMetrics() {
        graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        metrics = graphics.getFontMetrics();
    }

    @AfterEach
    void disposeGraphics() {
        graphics.dispose();
    }

    @Test
    void latinWordMovesToTheNextLineWholeInsteadOfSplitting() {
        int width = metrics.stringWidth("Chicken so");

        assertThat(legacyWrap("Chicken soup", metrics, width))
                .as("the per-glyph wrap this replaces")
                .containsExactly("Chicken so", "up");
        assertThat(wrap("Chicken soup", width)).containsExactly("Chicken", "soup");
    }

    @Test
    void latinSentenceBreaksOnlyAtSpacesAndFillsEachLineGreedily() {
        String sentence = "Shampoo, haircut and scalp isolation with Nanyang Green Curry Chicken Rice.";
        List<String> words = Arrays.asList(sentence.split(" "));
        int widest = words.stream().mapToInt(metrics::stringWidth).max().orElseThrow();

        for (int width = widest; width <= metrics.stringWidth(sentence) + 1; width++) {
            int limit = width;
            List<String> lines = wrap(sentence, limit);

            assertThat(lines).as("width=%s", limit).allSatisfy(line -> {
                assertThat(line).isEqualTo(line.strip());
                assertThat(metrics.stringWidth(line)).isLessThanOrEqualTo(limit);
            });
            assertThat(lines.stream().flatMap(line -> Arrays.stream(line.split(" "))).toList())
                    .as("every word stays whole at width=%s: %s", limit, lines)
                    .isEqualTo(words);
            for (int index = 0; index + 1 < lines.size(); index++) {
                String nextWord = lines.get(index + 1).split(" ")[0];
                assertThat(metrics.stringWidth(lines.get(index) + " " + nextWord))
                        .as("line %s could have taken '%s' at width=%s", index, nextWord, limit)
                        .isGreaterThan(limit);
            }
        }
    }

    @Test
    void wordWiderThanTheLineStillBreaksByCharacters() {
        String word = "Supercalifragilistic";
        int width = metrics.stringWidth("Superca");
        List<String> perGlyph = legacyWrap(word, metrics, width);
        assertThat(perGlyph).hasSizeGreaterThan(1);

        assertThat(wrap(word, width)).isEqualTo(perGlyph);

        List<String> lines = wrap("Hi " + word + " ok", width);
        assertThat(lines.get(0)).isEqualTo("Hi");
        assertThat(lines.subList(1, perGlyph.size()))
                .as("the long word starts its own line and breaks exactly as before")
                .isEqualTo(perGlyph.subList(0, perGlyph.size() - 1));
        assertThat(String.join(" ", lines.subList(perGlyph.size(), lines.size())))
                .isEqualTo(perGlyph.get(perGlyph.size() - 1) + " ok");
    }

    @Test
    void cjkTextWrapsPerGlyphExactlyAsBefore() {
        List<String> samples = List.of(
                "這是一段需要換行的翻譯文字",
                "密集文字會自動縮排與換行",
                "洗髮剪髮頭皮隔離染髮日本三劑式結構護髮",
                "他說：「你好……」——然後離開了。",
                "コーヒー・ラテ（特製）をどうぞ。",
                "第一段\n第二段文字\r\n\n第三段");

        for (String sample : samples) {
            for (int width = 1; width <= metrics.stringWidth(sample) + 2; width++) {
                assertThat(wrap(sample, width))
                        .as("%s at width=%s", sample, width)
                        .isEqualTo(legacyWrap(sample, metrics, width));
            }
        }
    }

    @Test
    void koreanWordMovesToTheNextLineWholeInsteadOfSplittingBetweenSyllables() {
        String text = "안녕하세요 반갑습니다";
        int width = metrics.stringWidth("안녕하세요 반갑");

        assertThat(legacyWrap(text, metrics, width))
                .as("the per-glyph wrap this replaces")
                .containsExactly("안녕하세요 반갑", "습니다");
        assertThat(wrap(text, width)).containsExactly("안녕하세요", "반갑습니다");
    }

    @Test
    void koreanSentenceBreaksOnlyAtSpacesAndFillsEachLineGreedily() {
        String sentence = "샴푸 커트 두피 격리 염색 일본 삼제식 구조 케어 남양 그린 카레 치킨 라이스";
        List<String> words = Arrays.asList(sentence.split(" "));
        int widest = words.stream().mapToInt(metrics::stringWidth).max().orElseThrow();

        for (int width = widest; width <= metrics.stringWidth(sentence) + 1; width++) {
            int limit = width;
            List<String> lines = wrap(sentence, limit);

            assertThat(lines).as("width=%s", limit).allSatisfy(line -> {
                assertThat(line).isEqualTo(line.strip());
                assertThat(metrics.stringWidth(line)).isLessThanOrEqualTo(limit);
            });
            assertThat(lines.stream().flatMap(line -> Arrays.stream(line.split(" "))).toList())
                    .as("every word stays whole at width=%s: %s", limit, lines)
                    .isEqualTo(words);
            for (int index = 0; index + 1 < lines.size(); index++) {
                String nextWord = lines.get(index + 1).split(" ")[0];
                assertThat(metrics.stringWidth(lines.get(index) + " " + nextWord))
                        .as("line %s could have taken '%s' at width=%s", index, nextWord, limit)
                        .isGreaterThan(limit);
            }
        }
    }

    @Test
    void koreanWordKeepsItsAttachedPunctuationAndHyphensAreNotBreakPoints() {
        String punctuated = "안녕하세요, 반갑습니다!";
        int width = metrics.stringWidth("안녕하세요, 반갑");
        assertThat(legacyWrap(punctuated, metrics, width))
                .containsExactly("안녕하세요, 반갑", "습니다!");
        assertThat(wrap(punctuated, width)).containsExactly("안녕하세요,", "반갑습니다!");

        String hyphenated = "한국어-영어 사전";
        width = metrics.stringWidth("한국어-영어 사");
        assertThat(legacyWrap(hyphenated, metrics, width)).containsExactly("한국어-영어 사", "전");
        assertThat(wrap(hyphenated, width)).containsExactly("한국어-영어", "사전");
    }

    @Test
    void koreanWordWiderThanTheLineStillBreaksBySyllablesAsALastResort() {
        for (String word : List.of("한국어포장지설명서", "한국어-영어포장지설명서")) {
            for (int width = 1; width <= metrics.stringWidth(word) + 2; width++) {
                assertThat(wrap(word, width))
                        .as("%s at width=%s", word, width)
                        .isEqualTo(legacyWrap(word, metrics, width));
            }
        }
        int width = metrics.stringWidth("한국어포");
        List<String> lines = wrap("가 한국어포장지설명서", width);
        assertThat(lines.get(0)).isEqualTo("가");
        assertThat(String.join("", lines.subList(1, lines.size())))
                .isEqualTo("한국어포장지설명서");
    }

    @Test
    void hanAndKanaNextToKoreanStillBreakBetweenGlyphs() {
        for (String text : List.of("한국어漢字", "한국어のテ")) {
            int width = metrics.stringWidth(text.substring(0, text.length() - 1));

            assertThat(wrap(text, width))
                    .as("%s at width=%s", text, width)
                    .containsExactly(text.substring(0, text.length() - 1),
                            text.substring(text.length() - 1))
                    .isEqualTo(legacyWrap(text, metrics, width));
        }
    }

    @Test
    void mixedTextKeepsLatinWordsWholeAndStillBreaksBetweenCjkGlyphs() {
        String text = "特價商品限時優惠 Chicken soup 100元";
        String cjkRun = "特價商品限時優惠";
        int narrow = metrics.stringWidth("Chicken");
        assertThat(metrics.stringWidth(cjkRun)).as("the CJK run must need more than one line")
                .isGreaterThan(narrow);

        List<String> lines = wrap(text, narrow);
        assertThat(lines.stream().filter(line -> !line.isEmpty()
                        && line.codePoints().allMatch(code -> cjkRun.indexOf(code) >= 0)).count())
                .as("the CJK run splits between glyphs: %s", lines)
                .isGreaterThan(1);

        for (int width = narrow; width <= metrics.stringWidth(text) + 1; width++) {
            List<String> wrapped = wrap(text, width);
            assertThat(String.join("", wrapped).replace(" ", ""))
                    .as("width=%s", width).isEqualTo(text.replace(" ", ""));
            for (String word : List.of("Chicken", "soup", "100")) {
                assertThat(wrapped).as("'%s' stays whole at width=%s: %s", word, width, wrapped)
                        .anySatisfy(line -> assertThat(line).contains(word));
            }
        }
    }

    @Test
    void paragraphsWrapIndependently() {
        int widestWord = Stream.of("Chicken", "soup", "Beef", "noodles")
                .mapToInt(metrics::stringWidth).max().orElseThrow();
        int widestParagraph = Math.max(
                metrics.stringWidth("Chicken soup"), metrics.stringWidth("Beef noodles"));

        assertThat(wrap("Chicken soup\nBeef noodles", widestWord))
                .containsExactly("Chicken", "soup", "Beef", "noodles");
        assertThat(wrap("Chicken soup\r\n\r\nBeef noodles", widestParagraph))
                .containsExactly("Chicken soup", "Beef noodles");
        assertThat(wrap("Chicken soup\n", widestWord)).containsExactly("Chicken", "soup", "");
        assertThat(wrap("", widestWord)).containsExactly("");
    }

    @Test
    void saysWhetherAWordHadToBeBrokenBetweenItsCharacters() {
        int menu = metrics.stringWidth("Menu");
        boolean[] whole = new boolean[1];
        boolean[] broken = new boolean[1];

        List<String> unbroken = ImageTranslationOverlayRenderer.wrap("Open Menu", metrics, menu, whole);
        List<String> split = ImageTranslationOverlayRenderer.wrap("Open Menu", metrics, menu / 2, broken);

        assertThat(unbroken).containsExactly("Open", "Menu");
        assertThat(whole[0]).as("a word that fits a line of its own is never broken").isFalse();
        assertThat(split.size()).isGreaterThan(2);
        assertThat(broken[0]).as("a word wider than the line is broken between its letters").isTrue();
    }

    @Test
    void aGlyphWiderThanTheLineAndHanAndKanaBreaksAreNotBrokenWords() {
        boolean[] flag = new boolean[1];

        ImageTranslationOverlayRenderer.wrap("糖醋排骨飯", metrics, metrics.stringWidth("糖醋"), flag);
        assertThat(flag[0]).as("Han glyphs are breakable anywhere, each one its own token").isFalse();

        ImageTranslationOverlayRenderer.wrap("W", metrics, 1, flag);
        assertThat(flag[0]).as("one glyph cannot be broken").isFalse();

        ImageTranslationOverlayRenderer.wrap("Menu", metrics, 1, flag);
        assertThat(flag[0]).isTrue();
    }

    private List<String> wrap(String text, int width) {
        return ImageTranslationOverlayRenderer.wrap(text, metrics, width);
    }

    /** The per-code-point wrap that preceded word-aware wrapping, kept verbatim as a reference. */
    private static List<String> legacyWrap(String text, FontMetrics metrics, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.replace('\r', '\n').split("\\n+", -1)) {
            StringBuilder line = new StringBuilder();
            int[] codePoints = paragraph.codePoints().toArray();
            for (int codePoint : codePoints) {
                String glyph = new String(Character.toChars(codePoint));
                if (!line.isEmpty() && metrics.stringWidth(line + glyph) > width) {
                    lines.add(line.toString().stripTrailing());
                    line.setLength(0);
                }
                line.append(glyph);
            }
            if (!line.isEmpty() || paragraph.isEmpty()) {
                lines.add(line.toString().strip());
            }
        }
        return lines.isEmpty() ? List.of("") : lines;
    }
}
