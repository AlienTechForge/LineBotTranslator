package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.storage.ImageStorageResult;
import com.linetranslate.bot.service.translation.TranslationRequestKind;
import com.linetranslate.bot.service.translation.TranslationResponse;
import com.linetranslate.bot.service.translation.TranslationWorkflowResult;

class ImageTranslationResultRenderingTests {

    @Test
    void responseIncludesSafeTemporaryImageUrlAndTextFallback() {
        ImageTranslationPipeline pipeline = mock(ImageTranslationPipeline.class);
        UserProfileRepository repository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder().userId("U-result").build();
        UserPreferencesModule profiles = mock(UserPreferencesModule.class);
        when(profiles.profile("U-result")).thenReturn(profile);
        ImageTranslationContext context = new ImageTranslationContext(
                new DownloadedImage(new byte[] {1}, "image/png"),
                ImageStorageResult.notStored(),
                "hello");
        TranslationWorkflowResult translation = new TranslationWorkflowResult(
                "hello",
                "en",
                "zh-TW",
                new AiExecutionResult("你好", "openrouter", "model"),
                20,
                TranslationRequestKind.IMAGE_OCR);
        when(pipeline.execute(any())).thenReturn(new ImageTranslationOutcome.Success(
                new ImageTranslationPipelineResult(
                        context,
                        translation,
                        ImageStorageResult.stored("https://s3.example/result.png?signature=safe"),
                        1)));
        ImageTranslationService service = new ImageTranslationService(pipeline, repository, profiles);

        ImageTranslationReply reply = service.processImageTranslationReply("U-result", "message");
        TranslationResponse response = reply.response();

        assertThat(reply.renderedImageUrl())
                .contains("https://s3.example/result.png?signature=safe");
        assertThat(response.displayText())
                .contains("https://s3.example/result.png?signature=safe")
                .contains("1 小時內有效")
                .contains("翻譯結果：\n你好")
                .doesNotContain("低信心區塊");
        // The activity time is one atomic field write; the whole profile is never saved back.
        verify(repository).setLastInteractionAt(eq("U-result"), any(LocalDateTime.class));
        verify(repository, never()).save(any(UserProfile.class));
    }

    @Test
    void storedPreviewRenditionIsCarriedSeparatelyFromTheOriginalUrl() {
        ImageTranslationReply reply = replyFor(ImageStorageResult.stored(
                "https://s3.example/result.png?signature=safe",
                "https://s3.example/result-preview.jpg?signature=safe"));

        assertThat(reply.renderedImageUrl()).contains("https://s3.example/result.png?signature=safe");
        assertThat(reply.renderedPreviewUrl()).contains("https://s3.example/result-preview.jpg?signature=safe");
        assertThat(reply.renderedPreviewUrl()).isNotEqualTo(reply.renderedImageUrl());
        assertThat(reply.response().displayText()).doesNotContain("result-preview.jpg");
    }

    @Test
    void insecurePreviewUrlIsDroppedButTheOriginalStillRenders() {
        ImageTranslationReply reply = replyFor(ImageStorageResult.stored(
                "https://s3.example/result.png?signature=safe",
                "http://user:secret@internal/result-preview.jpg"));

        assertThat(reply.renderedImageUrl()).contains("https://s3.example/result.png?signature=safe");
        assertThat(reply.renderedPreviewUrl()).isEmpty();
    }

    @Test
    void anImageWithoutAStoredPreviewKeepsAnEmptyPreviewUrl() {
        ImageTranslationReply reply = replyFor(ImageStorageResult.stored("https://s3.example/result.png?signature=safe"));

        assertThat(reply.renderedPreviewUrl()).isEmpty();
    }

    @Test
    void aPreviewWithoutAnOriginalIsNeverReported() {
        assertThat(ImageStorageResult.stored(null, "https://s3.example/result-preview.jpg").previewUrl()).isEmpty();
        assertThat(ImageStorageResult.notStored().previewUrl()).isEmpty();
        assertThat(new ImageStorageResult(false, java.util.Optional.of("https://s3.example/a"),
                java.util.Optional.of("https://s3.example/b")).previewUrl()).isEmpty();
        assertThat(new ImageTranslationReply(TranslationResponse.plain("x"), java.util.Optional.empty(),
                java.util.Optional.of("https://s3.example/b")).renderedPreviewUrl()).isEmpty();
    }

    private static ImageTranslationReply replyFor(ImageStorageResult rendered) {
        ImageTranslationPipeline pipeline = mock(ImageTranslationPipeline.class);
        UserProfileRepository repository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder().userId("U-result").build();
        UserPreferencesModule profiles = mock(UserPreferencesModule.class);
        when(profiles.profile("U-result")).thenReturn(profile);
        when(repository.save(any(UserProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ImageTranslationContext context = new ImageTranslationContext(
                new DownloadedImage(new byte[] {1}, "image/png"),
                ImageStorageResult.notStored(),
                "hello");
        TranslationWorkflowResult translation = new TranslationWorkflowResult(
                "hello", "en", "zh-TW",
                new AiExecutionResult("你好", "openrouter", "model"),
                20, TranslationRequestKind.IMAGE_OCR);
        when(pipeline.execute(any())).thenReturn(new ImageTranslationOutcome.Success(
                new ImageTranslationPipelineResult(context, translation, rendered, 0)));
        return new ImageTranslationService(pipeline, repository, profiles)
                .processImageTranslationReply("U-result", "message");
    }

    @Test
    void insecureOrMalformedArtifactUrlIsNotReturned() {
        ImageTranslationPipeline pipeline = mock(ImageTranslationPipeline.class);
        UserProfileRepository repository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder().userId("U-result").build();
        UserPreferencesModule profiles = mock(UserPreferencesModule.class);
        when(profiles.profile("U-result")).thenReturn(profile);
        ImageTranslationContext context = new ImageTranslationContext(
                new DownloadedImage(new byte[] {1}, "image/png"),
                ImageStorageResult.notStored(),
                "hello");
        TranslationWorkflowResult translation = new TranslationWorkflowResult(
                "hello", "en", "zh-TW",
                new AiExecutionResult("你好", "openrouter", "model"),
                20, TranslationRequestKind.IMAGE_OCR);
        when(pipeline.execute(any())).thenReturn(new ImageTranslationOutcome.Success(
                new ImageTranslationPipelineResult(
                        context,
                        translation,
                        ImageStorageResult.stored("http://user:secret@internal/result.png"),
                        0)));
        ImageTranslationService service = new ImageTranslationService(pipeline, repository, profiles);

        ImageTranslationReply reply = service.processImageTranslationReply("U-result", "message");
        TranslationResponse response = reply.response();

        assertThat(reply.renderedImageUrl()).isEmpty();
        assertThat(response.displayText()).doesNotContain("user:secret", "internal/result.png");
        assertThat(response.displayText()).contains("翻譯結果：\n你好");
    }

    @Test
    void safetyDegradationIsReportedAsSuccessfulTextOnlyTranslation() {
        ImageTranslationPipeline pipeline = mock(ImageTranslationPipeline.class);
        UserProfileRepository repository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder().userId("U-safe").build();
        UserPreferencesModule profiles = mock(UserPreferencesModule.class);
        when(profiles.profile("U-safe")).thenReturn(profile);
        ImageTranslationContext context = new ImageTranslationContext(
                new DownloadedImage(new byte[] {1}, "image/png"), ImageStorageResult.notStored(), "hello");
        TranslationWorkflowResult translation = new TranslationWorkflowResult(
                "hello", "en", "zh-TW", new AiExecutionResult("你好", "openrouter", "model"),
                20, TranslationRequestKind.IMAGE_OCR);
        when(pipeline.execute(any())).thenReturn(new ImageTranslationOutcome.Success(
                new ImageTranslationPipelineResult(context, translation, ImageStorageResult.notStored(), 1,
                        ImageOverlayDisposition.SAFETY_DEGRADED)));

        TranslationResponse response = new ImageTranslationService(pipeline, repository, profiles)
                .processImageTranslationResponse("U-safe", "message");

        assertThat(response.displayText())
                .contains("未能覆寫原圖")
                .contains("僅提供文字翻譯")
                .contains("翻譯結果：\n你好");
        assertThat(response.translatedText()).isEqualTo("你好");
    }

    @Test
    void coverageDegradationIsDescribedAccuratelyWithoutCallingItLowConfidence() {
        ImageTranslationPipeline pipeline = mock(ImageTranslationPipeline.class);
        UserProfileRepository repository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder().userId("U-coverage").build();
        UserPreferencesModule profiles = mock(UserPreferencesModule.class);
        when(profiles.profile("U-coverage")).thenReturn(profile);
        ImageTranslationContext context = new ImageTranslationContext(
                new DownloadedImage(new byte[] {1}, "image/png"), ImageStorageResult.notStored(), "text");
        TranslationWorkflowResult translation = new TranslationWorkflowResult(
                "text", "ja", "zh-TW", new AiExecutionResult("文字", "openrouter", "model"),
                20, TranslationRequestKind.IMAGE_OCR);
        when(pipeline.execute(any())).thenReturn(new ImageTranslationOutcome.Success(
                new ImageTranslationPipelineResult(context, translation, ImageStorageResult.notStored(), 0,
                        ImageOverlayDisposition.SAFETY_DEGRADED,
                        OverlayDegradationSummary.single(OverlayDegradationReason.COVERAGE, 3))));

        String display = new ImageTranslationService(pipeline, repository, profiles)
                .processImageTranslationResponse("U-coverage", "message").displayText();

        assertThat(display).doesNotContain("個文字區域", "低信心區塊", "覆蓋範圍");
    }

    @Test
    void overlayDiagnosticsNeverLeakIntoTheUserReply() {
        for (OverlayDegradationReason reason : OverlayDegradationReason.values()) {
            String display = degradedDisplay("U-" + reason, OverlayDegradationSummary.single(reason, 7));

            assertThat(display)
                    .as("reply for %s", reason)
                    .doesNotContain("7", "個文字區域", "低信心區塊");
        }
    }

    @Test
    void degradedReplyStatesTheOutcomeExactlyOnceWithoutDiagnostics() {
        String display = degradedDisplay("U-bare", OverlayDegradationSummary.none());

        assertThat(display).contains("未能覆寫原圖");
        assertThat(display.lines().filter(line -> line.startsWith("⚠️")).count()).isEqualTo(1L);
    }

    private static String degradedDisplay(String userId, OverlayDegradationSummary degradation) {
        ImageTranslationPipeline pipeline = mock(ImageTranslationPipeline.class);
        UserProfileRepository repository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder().userId(userId).build();
        UserPreferencesModule profiles = mock(UserPreferencesModule.class);
        when(profiles.profile(userId)).thenReturn(profile);
        ImageTranslationContext context = new ImageTranslationContext(
                new DownloadedImage(new byte[] {1}, "image/png"), ImageStorageResult.notStored(), "text");
        TranslationWorkflowResult translation = new TranslationWorkflowResult(
                "text", "ja", "zh-TW", new AiExecutionResult("文字", "openrouter", "model"),
                20, TranslationRequestKind.IMAGE_OCR);
        when(pipeline.execute(any())).thenReturn(new ImageTranslationOutcome.Success(
                new ImageTranslationPipelineResult(context, translation, ImageStorageResult.notStored(), 0,
                        ImageOverlayDisposition.SAFETY_DEGRADED, degradation)));

        return new ImageTranslationService(pipeline, repository, profiles)
                .processImageTranslationResponse(userId, "message").displayText();
    }
}
