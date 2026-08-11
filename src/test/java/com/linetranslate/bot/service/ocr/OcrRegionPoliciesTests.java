package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class OcrRegionPoliciesTests {
    private final OcrRegionQualificationPolicy qualification = new OcrRegionQualificationPolicy();

    @Test
    void rejectsDecorationAndUnknownConfidenceButPreservesDateAndPercent() {
        assertThat(qualification.decide(region("~", false, List.of()), .6f).qualification())
                .isEqualTo(OcrQualification.REJECT);
        assertThat(qualification.decide(region("2021.05.28", true, List.of()), .6f).qualification())
                .isEqualTo(OcrQualification.PRESERVE);
        assertThat(qualification.decide(region("2.52%", true, List.of()), .6f).qualification())
                .isEqualTo(OcrQualification.PRESERVE);
    }

    @Test
    void acceptsHangulVietnameseAndProtectsTokensInNaturalText() {
        assertThat(qualification.decide(region("불닭볶음면", true, List.of()), .6f).qualification())
                .isEqualTo(OcrQualification.TRANSLATE);
        OcrRegionDecision vietnamese = qualification.decide(
                region("Nhiệt độ 38°C và 2.52%", true, List.of()), .6f);
        assertThat(vietnamese.qualification()).isEqualTo(OcrQualification.TRANSLATE);
        assertThat(vietnamese.protectedTokens()).containsExactly("38°C", "2.52%");
        assertThat(qualification.preservesTokens(vietnamese, "溫度 38°C 和 2.52%" )).isTrue();
        assertThat(qualification.preservesTokens(vietnamese, "溫度 39°C 和 2.52%" )).isFalse();
    }

    @Test
    void resolvesDominantLanguageWithoutLettingEnglishBrandWin() {
        OcrSourceLanguageResolver resolver = new OcrSourceLanguageResolver();
        OcrRegion korean = region("불닭볶음면 치즈의 풍미", true,
                List.of(new OcrDetectedLanguage("ko", .98f)));
        OcrRegion english = region("Cheese", true, List.of(new OcrDetectedLanguage("en", .99f)));
        assertThat(resolver.resolve(List.of(korean, english))).isEqualTo("ko");
        assertThat(resolver.resolve(List.of(region("BẢO VỆ BẢN THÂN KHỎI", true,
                List.of(new OcrDetectedLanguage("vi", .97f)))))).isEqualTo("vi");
    }

    @Test
    void rejectsTrustedLanguageScriptConflictAndOversizedText() {
        assertThat(qualification.decide(region("辣", true,
                List.of(new OcrDetectedLanguage("ko", .98f))), .6f).qualification())
                .isEqualTo(OcrQualification.REJECT);
        assertThat(qualification.decide(region("辣", true, List.of()), .6f).qualification())
                .isEqualTo(OcrQualification.REJECT);
        assertThat(qualification.decide(region("辣", true,
                List.of(new OcrDetectedLanguage("zh", .98f))), .6f).qualification())
                .isEqualTo(OcrQualification.TRANSLATE);
        assertThat(qualification.decide(region("a".repeat(4_001), true,
                List.of(new OcrDetectedLanguage("en", .98f))), .6f).qualification())
                .isEqualTo(OcrQualification.REJECT);
    }

    @Test
    void derivesHorizontalVerticalAndRotatedOrientationFromProviderVertexOrder() {
        OcrRegion horizontal = region("hello", true, List.of(new OcrDetectedLanguage("en", .9f)));
        OcrRegion vertical = new OcrRegion("vertical", "文字", List.of(
                new OcrPoint(20, 10), new OcrPoint(20, 80),
                new OcrPoint(5, 80), new OcrPoint(5, 10)), List.of(),
                .9f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("zh", .9f)), 0);
        OcrRegion rotated = new OcrRegion("rotated", "Cheese", List.of(
                new OcrPoint(10, 10), new OcrPoint(80, 45),
                new OcrPoint(65, 75), new OcrPoint(-5, 40)), List.of(),
                .9f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("en", .9f)), 0);
        assertThat(horizontal.orientation()).isEqualTo(OcrOrientation.HORIZONTAL);
        assertThat(vertical.orientation()).isEqualTo(OcrOrientation.VERTICAL);
        assertThat(rotated.orientation()).isEqualTo(OcrOrientation.ROTATED);
    }

    private static OcrRegion region(String text, boolean confidenceKnown, List<OcrDetectedLanguage> languages) {
        return new OcrRegion("r-1-" + Math.abs(text.hashCode()), text,
                List.of(new OcrPoint(10, 10), new OcrPoint(110, 10),
                        new OcrPoint(110, 40), new OcrPoint(10, 40)),
                List.of(), confidenceKnown ? .95f : 0, confidenceKnown,
                OcrBlockType.TEXT, languages, 0);
    }
}
