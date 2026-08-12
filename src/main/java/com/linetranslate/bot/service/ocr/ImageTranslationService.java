package com.linetranslate.bot.service.ocr;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiExecutionFailure;
import com.linetranslate.bot.service.settings.RuntimeSettings;
import com.linetranslate.bot.service.settings.RuntimeSettingsSource;
import com.linetranslate.bot.service.translation.TranslationWorkflowResult;
import com.linetranslate.bot.service.translation.TranslationResponse;
import com.linetranslate.bot.util.LanguageUtils;

import lombok.extern.slf4j.Slf4j;

/** User-profile entrypoint and LINE text renderer for the image pipeline. */
@Service
@Slf4j
public class ImageTranslationService {

    private final ImageTranslationPipeline imageTranslationPipeline;
    private final UserProfileRepository userProfileRepository;
    private final RuntimeSettingsSource runtimeSettingsSource;

    @Autowired
    public ImageTranslationService(
            ImageTranslationPipeline imageTranslationPipeline,
            UserProfileRepository userProfileRepository,
            RuntimeSettingsSource runtimeSettingsSource) {
        this.imageTranslationPipeline = imageTranslationPipeline;
        this.userProfileRepository = userProfileRepository;
        this.runtimeSettingsSource = runtimeSettingsSource;
    }

    /** Compatibility constructor for focused unit tests. */
    public ImageTranslationService(
            ImageTranslationPipeline imageTranslationPipeline,
            UserProfileRepository userProfileRepository) {
        this(imageTranslationPipeline, userProfileRepository, () -> null);
    }

    public boolean isOcrEnabled() {
        RuntimeSettings settings = runtimeSettingsSource.current();
        return settings == null || settings.ocrEnabled();
    }

    public String processImageTranslation(String userId, String messageId) {
        return processImageTranslationResponse(userId, messageId).displayText();
    }

    public TranslationResponse processImageTranslationResponse(String userId, String messageId) {
        if (!isOcrEnabled()) {
            return TranslationResponse.plain("OCR 功能目前已停用。請稍後再試。");
        }

        Instant start = Instant.now();
        log.info("處理圖片翻譯請求: user={}, message={}",
                SafeLog.user(userId), SafeLog.content(messageId));

        try {
            UserProfile userProfile = ensureUserProfileExists(userId);
            ImageTranslationOutcome outcome = imageTranslationPipeline.execute(
                    new ImageTranslationRequest(userProfile, messageId, start));
            if (outcome instanceof ImageTranslationOutcome.Success success) {
                return render(success.result());
            }
            return TranslationResponse.plain(
                    renderFailure(userId, (ImageTranslationOutcome.Failure) outcome));
        } catch (RuntimeException failure) {
            log.error("圖片翻譯失敗: user={}, failure={}",
                    SafeLog.user(userId), SafeLog.failure(failure));
            return TranslationResponse.plain("圖片翻譯處理失敗，請稍後再試。");
        }
    }

    private TranslationResponse render(ImageTranslationPipelineResult result) {
        String recognizedText = result.context().recognizedText();
        TranslationWorkflowResult translation = result.translation();
        StringBuilder display = new StringBuilder();
        Optional<String> renderedImageUrl = result.renderedImage().url()
                .filter(ImageTranslationService::isSafeRenderedImageUrl);
        renderedImageUrl.ifPresent(url -> display.append("【翻譯圖片（連結 1 小時內有效）】\n")
                        .append(url)
                        .append("\n\n"));
        if (result.overlayDisposition() == ImageOverlayDisposition.SAFETY_DEGRADED) {
            display.append("⚠️ 為避免錯誤覆寫，本次只提供文字翻譯；原圖未被修改。\n\n");
        }
        display.append("【圖片文字辨識結果】\n\n")
                .append("識別的文字：\n").append(recognizedText).append("\n\n")
                .append("翻譯結果：\n").append(translation.translatedText()).append("\n\n");
        if (result.lowConfidenceBlockCount() > 0) {
            display.append("⚠️ ").append(result.lowConfidenceBlockCount())
                    .append(renderedImageUrl.isPresent()
                            ? " 個低信心區塊已保留原文並以橘框標示。\n\n"
                            : " 個低信心區塊未覆寫，已保留原文。\n\n");
        }
        appendDegradation(display, result.degradation(), OverlayDegradationReason.COVERAGE,
                " 個文字區域因覆蓋範圍過大而保留原文。");
        appendDegradation(display, result.degradation(), OverlayDegradationReason.GEOMETRY,
                " 個文字區域因位置資訊不安全而保留原文。");
        appendDegradation(display, result.degradation(), OverlayDegradationReason.OVERLAP,
                " 個文字區域因位置重疊而保留原文。");
        appendDegradation(display, result.degradation(), OverlayDegradationReason.FONT,
                " 個文字區域因找不到完整字形而保留原文。");
        appendDegradation(display, result.degradation(), OverlayDegradationReason.TEXT_FIT,
                " 個文字區域因譯文無法以可讀大小完整放入而保留原文。");
        display.append("[偵測到: ").append(LanguageUtils.toChineseName(translation.sourceLanguage()))
                .append(" | 翻譯成: ").append(LanguageUtils.toChineseName(translation.targetLanguage()))
                .append("]");
        return TranslationResponse.success(translation, display.toString());
    }

    private static void appendDegradation(StringBuilder display, OverlayDegradationSummary summary,
            OverlayDegradationReason reason, String message) {
        int count = summary.count(reason);
        if (count > 0) display.append("⚠️ ").append(count).append(message).append("\n\n");
    }

    private static boolean isSafeRenderedImageUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String renderFailure(String userId, ImageTranslationOutcome.Failure failure) {
        failure.executionFailure().ifPresent(execution -> logWorkflowFailure(userId, execution));
        return switch (failure.stage()) {
            case NO_TEXT -> "未能識別到圖片中的文字。請確保圖片中包含清晰可見的文字。";
            case TRANSLATION -> "圖片翻譯服務暫時無法使用，請稍後再試。";
            case DOWNLOAD, RECOGNITION, CANCELLED -> "圖片處理失敗，請稍後再試。";
        };
    }

    private UserProfile ensureUserProfileExists(String userId) {
        Optional<UserProfile> userProfileOptional = userProfileRepository.findByUserId(userId);

        if (userProfileOptional.isPresent()) {
            UserProfile userProfile = userProfileOptional.get();
            userProfile.setLastInteractionAt(LocalDateTime.now());
            return userProfileRepository.save(userProfile);
        }

        UserProfile newUserProfile = UserProfile.builder()
                .userId(userId)
                .firstInteractionAt(LocalDateTime.now())
                .lastInteractionAt(LocalDateTime.now())
                .build();
        return userProfileRepository.save(newUserProfile);
    }

    private void logWorkflowFailure(String userId, AiExecutionFailure failure) {
        log.warn(
                "AI 圖片翻譯未完成: user={}, provider={}, model={}, outcome={}, reason={}, correlation={}",
                SafeLog.user(userId),
                failure.provider(),
                failure.model(),
                failure.outcome(),
                failure.reason(),
                failure.correlationId());
    }
}
