package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.storage.ImageStorageResult;
import com.linetranslate.bot.service.translation.TranslationResponse;
import com.linetranslate.bot.util.LineTextLimit;

class ImageTranslationResultRenderingTests {

    private static final String SIGNED = "https://s3.example/result.png?signature=safe";

    @Test
    void aPictureReplyCarriesTheSignedUrlTheTextsAndNoWarningWhenClean() {
        Fixture fixture = new Fixture("U-result");

        ImageTranslationReply reply = fixture.reply(ImageStorageResult.stored(SIGNED), false, "hello", "你好");
        TranslationResponse response = reply.response();

        assertThat(reply.renderedImageUrl()).contains(SIGNED);
        assertThat(reply.warning()).isEmpty();
        assertThat(response.displayText())
                .contains(SIGNED)
                .contains("1 小時內有效")
                .contains("識別的文字：\nhello")
                .contains("翻譯結果：\n你好")
                .contains("[偵測到: 英文 | 翻譯成: 繁體中文]");
        assertThat(response.translatedText()).isEqualTo("你好");
        assertThat(response.recordId()).isEqualTo("record-1");
        assertThat(response.actionable()).isTrue();
        // The activity time is one atomic field write; the whole profile is never saved back.
        verify(fixture.repository).setLastInteractionAt(eq("U-result"), any(LocalDateTime.class));
        verify(fixture.repository, never()).save(any(UserProfile.class));
    }

    @Test
    void aWarnedPictureReplyCarriesTheWarningLineExactly() {
        Fixture fixture = new Fixture("U-warned");

        ImageTranslationReply reply = fixture.reply(ImageStorageResult.stored(SIGNED), true, "hello", "你好");

        assertThat(reply.warning()).contains("⚠️ 圖片翻譯可能有遺漏或錯字，數字請以原圖為準");
        assertThat(ImageTranslationService.WARNING_TEXT).isEqualTo("⚠️ 圖片翻譯可能有遺漏或錯字，數字請以原圖為準");
    }

    @Test
    void textOnlyIsThePrefixLineAndTheTranslatedTextAndNeverAWarning() {
        Fixture fixture = new Fixture("U-text");

        ImageTranslationReply reply = fixture.reply(ImageStorageResult.notStored(), true, "こんにちは", "你好");

        assertThat(reply.renderedImageUrl()).isEmpty();
        assertThat(reply.warning()).isEmpty();
        assertThat(reply.response().displayText()).isEqualTo("圖片無法完整翻譯，以下是文字翻譯：\n你好");
        assertThat(reply.response().translatedText()).isEqualTo("你好");
        assertThat(reply.response().actionable()).isTrue();
    }

    @Test
    void aLongTextOnlyTranslationIsCutToTheLineLimitWithTheMarker() {
        Fixture fixture = new Fixture("U-long");
        String translated = "字".repeat(LineTextLimit.MAX_CODE_POINTS + 500);

        String display = fixture.reply(ImageStorageResult.notStored(), false, "x", translated).response().displayText();

        assertThat(LineTextLimit.codePoints(display)).isEqualTo(LineTextLimit.MAX_CODE_POINTS);
        assertThat(display).startsWith("圖片無法完整翻譯，以下是文字翻譯：\n").endsWith(LineTextLimit.TRUNCATION_MARKER);
    }

    @Test
    void aLongPictureReplyKeepsTheTranslationAndCutsTheRecognisedTextFirst() {
        Fixture fixture = new Fixture("U-long-picture");
        String recognised = "a".repeat(LineTextLimit.MAX_CODE_POINTS + 100);

        String display = fixture.reply(ImageStorageResult.stored(SIGNED), false, recognised, "你好").response().displayText();

        assertThat(LineTextLimit.codePoints(display)).isLessThanOrEqualTo(LineTextLimit.MAX_CODE_POINTS);
        assertThat(display).contains("翻譯結果：\n你好").contains(LineTextLimit.TRUNCATION_MARKER);
    }

    @Test
    void aTextOnlyReplyWithoutATranslationIsTheGenericFailure() {
        Fixture fixture = new Fixture("U-empty");

        ImageTranslationReply reply = fixture.reply(ImageStorageResult.notStored(), false, "hello", "");

        assertThat(reply.response().displayText()).isEqualTo("圖片處理失敗，請稍後再試。");
        assertThat(reply.response().actionable()).isFalse();
    }

    @Test
    void storedPreviewRenditionIsCarriedSeparatelyFromTheOriginalUrl() {
        ImageTranslationReply reply = new Fixture("U-preview").reply(ImageStorageResult.stored(
                SIGNED, "https://s3.example/result-preview.jpg?signature=safe"), false, "hello", "你好");

        assertThat(reply.renderedImageUrl()).contains(SIGNED);
        assertThat(reply.renderedPreviewUrl()).contains("https://s3.example/result-preview.jpg?signature=safe");
        assertThat(reply.response().displayText()).doesNotContain("result-preview.jpg");
    }

    @Test
    void insecurePreviewUrlIsDroppedButTheOriginalStillRenders() {
        ImageTranslationReply reply = new Fixture("U-insecure-preview").reply(ImageStorageResult.stored(
                SIGNED, "http://user:secret@internal/result-preview.jpg"), false, "hello", "你好");

        assertThat(reply.renderedImageUrl()).contains(SIGNED);
        assertThat(reply.renderedPreviewUrl()).isEmpty();
    }

    @Test
    void anImageWithoutAStoredPreviewKeepsAnEmptyPreviewUrl() {
        assertThat(new Fixture("U-no-preview").reply(ImageStorageResult.stored(SIGNED), false, "hello", "你好")
                .renderedPreviewUrl()).isEmpty();
    }

    @Test
    void aPreviewWithoutAnOriginalIsNeverReported() {
        assertThat(ImageStorageResult.stored(null, "https://s3.example/result-preview.jpg").previewUrl()).isEmpty();
        assertThat(ImageStorageResult.notStored().previewUrl()).isEmpty();
        assertThat(new ImageTranslationReply(TranslationResponse.plain("x"), Optional.empty(),
                Optional.of("https://s3.example/b")).renderedPreviewUrl()).isEmpty();
    }

    @Test
    void aWarningWithoutAPictureIsNeverReported() {
        assertThat(new ImageTranslationReply(TranslationResponse.plain("x"), Optional.empty(), Optional.empty(),
                Optional.of("careful")).warning()).isEmpty();
        assertThat(new ImageTranslationReply(TranslationResponse.plain("x"), Optional.of(SIGNED), Optional.empty(),
                Optional.of("careful")).warning()).contains("careful");
        assertThat(new ImageTranslationReply(TranslationResponse.plain("x"), Optional.of(SIGNED), Optional.empty(),
                Optional.of(" ")).warning()).isEmpty();
    }

    @Test
    void insecureOrMalformedArtifactUrlIsNotReturnedAndTheReplyFallsBackToText() {
        Fixture fixture = new Fixture("U-insecure");

        ImageTranslationReply reply = fixture.reply(
                ImageStorageResult.stored("http://user:secret@internal/result.png"), true, "hello", "你好");

        assertThat(reply.renderedImageUrl()).isEmpty();
        assertThat(reply.warning()).isEmpty();
        assertThat(reply.response().displayText())
                .doesNotContain("user:secret", "internal/result.png")
                .startsWith("圖片無法完整翻譯，以下是文字翻譯：")
                .contains("你好");
    }

    @ParameterizedTest
    @CsvSource({
            "NO_TEXT, 未能識別到圖片中的文字。請確保圖片中包含清晰可見的文字。",
            "BUDGET, 本月圖片翻譯額度已用完，請下個月再試。",
            "SERVICE_UNAVAILABLE, 圖片翻譯服務暫時無法使用，請稍後再試。",
            "TRANSLATION, 圖片處理失敗，請稍後再試。",
            "DOWNLOAD, 圖片處理失敗，請稍後再試。",
            "CANCELLED, 圖片處理失敗，請稍後再試。"})
    void everyFailureStageHasItsOwnReply(ImageTranslationFailureStage stage, String expected) {
        Fixture fixture = new Fixture("U-failure");
        when(fixture.pipeline.execute(any())).thenReturn(new ImageTranslationOutcome.Failure(stage));

        ImageTranslationReply reply = fixture.service.processImageTranslationReply("U-failure", "message");

        assertThat(reply.response().displayText()).isEqualTo(expected);
        assertThat(reply.renderedImageUrl()).isEmpty();
        assertThat(reply.response().actionable()).isFalse();
    }

    @Test
    void aDefectInThePipelineIsTheGenericReplyNotAnException() {
        Fixture fixture = new Fixture("U-defect");
        when(fixture.pipeline.execute(any())).thenThrow(new IllegalStateException("bug"));

        assertThat(fixture.service.processImageTranslationReply("U-defect", "message").response().displayText())
                .isEqualTo("圖片翻譯處理失敗，請稍後再試。");
    }

    /** A service over a stubbed pipeline for one user. */
    private static final class Fixture {

        final ImageTranslationPipeline pipeline = mock(ImageTranslationPipeline.class);
        final UserProfileRepository repository = mock(UserProfileRepository.class);
        final ImageTranslationService service;
        final String userId;

        Fixture(String userId) {
            this.userId = userId;
            UserPreferencesModule profiles = mock(UserPreferencesModule.class);
            when(profiles.profile(userId)).thenReturn(UserProfile.builder().userId(userId).build());
            service = new ImageTranslationService(pipeline, repository, profiles);
        }

        ImageTranslationReply reply(ImageStorageResult rendered, boolean warned, String recognised, String translated) {
            ImageTranslationContext context = new ImageTranslationContext(
                    new DownloadedImage(new byte[] {1}, "image/png"), ImageStorageResult.notStored());
            when(pipeline.execute(any())).thenReturn(new ImageTranslationOutcome.Success(
                    new ImageTranslationPipelineResult(context, rendered, warned,
                            new ImageTranslationPipelineResult.Text(
                                    recognised, translated, "en", "zh-TW", "record-1", "faithful", "faithful-v1"))));
            return service.processImageTranslationReply(userId, "message");
        }
    }
}
