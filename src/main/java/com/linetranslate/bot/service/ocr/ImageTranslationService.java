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
import com.linetranslate.bot.service.imagetranslator.ImageTranslatorProperties;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.settings.RuntimeSettings;
import com.linetranslate.bot.service.settings.RuntimeSettingsSource;
import com.linetranslate.bot.service.translation.TranslationResponse;
import com.linetranslate.bot.util.LanguageUtils;
import com.linetranslate.bot.util.LineTextLimit;

import lombok.extern.slf4j.Slf4j;

/** User-profile entrypoint and LINE text renderer for the image pipeline. */
@Service
@Slf4j
public class ImageTranslationService {

    /** Follows a picture whose tier is {@code warned}: usable, but it may have gaps or typos. */
    public static final String WARNING_TEXT = "⚠️ 圖片翻譯可能有遺漏或錯字，數字請以原圖為準";

    /** Introduces the translated text that replaces a picture the service did not trust. */
    public static final String TEXT_ONLY_PREFIX = "圖片無法完整翻譯，以下是文字翻譯：";

    static final String DISABLED_TEXT = "圖片翻譯功能目前已停用。請稍後再試。";
    static final String NO_TEXT_TEXT = "未能識別到圖片中的文字。請確保圖片中包含清晰可見的文字。";
    static final String BUDGET_TEXT = "本月圖片翻譯額度已用完，請下個月再試。";
    static final String SERVICE_UNAVAILABLE_TEXT = "圖片翻譯服務暫時無法使用，請稍後再試。";
    static final String FAILED_TEXT = "圖片處理失敗，請稍後再試。";

    private final ImageTranslationPipeline imageTranslationPipeline;
    private final UserProfileRepository userProfileRepository;
    private final UserPreferencesModule userPreferencesModule;
    private final RuntimeSettingsSource runtimeSettingsSource;
    private final ImageTranslatorProperties imageTranslatorProperties;

    @Autowired
    public ImageTranslationService(
            ImageTranslationPipeline imageTranslationPipeline,
            UserProfileRepository userProfileRepository,
            UserPreferencesModule userPreferencesModule,
            RuntimeSettingsSource runtimeSettingsSource,
            ImageTranslatorProperties imageTranslatorProperties) {
        this.imageTranslationPipeline = imageTranslationPipeline;
        this.userProfileRepository = userProfileRepository;
        this.userPreferencesModule = userPreferencesModule;
        this.runtimeSettingsSource = runtimeSettingsSource;
        this.imageTranslatorProperties = imageTranslatorProperties;
    }

    /** Compatibility constructor for focused unit tests: the service client is switched on. */
    public ImageTranslationService(
            ImageTranslationPipeline imageTranslationPipeline,
            UserProfileRepository userProfileRepository,
            UserPreferencesModule userPreferencesModule,
            RuntimeSettingsSource runtimeSettingsSource) {
        this(imageTranslationPipeline, userProfileRepository, userPreferencesModule,
                runtimeSettingsSource, new ImageTranslatorProperties());
    }

    /** Compatibility constructor for focused unit tests. */
    public ImageTranslationService(
            ImageTranslationPipeline imageTranslationPipeline,
            UserProfileRepository userProfileRepository,
            UserPreferencesModule userPreferencesModule) {
        this(imageTranslationPipeline, userProfileRepository, userPreferencesModule, () -> null);
    }

    /**
     * Image translation is on while the administrator's {@code ocrEnabled} setting (deployment default
     * {@code OCR_ENABLED}) is on and {@code app.image-translator.enabled} has not switched the client off.
     */
    public boolean isOcrEnabled() {
        RuntimeSettings settings = runtimeSettingsSource.current();
        return imageTranslatorProperties.isEnabled() && (settings == null || settings.ocrEnabled());
    }

    public String processImageTranslation(String userId, String messageId) {
        return processImageTranslationResponse(userId, messageId).displayText();
    }

    public TranslationResponse processImageTranslationResponse(String userId, String messageId) {
        return processImageTranslationReply(userId, messageId).response();
    }

    public ImageTranslationReply processImageTranslationReply(String userId, String messageId) {
        if (!isOcrEnabled()) {
            return text(DISABLED_TEXT);
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
            return text(renderFailure((ImageTranslationOutcome.Failure) outcome));
        } catch (RuntimeException failure) {
            log.error("圖片翻譯失敗: user={}, failure={}",
                    SafeLog.user(userId), SafeLog.failure(failure));
            return text("圖片翻譯處理失敗，請稍後再試。");
        }
    }

    private static ImageTranslationReply text(String message) {
        return ImageTranslationReply.text(TranslationResponse.plain(message));
    }

    private ImageTranslationReply render(ImageTranslationPipelineResult result) {
        Optional<String> renderedImageUrl = result.renderedImage().url()
                .filter(ImageTranslationService::isSafeRenderedImageUrl);
        if (renderedImageUrl.isEmpty()) {
            return renderTextOnly(result);
        }
        ImageTranslationPipelineResult.Text translation = result.text();
        Optional<String> renderedPreviewUrl = result.renderedImage().previewUrl()
                .filter(ImageTranslationService::isSafeRenderedImageUrl);
        StringBuilder display = new StringBuilder("【翻譯圖片（連結 1 小時內有效）】\n")
                .append(renderedImageUrl.get())
                .append("\n\n")
                .append("【圖片文字辨識結果】\n\n")
                .append("識別的文字：\n");
        String translationSection = "\n\n翻譯結果：\n" + translation.translatedText() + "\n\n"
                + "[偵測到: " + LanguageUtils.toChineseName(translation.sourceLanguage())
                + " | 翻譯成: " + LanguageUtils.toChineseName(translation.targetLanguage()) + "]";
        // Recognized text yields its space first so the translation stays inside the LINE limit.
        int recognizedBudget = LineTextLimit.MAX_CODE_POINTS
                - LineTextLimit.codePoints(display) - LineTextLimit.codePoints(translationSection);
        display.append(LineTextLimit.fit(translation.recognizedText(), recognizedBudget))
                .append(translationSection);
        return new ImageTranslationReply(
                response(display.toString(), translation),
                renderedImageUrl,
                renderedPreviewUrl,
                result.warned() ? Optional.of(WARNING_TEXT) : Optional.empty());
    }

    /** No picture to send: the translated text, behind a line that says why it is not a picture. */
    private ImageTranslationReply renderTextOnly(ImageTranslationPipelineResult result) {
        ImageTranslationPipelineResult.Text translation = result.text();
        if (translation.translatedText().isBlank()) {
            return text(FAILED_TEXT);
        }
        String head = TEXT_ONLY_PREFIX + "\n";
        String display = head + LineTextLimit.fit(
                translation.translatedText(), LineTextLimit.MAX_CODE_POINTS - LineTextLimit.codePoints(head));
        return ImageTranslationReply.text(response(display, translation));
    }

    private static TranslationResponse response(String display, ImageTranslationPipelineResult.Text translation) {
        return new TranslationResponse(
                display,
                translation.translatedText(),
                translation.recordId(),
                translation.sourceLanguage(),
                translation.targetLanguage(),
                translation.stylePresetId(),
                translation.stylePromptVersion());
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

    private static String renderFailure(ImageTranslationOutcome.Failure failure) {
        return switch (failure.stage()) {
            case NO_TEXT -> NO_TEXT_TEXT;
            case BUDGET -> BUDGET_TEXT;
            case SERVICE_UNAVAILABLE -> SERVICE_UNAVAILABLE_TEXT;
            case TRANSLATION, DOWNLOAD, CANCELLED -> FAILED_TEXT;
        };
    }

    private UserProfile ensureUserProfileExists(String userId) {
        UserProfile userProfile = userPreferencesModule.profile(userId);
        userProfileRepository.setLastInteractionAt(userId, LocalDateTime.now());
        return userProfile;
    }
}
