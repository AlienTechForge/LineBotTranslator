package com.linetranslate.bot.service.ocr;


import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.linecorp.bot.client.base.BlobContent;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiExecutionFailure;
import com.linetranslate.bot.service.ai.AiProviderExecutionModule;
import com.linetranslate.bot.service.ai.AiProviderException;
import com.linetranslate.bot.service.storage.ImageStorageResult;
import com.linetranslate.bot.service.storage.MinioStorageService;
import com.linetranslate.bot.service.translation.TranslationRequestKind;
import com.linetranslate.bot.service.translation.TranslationWorkflowModule;
import com.linetranslate.bot.service.translation.TranslationWorkflowOutcome;
import com.linetranslate.bot.service.translation.TranslationWorkflowRequest;
import com.linetranslate.bot.service.translation.TranslationWorkflowResult;
import com.linetranslate.bot.util.LanguageUtils;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ImageTranslationService {

    private final OcrService ocrService;
    private final TranslationWorkflowModule translationWorkflowModule;
    private final AiProviderExecutionModule aiProviderExecutionModule;
    private final MessagingApiBlobClient messagingApiBlobClient;
    private final UserProfileRepository userProfileRepository;
    private final MinioStorageService minioStorageService;
    
    // 翻譯指令的正則表達式模式（中文語言名稱）
    private static final Pattern TRANSLATION_COMMAND_PATTERN_CN = Pattern.compile("翻譯成([\\u4e00-\\u9fa5]+)\\s*(.*)");

    // 翻譯指令的正則表達式模式（語言代碼）
    private static final Pattern TRANSLATION_COMMAND_PATTERN_CODE = Pattern.compile("翻譯成([a-zA-Z\\-]+)\\s*(.*)");

    @Value("${app.ocr.enabled:true}")
    private boolean ocrEnabled;
    
    /**
     * 檢查 OCR 功能是否啟用
     * 
     * @return OCR 功能是否啟用
     */
    public boolean isOcrEnabled() {
        return ocrEnabled;
    }

    public ImageTranslationService(
            ObjectProvider<OcrService> ocrServiceProvider,
            TranslationWorkflowModule translationWorkflowModule,
            AiProviderExecutionModule aiProviderExecutionModule,
            MessagingApiBlobClient messagingApiBlobClient,
            UserProfileRepository userProfileRepository,
            MinioStorageService minioStorageService) {
        this.ocrService = ocrServiceProvider.getIfAvailable();
        this.translationWorkflowModule = translationWorkflowModule;
        this.aiProviderExecutionModule = aiProviderExecutionModule;
        this.messagingApiBlobClient = messagingApiBlobClient;
        this.userProfileRepository = userProfileRepository;
        this.minioStorageService = minioStorageService;
    }

    /**
     * 處理圖片翻譯
     *
     * @param userId 用戶 ID
     * @param messageId 圖片消息 ID
     * @return 翻譯結果
     */
    public String processImageTranslation(String userId, String messageId) {
        if (!ocrEnabled) {
            return "OCR 功能目前已停用。請稍後再試。";
        }

        Instant start = Instant.now();
        log.info("處理圖片翻譯請求: user={}, message={}",
                SafeLog.user(userId), SafeLog.content(messageId));

        try {
            // 獲取用戶資料
            UserProfile userProfile = ensureUserProfileExists(userId);

            // 獲取圖片內容並轉換為Base64
            String recognizedText;
            ImageStorageResult storageResult = ImageStorageResult.notStored();

            try {
                Result<BlobContent> response = messagingApiBlobClient.getMessageContent(messageId).get();
                BlobContent content = response.body();
                if (content == null) {
                    throw new IllegalStateException("LINE image response body is empty");
                }
                byte[] imageBytes;
                try (java.io.InputStream imageStream = content.byteStream()) {
                    imageBytes = imageStream.readAllBytes();
                }
                String base64Image = Base64.getEncoder().encodeToString(imageBytes);
                
                // 上傳圖片到 MinIO 並獲取 URL
                // LINE 平台的圖片通常是 JPEG 格式
                String contentType = "image/jpeg";
                storageResult = minioStorageService.uploadImage(imageBytes, contentType);
                log.info("圖片儲存處理完成: stored={}, urlPresent={}",
                        storageResult.stored(), storageResult.url().isPresent());

                // 準備OCR識別文字
                if (ocrService != null) {
                    // 如果Google Vision可用，使用它
                    recognizedText = ocrService.recognizeText(new java.io.ByteArrayInputStream(imageBytes));
                } else {
                    // 使用AI服務進行圖像識別
                    log.info("Google Vision不可用，使用AI模型識別圖片文字");

                    // 構建提示詞
                    String prompt = "請識別這張圖片中的所有文字，只返回文字內容，不要添加任何其他描述或解釋。";

                    AiExecutionResult ocrResult = aiProviderExecutionModule.processImage(
                            userProfile,
                            prompt,
                            "data:image/jpeg;base64," + base64Image);
                    recognizedText = ocrResult.text();
                }
                
            } catch (Exception e) {
                log.error("圖片處理失敗: user={}, failure={}",
                        SafeLog.user(userId), SafeLog.failure(e));
                return "圖片處理失敗，請稍後再試。";
            }

            if (recognizedText == null || recognizedText.trim().isEmpty()) {
                return "未能識別到圖片中的文字。請確保圖片中包含清晰可見的文字。";
            }

            log.info("圖片文字識別完成: content={}", SafeLog.content(recognizedText));

            // 確定目標語言
            String targetLanguage = null;
            
            // 檢查文字中是否包含「翻譯成XX文」的指令
            Matcher matcherCN = TRANSLATION_COMMAND_PATTERN_CN.matcher(recognizedText);
            Matcher matcherCode = TRANSLATION_COMMAND_PATTERN_CODE.matcher(recognizedText);
            
            if (matcherCN.find()) {
                // 使用用戶指定的中文語言名稱
                String languageName = matcherCN.group(1);
                targetLanguage = LanguageUtils.toLanguageCode(languageName);
                log.info("圖片文字中指定翻譯成: {} ({})", languageName, targetLanguage);
            } else if (matcherCode.find()) {
                // 使用用戶指定的語言代碼
                String languageCode = matcherCode.group(1);
                targetLanguage = languageCode.toLowerCase();
                log.info("圖片文字中指定翻譯成: {} ({})", languageCode, LanguageUtils.toChineseName(targetLanguage));
            }
            
            TranslationWorkflowOutcome workflowOutcome = translationWorkflowModule.execute(
                    new TranslationWorkflowRequest(
                            userProfile,
                            recognizedText,
                            targetLanguage,
                            TranslationRequestKind.IMAGE_OCR,
                            storageResult.url().orElse(null),
                            storageResult.stored(),
                            start));
            if (workflowOutcome instanceof TranslationWorkflowOutcome.Failure failure) {
                logWorkflowFailure(userId, failure.failure());
                return "圖片翻譯服務暫時無法使用，請稍後再試。";
            }
            TranslationWorkflowResult workflowResult =
                    ((TranslationWorkflowOutcome.Success) workflowOutcome).result();
            String translatedText = workflowResult.translatedText();

            // 構建響應消息
            StringBuilder resultBuilder = new StringBuilder();
            resultBuilder.append("【圖片文字辨識結果】\n\n");
            resultBuilder.append("識別的文字：\n").append(recognizedText).append("\n\n");
            resultBuilder.append("翻譯結果：\n").append(translatedText);
            
            // 添加偵測到的語言資訊和翻譯目標語言
            String sourceLanguageName = LanguageUtils.toChineseName(workflowResult.sourceLanguage());
            String targetLanguageName = LanguageUtils.toChineseName(workflowResult.targetLanguage());
            resultBuilder.append("\n\n[偵測到: ").append(sourceLanguageName)
                      .append(" | 翻譯成: ").append(targetLanguageName).append("]");

            return resultBuilder.toString();

        } catch (AiProviderException failure) {
            log.warn(
                    "AI 圖片翻譯未完成: user={}, provider={}, model={}, outcome={}, reason={}, correlation={}",
                    SafeLog.user(userId),
                    failure.getProvider(),
                    failure.getModel(),
                    failure.getOutcome(),
                    failure.getReason(),
                    failure.getCorrelationId());
            return "圖片翻譯服務暫時無法使用，請稍後再試。";
        } catch (Exception e) {
            log.error("圖片翻譯失敗: user={}, failure={}",
                    SafeLog.user(userId), SafeLog.failure(e));
            return "圖片翻譯處理失敗，請稍後再試。";
        }
    }

    /**
     * 確保用戶資料存在
     */
    private UserProfile ensureUserProfileExists(String userId) {
        Optional<UserProfile> userProfileOptional = userProfileRepository.findByUserId(userId);

        if (userProfileOptional.isPresent()) {
            UserProfile userProfile = userProfileOptional.get();
            userProfile.setLastInteractionAt(LocalDateTime.now());
            return userProfileRepository.save(userProfile);
        } else {
            UserProfile newUserProfile = UserProfile.builder()
                    .userId(userId)
                    .firstInteractionAt(LocalDateTime.now())
                    .lastInteractionAt(LocalDateTime.now())
                    .build();
            return userProfileRepository.save(newUserProfile);
        }
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
