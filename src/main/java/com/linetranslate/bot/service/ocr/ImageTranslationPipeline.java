package com.linetranslate.bot.service.ocr;

import java.io.InputStream;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.linecorp.bot.client.base.BlobContent;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderExecutionModule;
import com.linetranslate.bot.service.storage.ImageStorageResult;
import com.linetranslate.bot.service.storage.MinioStorageService;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.translation.TranslationRequestKind;
import com.linetranslate.bot.service.translation.TranslationWorkflowModule;
import com.linetranslate.bot.service.translation.TranslationWorkflowOutcome;
import com.linetranslate.bot.service.translation.TranslationWorkflowRequest;
import com.linetranslate.bot.service.translation.TranslationWorkflowResult;
import com.linetranslate.bot.util.LanguageUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Image translation pipeline Module. Every stage consumes and returns explicit
 * values; no request state is stored on threads or singleton fields.
 */
@Service
@Slf4j
public class ImageTranslationPipeline {

    private static final String OCR_PROMPT =
            "請識別這張圖片中的所有文字，只返回文字內容，不要添加任何其他描述或解釋。";
    private static final Pattern TRANSLATION_COMMAND_PATTERN_CN =
            Pattern.compile("翻譯成([\\u4e00-\\u9fa5]+)\\s*(.*)");
    private static final Pattern TRANSLATION_COMMAND_PATTERN_CODE =
            Pattern.compile("翻譯成([a-zA-Z\\-]+)\\s*(.*)");

    private final OcrService ocrService;
    private final TranslationWorkflowModule translationWorkflowModule;
    private final AiProviderExecutionModule aiProviderExecutionModule;
    private final UserPreferencesModule userPreferencesModule;
    private final MessagingApiBlobClient messagingApiBlobClient;
    private final MinioStorageService minioStorageService;

    public ImageTranslationPipeline(
            ObjectProvider<OcrService> ocrServiceProvider,
            TranslationWorkflowModule translationWorkflowModule,
            AiProviderExecutionModule aiProviderExecutionModule,
            UserPreferencesModule userPreferencesModule,
            MessagingApiBlobClient messagingApiBlobClient,
            MinioStorageService minioStorageService) {
        this.ocrService = ocrServiceProvider.getIfAvailable();
        this.translationWorkflowModule = translationWorkflowModule;
        this.aiProviderExecutionModule = aiProviderExecutionModule;
        this.userPreferencesModule = userPreferencesModule;
        this.messagingApiBlobClient = messagingApiBlobClient;
        this.minioStorageService = minioStorageService;
    }

    public ImageTranslationOutcome execute(ImageTranslationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Image translation request is required");
        }
        DownloadedImage image;
        try {
            image = download(request.messageId());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.CANCELLED);
        } catch (Exception failure) {
            log.warn("LINE image download failed: message={}, failure={}",
                    SafeLog.content(request.messageId()), SafeLog.failure(failure));
            return new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.DOWNLOAD);
        }

        ImageStorageResult storage = store(image);
        String recognizedText;
        try {
            recognizedText = recognize(request, image);
        } catch (RuntimeException failure) {
            log.warn("Image recognition failed: user={}, failure={}",
                    SafeLog.user(request.userProfile().getUserId()), SafeLog.failure(failure));
            return new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.RECOGNITION);
        }
        if (recognizedText == null || recognizedText.isBlank()) {
            return new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.NO_TEXT);
        }

        ImageTranslationContext context = new ImageTranslationContext(
                image,
                storage,
                recognizedText,
                requestedTargetLanguage(recognizedText));
        TranslationWorkflowOutcome workflowOutcome;
        try {
            workflowOutcome = translationWorkflowModule.execute(new TranslationWorkflowRequest(
                    request.userProfile(),
                    context.recognizedText(),
                    context.requestedTargetLanguage(),
                    TranslationRequestKind.IMAGE_OCR,
                    context.storage().url().orElse(null),
                    context.storage().stored(),
                    request.startedAt()));
        } catch (RuntimeException failure) {
            log.warn("Image translation workflow failed: user={}, failure={}",
                    SafeLog.user(request.userProfile().getUserId()), SafeLog.failure(failure));
            return new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.TRANSLATION);
        }
        if (workflowOutcome instanceof TranslationWorkflowOutcome.Failure failure) {
            return new ImageTranslationOutcome.Failure(
                    ImageTranslationFailureStage.TRANSLATION,
                    failure.failure());
        }

        TranslationWorkflowResult translation =
                ((TranslationWorkflowOutcome.Success) workflowOutcome).result();
        return new ImageTranslationOutcome.Success(
                new ImageTranslationPipelineResult(context, translation));
    }

    private DownloadedImage download(String messageId) throws Exception {
        Result<BlobContent> response = messagingApiBlobClient.getMessageContent(messageId).get();
        BlobContent content = response == null ? null : response.body();
        if (content == null) {
            throw new IllegalStateException("LINE image response body is empty");
        }
        try (InputStream stream = content.byteStream()) {
            if (stream == null) {
                throw new IllegalStateException("LINE image response stream is empty");
            }
            return new DownloadedImage(stream.readAllBytes(), content.mimeType());
        }
    }

    private ImageStorageResult store(DownloadedImage image) {
        try {
            ImageStorageResult result = minioStorageService.uploadImage(
                    image.bytes(), image.contentType());
            return result == null ? ImageStorageResult.notStored() : result;
        } catch (RuntimeException failure) {
            log.warn("Image storage degraded: failure={}", SafeLog.failure(failure));
            return ImageStorageResult.notStored();
        }
    }

    private String recognize(ImageTranslationRequest request, DownloadedImage image) {
        if (ocrService != null) {
            try (InputStream stream = new java.io.ByteArrayInputStream(image.bytes())) {
                return ocrService.recognizeText(stream);
            } catch (OcrProcessingException failure) {
                log.info("Primary OCR unavailable; using AI image recognition: failure={}",
                        SafeLog.failure(failure));
            } catch (java.io.IOException impossibleForByteArray) {
                throw new OcrProcessingException("Unable to close OCR image stream", impossibleForByteArray);
            }
        }

        String dataUrl = "data:" + image.contentType() + ";base64,"
                + Base64.getEncoder().encodeToString(image.bytes());
        AiExecutionResult result = aiProviderExecutionModule.processImage(
                userPreferencesModule.resolve(request.userProfile()), OCR_PROMPT, dataUrl);
        if (result == null) {
            throw new OcrProcessingException("AI image recognition returned no result");
        }
        return result.text();
    }

    private String requestedTargetLanguage(String recognizedText) {
        Matcher chineseName = TRANSLATION_COMMAND_PATTERN_CN.matcher(recognizedText);
        if (chineseName.find()) {
            return LanguageUtils.toLanguageCode(chineseName.group(1));
        }
        Matcher languageCode = TRANSLATION_COMMAND_PATTERN_CODE.matcher(recognizedText);
        return languageCode.find()
                ? languageCode.group(1).toLowerCase(java.util.Locale.ROOT)
                : null;
    }
}
