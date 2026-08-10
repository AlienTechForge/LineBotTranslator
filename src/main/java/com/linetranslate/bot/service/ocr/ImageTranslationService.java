package com.linetranslate.bot.service.ocr;


import java.time.Duration;
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
import com.linetranslate.bot.config.AppConfig;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderExecutionModule;
import com.linetranslate.bot.service.ai.AiProviderException;
import com.linetranslate.bot.service.storage.ImageStorageResult;
import com.linetranslate.bot.service.storage.MinioStorageService;
import com.linetranslate.bot.service.translation.LanguageDetectionService;
import com.linetranslate.bot.service.translation.TranslationService;
import com.linetranslate.bot.util.LanguageUtils;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ImageTranslationService {

    private final OcrService ocrService;
    private final TranslationService translationService;
    private final LanguageDetectionService languageDetectionService;
    private final AiProviderExecutionModule aiProviderExecutionModule;
    private final MessagingApiBlobClient messagingApiBlobClient;
    private final TranslationRecordRepository translationRecordRepository;
    private final UserProfileRepository userProfileRepository;
    private final AppConfig appConfig;
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
            TranslationService translationService,
            LanguageDetectionService languageDetectionService,
            AiProviderExecutionModule aiProviderExecutionModule,
            MessagingApiBlobClient messagingApiBlobClient,
            TranslationRecordRepository translationRecordRepository,
            UserProfileRepository userProfileRepository,
            AppConfig appConfig,
            MinioStorageService minioStorageService) {
        this.ocrService = ocrServiceProvider.getIfAvailable();
        this.translationService = translationService;
        this.languageDetectionService = languageDetectionService;
        this.aiProviderExecutionModule = aiProviderExecutionModule;
        this.messagingApiBlobClient = messagingApiBlobClient;
        this.translationRecordRepository = translationRecordRepository;
        this.userProfileRepository = userProfileRepository;
        this.appConfig = appConfig;
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

            // 檢測文字語言
            String sourceLanguage = languageDetectionService.detectLanguage(recognizedText);

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
            
            // 如果沒有在文字中指定目標語言，則使用默認的目標語言選擇邏輯
            if (targetLanguage == null) {
                targetLanguage = getDefaultTargetLanguage(sourceLanguage, userProfile);
            }

            // 翻譯文字
            AiExecutionResult translationResult = translationService.translateWithService(
                    userProfile,
                    recognizedText,
                    targetLanguage);
            String translatedText = translationResult.text();

            // 計算處理時間
            long processingTimeMs = Duration.between(start, Instant.now()).toMillis();

            // 保存翻譯記錄
            saveTranslationRecord(userId, recognizedText, sourceLanguage, targetLanguage,
                    translatedText, translationResult.providerName(), translationResult.modelName(),
                    processingTimeMs, true, storageResult.url().orElse(null), storageResult.stored());

            // 更新用戶資料
            updateUserProfileAfterImageTranslation(userProfile);

            // 構建響應消息
            StringBuilder resultBuilder = new StringBuilder();
            resultBuilder.append("【圖片文字辨識結果】\n\n");
            resultBuilder.append("識別的文字：\n").append(recognizedText).append("\n\n");
            resultBuilder.append("翻譯結果：\n").append(translatedText);
            
            // 添加偵測到的語言資訊和翻譯目標語言
            String sourceLanguageName = com.linetranslate.bot.util.LanguageUtils.toChineseName(sourceLanguage);
            String targetLanguageName = com.linetranslate.bot.util.LanguageUtils.toChineseName(targetLanguage);
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
     * 根據源語言和用戶資料選擇默認的目標語言
     */
    private String getDefaultTargetLanguage(String sourceLanguage, UserProfile userProfile) {
        log.info("源語言: {}, 檢查是否為中文", sourceLanguage);
        // 檢查源語言是否為中文（包括 zh, zh-CN, zh-TW 等）
        boolean isChinese = sourceLanguage != null && sourceLanguage.startsWith("zh");
        
        String targetLanguage;
        if (isChinese) {
            // 如果是中文，先檢查用戶是否設置了偏好的中文翻譯目標語言
            String preferredChineseTargetLanguage = userProfile.getPreferredChineseTargetLanguage();
            if (preferredChineseTargetLanguage != null && !preferredChineseTargetLanguage.isEmpty()) {
                targetLanguage = preferredChineseTargetLanguage;
                log.info("使用用戶偏好的中文翻譯目標語言: {}", targetLanguage);
            } else {
                targetLanguage = appConfig.getDefaultTargetLanguageForChinese();
                log.info("使用系統預設的中文翻譯目標語言: {}", targetLanguage);
            }
        } else {
            // 如果不是中文，先檢查用戶是否設置了偏好的語言
            String preferredLanguage = userProfile.getPreferredLanguage();
            if (preferredLanguage != null && !preferredLanguage.isEmpty()) {
                targetLanguage = preferredLanguage;
                log.info("使用用戶偏好的目標語言: {}", targetLanguage);
            } else {
                targetLanguage = appConfig.getDefaultTargetLanguageForOthers();
                log.info("使用系統預設的目標語言: {}", targetLanguage);
            }
        }
        
        return targetLanguage;
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

    /**
     * 保存翻譯記錄
     */
    private void saveTranslationRecord(String userId, String sourceText, String sourceLanguage,
                                       String targetLanguage, String translatedText, String aiProvider,
                                       String modelName, double processingTimeMs, boolean isImageTranslation,
                                       String imageUrl, boolean imageStored) {

        TranslationRecord record = TranslationRecord.builder()
                .userId(userId)
                .sourceText(sourceText)
                .sourceLanguage(sourceLanguage)
                .targetLanguage(targetLanguage)
                .translatedText(translatedText)
                .aiProvider(aiProvider)
                .modelName(modelName)
                .createdAt(LocalDateTime.now())
                .processingTimeMs(processingTimeMs)
                .isImageTranslation(isImageTranslation)
                .imageUrl(imageUrl)
                .imageStored(imageStored)
                .build();

        translationRecordRepository.save(record);
        log.info("已保存圖片翻譯記錄: user={}", SafeLog.user(userId));
    }

    /**
     * 更新用戶資料
     */
    private void updateUserProfileAfterImageTranslation(UserProfile userProfile) {
        userProfile.setLastInteractionAt(LocalDateTime.now());
        userProfile.setTotalTranslations(userProfile.getTotalTranslations() + 1);
        userProfile.setImageTranslations(userProfile.getImageTranslations() + 1);
        userProfileRepository.save(userProfile);
    }
}
