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

        TranslationResponse response = service.processImageTranslationResponse("U-result", "message");

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

        TranslationResponse response = service.processImageTranslationResponse("U-result", "message");

        assertThat(response.displayText()).doesNotContain("user:secret", "internal/result.png");
        assertThat(response.displayText()).contains("翻譯結果：\n你好");
    }
}
