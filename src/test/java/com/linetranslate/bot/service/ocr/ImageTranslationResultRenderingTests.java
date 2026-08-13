package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiExecutionResult;
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
        when(repository.findByUserId("U-result")).thenReturn(Optional.of(profile));
        when(repository.save(any(UserProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ImageTranslationContext context = new ImageTranslationContext(
                new DownloadedImage(new byte[] {1}, "image/png"),
                ImageStorageResult.notStored(),
                "hello",
                null);
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
        ImageTranslationService service = new ImageTranslationService(pipeline, repository);

        ImageTranslationReply reply = service.processImageTranslationReply("U-result", "message");
        TranslationResponse response = reply.response();

        assertThat(reply.renderedImageUrl())
                .contains("https://s3.example/result.png?signature=safe");
        assertThat(response.displayText())
                .contains("https://s3.example/result.png?signature=safe")
                .contains("1 小時內有效")
                .contains("低信心區塊已保留原文")
                .contains("翻譯結果：\n你好");
    }

    @Test
    void insecureOrMalformedArtifactUrlIsNotReturned() {
        ImageTranslationPipeline pipeline = mock(ImageTranslationPipeline.class);
        UserProfileRepository repository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder().userId("U-result").build();
        when(repository.findByUserId("U-result")).thenReturn(Optional.of(profile));
        when(repository.save(any(UserProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ImageTranslationContext context = new ImageTranslationContext(
                new DownloadedImage(new byte[] {1}, "image/png"),
                ImageStorageResult.notStored(),
                "hello",
                null);
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
        ImageTranslationService service = new ImageTranslationService(pipeline, repository);

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
        when(repository.findByUserId("U-safe")).thenReturn(Optional.of(profile));
        when(repository.save(any(UserProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ImageTranslationContext context = new ImageTranslationContext(
                new DownloadedImage(new byte[] {1}, "image/png"), ImageStorageResult.notStored(), "hello", null);
        TranslationWorkflowResult translation = new TranslationWorkflowResult(
                "hello", "en", "zh-TW", new AiExecutionResult("你好", "openrouter", "model"),
                20, TranslationRequestKind.IMAGE_OCR);
        when(pipeline.execute(any())).thenReturn(new ImageTranslationOutcome.Success(
                new ImageTranslationPipelineResult(context, translation, ImageStorageResult.notStored(), 1,
                        ImageOverlayDisposition.SAFETY_DEGRADED)));

        TranslationResponse response = new ImageTranslationService(pipeline, repository)
                .processImageTranslationResponse("U-safe", "message");

        assertThat(response.displayText())
                .contains("為避免錯誤覆寫")
                .contains("只提供文字翻譯")
                .contains("翻譯結果：\n你好");
        assertThat(response.translatedText()).isEqualTo("你好");
    }

    @Test
    void coverageDegradationIsDescribedAccuratelyWithoutCallingItLowConfidence() {
        ImageTranslationPipeline pipeline = mock(ImageTranslationPipeline.class);
        UserProfileRepository repository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder().userId("U-coverage").build();
        when(repository.findByUserId("U-coverage")).thenReturn(Optional.of(profile));
        when(repository.save(any(UserProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ImageTranslationContext context = new ImageTranslationContext(
                new DownloadedImage(new byte[] {1}, "image/png"), ImageStorageResult.notStored(), "text", null);
        TranslationWorkflowResult translation = new TranslationWorkflowResult(
                "text", "ja", "zh-TW", new AiExecutionResult("文字", "openrouter", "model"),
                20, TranslationRequestKind.IMAGE_OCR);
        when(pipeline.execute(any())).thenReturn(new ImageTranslationOutcome.Success(
                new ImageTranslationPipelineResult(context, translation, ImageStorageResult.notStored(), 0,
                        ImageOverlayDisposition.SAFETY_DEGRADED,
                        OverlayDegradationSummary.single(OverlayDegradationReason.COVERAGE, 3))));

        String display = new ImageTranslationService(pipeline, repository)
                .processImageTranslationResponse("U-coverage", "message").displayText();

        assertThat(display).contains("3 個文字區域因覆蓋範圍過大而保留原文")
                .doesNotContain("低信心區塊");
    }

    @Test
    void mappingDegradationTellsTheUserWhyTheImageWasNotOverwritten() {
        String display = degradedDisplay("U-mapping",
                OverlayDegradationSummary.single(OverlayDegradationReason.MAPPING, 2));

        assertThat(display).contains("2 個文字區域未取得可靠的對應譯文");
    }

    @Test
    void disabledAndUnexpectedFailuresAreAlsoExplained() {
        assertThat(degradedDisplay("U-disabled",
                OverlayDegradationSummary.single(OverlayDegradationReason.DISABLED, 4)))
                .contains("圖片覆寫功能已停用");
        assertThat(degradedDisplay("U-other",
                OverlayDegradationSummary.single(OverlayDegradationReason.OTHER, 1)))
                .contains("未預期的處理錯誤");
        assertThat(degradedDisplay("U-storage",
                OverlayDegradationSummary.single(OverlayDegradationReason.STORAGE, 1)))
                .contains("上傳儲存失敗");
    }

    @Test
    void degradationWarningIsNeverSentWithoutAnyReason() {
        String display = degradedDisplay("U-bare", OverlayDegradationSummary.none());

        assertThat(display).contains("為避免錯誤覆寫");
        assertThat(display.lines().filter(line -> line.startsWith("⚠️")).count())
                .isGreaterThanOrEqualTo(2L);
    }

    private static String degradedDisplay(String userId, OverlayDegradationSummary degradation) {
        ImageTranslationPipeline pipeline = mock(ImageTranslationPipeline.class);
        UserProfileRepository repository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder().userId(userId).build();
        when(repository.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(repository.save(any(UserProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ImageTranslationContext context = new ImageTranslationContext(
                new DownloadedImage(new byte[] {1}, "image/png"), ImageStorageResult.notStored(), "text", null);
        TranslationWorkflowResult translation = new TranslationWorkflowResult(
                "text", "ja", "zh-TW", new AiExecutionResult("文字", "openrouter", "model"),
                20, TranslationRequestKind.IMAGE_OCR);
        when(pipeline.execute(any())).thenReturn(new ImageTranslationOutcome.Success(
                new ImageTranslationPipelineResult(context, translation, ImageStorageResult.notStored(), 0,
                        ImageOverlayDisposition.SAFETY_DEGRADED, degradation)));

        return new ImageTranslationService(pipeline, repository)
                .processImageTranslationResponse(userId, "message").displayText();
    }
}
