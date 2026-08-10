package com.linetranslate.bot.service.translation;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.config.AppConfig;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderException;
import com.linetranslate.bot.service.ai.AiServiceFactory;
import com.linetranslate.bot.service.translation.LanguageDetectionService;
import com.linetranslate.bot.util.LanguageUtils;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class TranslationService {

    private static final String PROVIDER_UNAVAILABLE_MESSAGE =
            "翻譯服務暫時無法使用，請稍後再試。";

    private final LanguageDetectionService languageDetectionService;
    private final AiServiceFactory aiServiceFactory;
    private final TranslationRecordRepository translationRecordRepository;
    private final UserProfileRepository userProfileRepository;
    private final AppConfig appConfig;

    // 翻譯指令的正則表達式模式（中文語言名稱）
    private static final Pattern TRANSLATION_COMMAND_PATTERN_CN = Pattern.compile("翻譯成([\\u4e00-\\u9fa5]+)\\s*(.*)");

    // 翻譯指令的正則表達式模式（語言代碼）
    private static final Pattern TRANSLATION_COMMAND_PATTERN_CODE = Pattern.compile("翻譯成([a-zA-Z\\-]+)\\s*(.*)");
    
    // 處理「文本 翻譯成XX文」格式的模式（中文語言名稱）
    private static final Pattern TEXT_THEN_TRANSLATION_PATTERN_CN = Pattern.compile("(.+?)\\s+翻譯成([\\u4e00-\\u9fa5]+)\\s*(.*)");
    
    // 處理「文本 翻譯成XX」格式的模式（語言代碼）
    private static final Pattern TEXT_THEN_TRANSLATION_PATTERN_CODE = Pattern.compile("(.+?)\\s+翻譯成([a-zA-Z\\-]+)\\s*(.*)");

    // 多行翻譯指令的正則表達式模式（處理 "xxx\n翻譯成yyy" 的情況）
    private static final Pattern MULTILINE_TRANSLATION_PATTERN_CN = Pattern.compile("(.*?)\\n翻譯成([\\u4e00-\\u9fa5]+)\\s*(.*)");
    private static final Pattern MULTILINE_TRANSLATION_PATTERN_CODE = Pattern.compile("(.*?)\\n翻譯成([a-zA-Z\\-]+)\\s*(.*)");

    @Autowired
    public TranslationService(
            LanguageDetectionService languageDetectionService,
            AiServiceFactory aiServiceFactory,
            TranslationRecordRepository translationRecordRepository,
            UserProfileRepository userProfileRepository,
            AppConfig appConfig) {
        this.languageDetectionService = languageDetectionService;
        this.aiServiceFactory = aiServiceFactory;
        this.translationRecordRepository = translationRecordRepository;
        this.userProfileRepository = userProfileRepository;
        this.appConfig = appConfig;
    }

    /**
     * 處理用戶的翻譯請求
     *
     * @param userId 用戶 ID
     * @param text 用戶輸入的文本
     * @return 翻譯結果
     */
    public String processTranslationRequest(String userId, String text) {
        Instant start = Instant.now();
        log.info("收到翻譯請求: user={}, content={}",
                SafeLog.user(userId), SafeLog.content(text));

        // 檢查用戶是否已存在，如果不存在則創建
        UserProfile userProfile = ensureUserProfileExists(userId);

        // 檢查是否是多行翻譯指令格式 (例如：你好\n翻譯成日文)
        Matcher multilineMatcherCN = MULTILINE_TRANSLATION_PATTERN_CN.matcher(text);
        Matcher multilineMatcherCode = MULTILINE_TRANSLATION_PATTERN_CODE.matcher(text);

        String sourceText;
        String targetLanguage;

        // 處理多行翻譯格式
        if (multilineMatcherCN.find()) {
            sourceText = multilineMatcherCN.group(1).trim();
            String languageName = multilineMatcherCN.group(2);
            targetLanguage = LanguageUtils.toLanguageCode(languageName);
            String additionalText = multilineMatcherCN.group(3).trim();

            // 如果有額外文本，添加到源文本
            if (!additionalText.isEmpty()) {
                sourceText += " " + additionalText;
            }

            if (sourceText.isEmpty()) {
                return "請提供要翻譯成" + languageName + "的文字。";
            }

            log.info("多行格式翻譯: languageName={}, target={}, content={}",
                    languageName, targetLanguage, SafeLog.content(sourceText));
        }
        else if (multilineMatcherCode.find()) {
            sourceText = multilineMatcherCode.group(1).trim();
            String languageCode = multilineMatcherCode.group(2);
            targetLanguage = languageCode.toLowerCase();
            String additionalText = multilineMatcherCode.group(3).trim();

            // 如果有額外文本，添加到源文本
            if (!additionalText.isEmpty()) {
                sourceText += " " + additionalText;
            }

            if (sourceText.isEmpty()) {
                return "請提供要翻譯成" + languageCode + "的文字。";
            }

            log.info("多行格式翻譯: languageCode={}, targetName={}, content={}",
                    languageCode, LanguageUtils.toChineseName(targetLanguage), SafeLog.content(sourceText));
        }
        // 處理「文本 翻譯成XX文」格式
        else if (text.contains("翻譯成")) {
            Matcher textThenTranslationMatcherCN = TEXT_THEN_TRANSLATION_PATTERN_CN.matcher(text);
            Matcher textThenTranslationMatcherCode = TEXT_THEN_TRANSLATION_PATTERN_CODE.matcher(text);
            
            if (textThenTranslationMatcherCN.find()) {
                // 使用用戶指定的中文語言名稱
                sourceText = textThenTranslationMatcherCN.group(1).trim();
                String languageName = textThenTranslationMatcherCN.group(2);
                targetLanguage = LanguageUtils.toLanguageCode(languageName);
                String additionalText = textThenTranslationMatcherCN.group(3).trim();
                
                // 如果有額外文本，添加到源文本
                if (!additionalText.isEmpty()) {
                    sourceText += " " + additionalText;
                }
                
                log.info("文本後翻譯格式: languageName={}, target={}, content={}",
                        languageName, targetLanguage, SafeLog.content(sourceText));
            } else if (textThenTranslationMatcherCode.find()) {
                // 使用用戶指定的語言代碼
                sourceText = textThenTranslationMatcherCode.group(1).trim();
                String languageCode = textThenTranslationMatcherCode.group(2);
                targetLanguage = languageCode.toLowerCase();
                String additionalText = textThenTranslationMatcherCode.group(3).trim();
                
                // 如果有額外文本，添加到源文本
                if (!additionalText.isEmpty()) {
                    sourceText += " " + additionalText;
                }
                
                log.info("文本後翻譯格式: languageCode={}, targetName={}, content={}",
                        languageCode, LanguageUtils.toChineseName(targetLanguage), SafeLog.content(sourceText));
            }
            // 檢查是否是單行翻譯指令格式 (例如：翻譯成日文 你好)
            else if (text.startsWith("翻譯成")) {
                Matcher matcherCN = TRANSLATION_COMMAND_PATTERN_CN.matcher(text);
                Matcher matcherCode = TRANSLATION_COMMAND_PATTERN_CODE.matcher(text);

                if (matcherCN.find()) {
                    // 使用用戶指定的中文語言名稱
                    String languageName = matcherCN.group(1);
                    targetLanguage = LanguageUtils.toLanguageCode(languageName);
                    sourceText = matcherCN.group(2).trim();

                    if (sourceText.isEmpty()) {
                        return "請在「翻譯成" + languageName + "」後面輸入要翻譯的文字。";
                    }

                    log.info("指定翻譯: languageName={}, target={}, content={}",
                            languageName, targetLanguage, SafeLog.content(sourceText));
                } else if (matcherCode.find()) {
                    // 使用用戶指定的語言代碼
                    String languageCode = matcherCode.group(1);
                    targetLanguage = languageCode.toLowerCase();
                    sourceText = matcherCode.group(2).trim();

                    if (sourceText.isEmpty()) {
                        return "請在「翻譯成" + languageCode + "」後面輸入要翻譯的文字。";
                    }

                    log.info("指定翻譯: languageCode={}, targetName={}, content={}",
                            languageCode, LanguageUtils.toChineseName(targetLanguage), SafeLog.content(sourceText));
                } else {
                    // 如果格式不正確，使用默認翻譯處理
                    return handleDefaultTranslation(userId, text, userProfile, start);
                }
            } else {
                // 如果包含「翻譯成」但格式不符合任何模式，使用默認翻譯處理
                return handleDefaultTranslation(userId, text, userProfile, start);
            }
        } else {
            // 使用默認翻譯處理
            return handleDefaultTranslation(userId, text, userProfile, start);
        }

        // 指定目標語言的請求仍需偵測一次來源語言，並在後續流程重用。
        String sourceLanguage = languageDetectionService.detectLanguage(sourceText);
        return performTranslation(
                userId, userProfile, sourceText, sourceLanguage, targetLanguage, start);
    }

    /**
     * 處理默認的翻譯情況（無指定目標語言）
     */
    private String handleDefaultTranslation(String userId, String text, UserProfile userProfile, Instant start) {
        // 使用自動檢測語言並選擇目標語言
        String sourceText = text;
        String sourceLanguage = languageDetectionService.detectLanguage(sourceText);
        String targetLanguage = getDefaultTargetLanguage(sourceLanguage, userProfile);

        log.info("自動檢測語言: {}, 目標語言: {}", sourceLanguage, targetLanguage);

        return performTranslation(
                userId, userProfile, sourceText, sourceLanguage, targetLanguage, start);
    }

    /**
     * 根據源語言選擇默認的目標語言
     *
     * @param sourceLanguage 源語言
     * @param userProfile 用戶資料
     * @return 目標語言代碼
     */
    private String getDefaultTargetLanguage(String sourceLanguage, UserProfile userProfile) {
        // 如果是中文，且用戶設置了偏好的中文翻譯目標語言
        if (sourceLanguage != null && (sourceLanguage.startsWith("zh") || "zh".equals(sourceLanguage))) {
            String preferredChineseTargetLanguage = userProfile.getPreferredChineseTargetLanguage();
            if (preferredChineseTargetLanguage != null && !preferredChineseTargetLanguage.isEmpty()) {
                return preferredChineseTargetLanguage;
            }
            return appConfig.getDefaultTargetLanguageForChinese();
        } else {
            // 如果不是中文，使用用戶偏好的語言或默認設置
            String preferredLanguage = userProfile.getPreferredLanguage();
            if (preferredLanguage != null
                    && !preferredLanguage.isEmpty()
                    && !preferredLanguage.equalsIgnoreCase(sourceLanguage)) {
                return preferredLanguage;
            }
            return appConfig.getDefaultTargetLanguageForOthers();
        }
    }

    /**
     * 執行翻譯並處理相關記錄
     */
    private String performTranslation(
            String userId,
            UserProfile userProfile,
            String sourceText,
            String sourceLanguage,
            String targetLanguage,
            Instant start) {
        AiExecutionResult executionResult;
        try {
            executionResult = translateWithService(userProfile, sourceText, targetLanguage);
        } catch (AiProviderException failure) {
            logTranslationFailure(userId, failure);
            return PROVIDER_UNAVAILABLE_MESSAGE;
        }
        String translatedText = executionResult.text();

        // 計算處理時間
        long processingTimeMs = Duration.between(start, Instant.now()).toMillis();

        // 保存翻譯記錄
        saveTranslationRecord(userId, sourceText, sourceLanguage,
                targetLanguage, translatedText, executionResult.providerName(),
                executionResult.modelName(), processingTimeMs, false, null);

        // 更新用戶資料
        updateUserProfileAfterTranslation(userProfile, translatedText, targetLanguage);

        // 在翻譯結果中添加偵測到的語言資訊和翻譯目標語言
        String sourceLanguageName = LanguageUtils.toChineseName(sourceLanguage);
        String targetLanguageName = LanguageUtils.toChineseName(targetLanguage);
        return translatedText + "\n\n[偵測到: " + sourceLanguageName + " | 翻譯成: " + targetLanguageName + "]";
    }

    /**
     * 快速翻譯到指定語言
     *
     * @param userId 用戶 ID
     * @param text 要翻譯的文本
     * @param targetLanguageCode 目標語言代碼
     * @return 翻譯結果
     */
    public String quickTranslate(String userId, String text, String targetLanguageCode) {
        if (!LanguageUtils.isSupported(targetLanguageCode)) {
            return "不支持的語言代碼：" + targetLanguageCode;
        }

        Instant start = Instant.now();
        UserProfile userProfile = ensureUserProfileExists(userId);

        String standardLanguageCode = LanguageUtils.toLanguageCode(targetLanguageCode);
        log.info("快速翻譯請求: user={}, target={}, content={}",
                SafeLog.user(userId), standardLanguageCode, SafeLog.content(text));

        String sourceLanguage = languageDetectionService.detectLanguage(text);

        AiExecutionResult executionResult;
        try {
            executionResult = translateWithService(userProfile, text, standardLanguageCode);
        } catch (AiProviderException failure) {
            logTranslationFailure(userId, failure);
            return PROVIDER_UNAVAILABLE_MESSAGE;
        }
        String translatedText = executionResult.text();

        // 計算處理時間
        long processingTimeMs = Duration.between(start, Instant.now()).toMillis();

        // 保存翻譯記錄
        saveTranslationRecord(userId, text, sourceLanguage,
                standardLanguageCode, translatedText, executionResult.providerName(),
                executionResult.modelName(), processingTimeMs, false, null);

        // 更新用戶資料
        updateUserProfileAfterTranslation(userProfile, translatedText, standardLanguageCode);

        return translatedText;
    }

    /**
     * 處理多行文本翻譯
     *
     * @param userId 用戶 ID
     * @param text 多行文本
     * @return 翻譯結果
     */
    public String processBatchTranslation(String userId, String text) {
        if (text == null || text.trim().isEmpty()) {
            return "請提供要翻譯的文本。";
        }

        // 檢查文本是否包含多行
        String[] lines = text.split("\n");
        if (lines.length <= 1) {
            // 如果只有一行，直接使用標準翻譯處理
            return processTranslationRequest(userId, text);
        }

        Instant start = Instant.now();
        UserProfile userProfile = ensureUserProfileExists(userId);

        // 檢測原文語言
        String sourceLanguage = languageDetectionService.detectLanguage(text);

        // 確定目標語言
        String targetLanguage;
        if (userProfile.getPreferredLanguage() != null && !userProfile.getPreferredLanguage().isEmpty()) {
            // 如果用戶偏好與源語言相同，使用默認規則
            if (sourceLanguage.equals(userProfile.getPreferredLanguage()) ||
                    (sourceLanguage.startsWith("zh") && userProfile.getPreferredLanguage().startsWith("zh"))) {
                targetLanguage = getDefaultTargetLanguage(sourceLanguage, userProfile);
            } else {
                targetLanguage = userProfile.getPreferredLanguage();
            }
        } else {
            targetLanguage = getDefaultTargetLanguage(sourceLanguage, userProfile);
        }

        AiExecutionResult executionResult;
        try {
            executionResult = translateWithService(userProfile, text, targetLanguage);
        } catch (AiProviderException failure) {
            logTranslationFailure(userId, failure);
            return PROVIDER_UNAVAILABLE_MESSAGE;
        }
        String translatedText = executionResult.text();

        // 計算處理時間
        long processingTimeMs = Duration.between(start, Instant.now()).toMillis();

        // 保存翻譯記錄
        saveTranslationRecord(userId, text, sourceLanguage, targetLanguage,
                translatedText, executionResult.providerName(),
                executionResult.modelName(), processingTimeMs, false, null);

        // 更新用戶資料
        updateUserProfileAfterTranslation(userProfile, translatedText, targetLanguage);

        return translatedText;
    }

    /**
     * 使用指定的 AI 服務進行翻譯
     *
     * @param userProfile user preferences used to select the primary provider
     * @param text 要翻譯的文本
     * @param targetLanguage 目標語言
     * @return 翻譯結果
     */
    @Cacheable(
            value = "translations",
            key = "{#text, #targetLanguage, #userProfile.preferredAiProvider, "
                    + "#userProfile.openaiPreferredModel, #userProfile.geminiPreferredModel}")
    public AiExecutionResult translateWithService(
            UserProfile userProfile,
            String text,
            String targetLanguage) {
        log.info("執行 AI 翻譯: target={}", targetLanguage);
        return aiServiceFactory.translateText(userProfile, text, targetLanguage);
    }

    private void logTranslationFailure(String userId, AiProviderException failure) {
        log.warn(
                "AI 翻譯未完成: user={}, provider={}, model={}, outcome={}, reason={}, correlation={}",
                SafeLog.user(userId),
                failure.getProvider(),
                failure.getModel(),
                failure.getOutcome(),
                failure.getReason(),
                failure.getCorrelationId());
    }

    /**
     * 確保用戶資料存在
     *
     * @param userId 用戶 ID
     * @return 用戶資料
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
                                       String modelName, double processingTimeMs, boolean isImageTranslation, String imageUrl) {

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
                .build();

        translationRecordRepository.save(record);
        log.info("已保存翻譯記錄: user={}", SafeLog.user(userId));
    }

    /**
     * 更新用戶資料
     */
    private void updateUserProfileAfterTranslation(UserProfile userProfile, String translatedText, String targetLanguage) {
        userProfile.setLastInteractionAt(LocalDateTime.now());
        userProfile.setTotalTranslations(userProfile.getTotalTranslations() + 1);
        userProfile.setTextTranslations(userProfile.getTextTranslations() + 1);

        // 更新最近的翻譯
        userProfile.getRecentTranslations().add(0, translatedText);
        if (userProfile.getRecentTranslations().size() > 5) {
            userProfile.getRecentTranslations().remove(5);
        }

        // 更新最近使用的語言
        userProfile.addRecentLanguage(targetLanguage);

        userProfileRepository.save(userProfile);
    }

    /**
     * 設置用戶偏好的 AI 提供者
     *
     * @param userId 用戶 ID
     * @param provider AI 提供者
     * @return 更新結果信息
     */
    public String setPreferredProvider(String userId, String provider) {
        if (!provider.equals("openai") && !provider.equals("gemini")) {
            return "不支持的 AI 提供者。請選擇 'openai' 或 'gemini'。";
        }

        UserProfile userProfile = ensureUserProfileExists(userId);
        userProfile.setPreferredAiProvider(provider);
        userProfileRepository.save(userProfile);

        return "已將您的偏好 AI 設置為 " + provider;
    }

    /**
     * 設置用戶偏好的語言
     *
     * @param userId 用戶 ID
     * @param language 語言代碼或名稱
     * @return 更新結果信息
     */
    public String setPreferredLanguage(String userId, String language) {
        String languageCode = LanguageUtils.toLanguageCode(language);

        if (!LanguageUtils.isSupported(languageCode)) {
            return "不支持的語言：" + language +
                    "。請使用支援的語言代碼（如 en、ja、ko）或語言名稱（如 英文、日文、韓文）。";
        }

        UserProfile userProfile = ensureUserProfileExists(userId);
        userProfile.setPreferredLanguage(languageCode);
        userProfileRepository.save(userProfile);

        String languageName = LanguageUtils.toChineseName(languageCode);
        return "已將您的偏好語言設置為 " + languageName + " (" + languageCode + ")";
    }

    /**
     * 獲取用戶最近使用的語言列表
     *
     * @param userId 用戶 ID
     * @return 最近使用的語言列表
     */
    public List<String> getRecentLanguages(String userId) {
        UserProfile userProfile = ensureUserProfileExists(userId);
        return userProfile.getRecentLanguagesList();
    }
    /**
     * 獲取用戶偏好的 AI 提供者
     *
     * @param userId 用戶 ID
     * @return 用戶偏好的 AI 提供者 (openai 或 gemini)
     */
    public String getPreferredProvider(String userId) {
        UserProfile userProfile = ensureUserProfileExists(userId);
        String provider = userProfile.getPreferredAiProvider();
        
        if (provider == null || provider.isEmpty()) {
            provider = "openai"; // 預設為 OpenAI
        }
        
        return provider;
    }
    
    /**
     * 設置用戶偏好的中文翻譯目標語言
     * 
     * @param userId 用戶 ID
     * @param targetLanguage 目標語言代碼或名稱
     * @return 設置結果消息
     */
    public String setPreferredChineseTargetLanguage(String userId, String targetLanguage) {
        UserProfile userProfile = ensureUserProfileExists(userId);
        
        // 將語言名稱轉換為語言代碼
        String languageCode = LanguageUtils.toLanguageCode(targetLanguage);
        
        if (!LanguageUtils.isSupported(languageCode)) {
            return "不支援的語言: " + targetLanguage +
                   "。請使用支援的語言代碼（如 en、ja、ko）或語言名稱（如 英文、日文、韓文）。";
        }
        
        // 保存用戶偏好的中文翻譯目標語言
        String oldLanguage = userProfile.getPreferredChineseTargetLanguage();
        userProfile.setPreferredChineseTargetLanguage(languageCode);
        userProfileRepository.save(userProfile);
        
        // 準備回應消息
        String languageName = LanguageUtils.toChineseName(languageCode);
        StringBuilder result = new StringBuilder();
        if (oldLanguage != null && !oldLanguage.isEmpty() && oldLanguage.equals(languageCode)) {
            result.append("已設置中文翻譯的預設目標語言為 ").append(languageName);
        } else if (oldLanguage != null && !oldLanguage.isEmpty()) {
            result.append("已將中文翻譯的預設目標語言從 ").append(LanguageUtils.toChineseName(oldLanguage))
                  .append(" 更改為 ").append(languageName);
        } else {
            result.append("已設置中文翻譯的預設目標語言為 ").append(languageName);
        }
        
        return result.toString();
    }
    
    /**
     * 獲取用戶的所有設定狀態
     *
     * @param userId 用戶 ID
     * @return 用戶設定狀態的字符串表示
     */
    public String getUserStatus(String userId) {
        UserProfile userProfile = ensureUserProfileExists(userId);
        
        StringBuilder status = new StringBuilder();
        status.append("您的翻譯設定：\n\n");
        
        // 偏好的 AI 提供者
        String provider = userProfile.getPreferredAiProvider();
        if (provider != null && !provider.isEmpty()) {
            status.append("• 偏好的 AI 提供者：").append(provider.toUpperCase()).append("\n");
            
            // 偏好的模型
            String model = "";
            if ("openai".equalsIgnoreCase(provider)) {
                model = userProfile.getOpenaiPreferredModel();
                if (model == null || model.isEmpty()) {
                    model = "gpt-4o"; // 預設模型
                }
            } else if ("gemini".equalsIgnoreCase(provider)) {
                model = userProfile.getGeminiPreferredModel();
                if (model == null || model.isEmpty()) {
                    model = "gemini-pro"; // 預設模型
                }
            }
            status.append("• 目前使用的模型：").append(model).append("\n");
        } else {
            status.append("• 偏好的 AI 提供者：未設定（預設：OpenAI）\n");
            status.append("• 目前使用的模型：gpt-4o\n");
        }
        
        // 偏好的預設翻譯語言
        String preferredLanguage = userProfile.getPreferredLanguage();
        if (preferredLanguage != null && !preferredLanguage.isEmpty()) {
            status.append("• 預設翻譯語言：").append(LanguageUtils.toChineseName(preferredLanguage)).append("\n");
        } else {
            status.append("• 預設翻譯語言：未設定（預設：英文）\n");
        }
        
        // 偏好的中文翻譯目標語言
        String preferredChineseTargetLanguage = userProfile.getPreferredChineseTargetLanguage();
        if (preferredChineseTargetLanguage != null && !preferredChineseTargetLanguage.isEmpty()) {
            status.append("• 中文翻譯的預設目標語言：").append(LanguageUtils.toChineseName(preferredChineseTargetLanguage)).append("\n");
        } else {
            status.append("• 中文翻譯的預設目標語言：未設定（預設：英文）\n");
        }
        
        // 翻譯統計
        status.append("\n翻譯統計：\n");
        status.append("• 文字翻譯：").append(userProfile.getTextTranslations()).append(" 次\n");
        status.append("• 圖片翻譯：").append(userProfile.getImageTranslations()).append(" 次\n");
        status.append("• 總翻譯次數：").append(userProfile.getTotalTranslations()).append(" 次\n");
        
        // 最近使用的語言
        List<String> recentLanguages = userProfile.getRecentLanguagesList();
        if (!recentLanguages.isEmpty()) {
            status.append("\n最近使用的語言：\n");
            for (int i = 0; i < recentLanguages.size(); i++) {
                String lang = recentLanguages.get(i);
                status.append("• ").append(LanguageUtils.toChineseName(lang)).append(" (").append(lang).append(")\n");
            }
        }
        
        // 用戶資訊
        status.append("\n用戶資訊：\n");
        status.append("• 用戶 ID：").append(userProfile.getUserId().substring(Math.max(0, userProfile.getUserId().length() - 6))).append("\n");
        if (userProfile.getDisplayName() != null && !userProfile.getDisplayName().isEmpty()) {
            status.append("• 用戶名稱：").append(userProfile.getDisplayName()).append("\n");
        }
        if (userProfile.getFirstInteractionAt() != null) {
            status.append("• 首次使用時間：").append(userProfile.getFirstInteractionAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        }
        if (userProfile.getLastInteractionAt() != null) {
            status.append("• 上次使用時間：").append(userProfile.getLastInteractionAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        }
        
        return status.toString();
    }
    
    /**
     * 獲取默認的 AI 提供者
     * 
     * @return 默認的 AI 提供者名稱
     */
    public String getDefaultProvider() {
        return appConfig.getDefaultAiProvider();
    }
    
    /**
     * 設置用戶偏好的 AI 模型
     * 
     * @param userId 用戶 ID
     * @param modelName 模型名稱
     * @return 設置結果消息
     */
    public String setPreferredModel(String userId, String modelName) {
        UserProfile userProfile = ensureUserProfileExists(userId);
        String currentProvider = userProfile.getPreferredAiProvider();
        
        // 檢查模型屬於哪個提供者
        boolean isOpenAiModel = false;
        boolean isGeminiModel = false;
        
        // 簡單的檢查邏輯，可以根據實際情況擴充
        if (modelName.toLowerCase().contains("gpt") || modelName.toLowerCase().startsWith("o")) {
            isOpenAiModel = true;
        } else if (modelName.toLowerCase().contains("gemini")) {
            isGeminiModel = true;
        }
        
        String oldModel;
        String newProvider = currentProvider;
        
        // 根據模型類型設置相應的提供者和模型
        if (isOpenAiModel) {
            oldModel = userProfile.getOpenaiPreferredModel();
            userProfile.setOpenaiPreferredModel(modelName);
            newProvider = "openai";
        } else if (isGeminiModel) {
            oldModel = userProfile.getGeminiPreferredModel();
            userProfile.setGeminiPreferredModel(modelName);
            newProvider = "gemini";
        } else {
            // 如果無法確定模型類型，則根據當前提供者設置
            if ("openai".equals(currentProvider)) {
                oldModel = userProfile.getOpenaiPreferredModel();
                userProfile.setOpenaiPreferredModel(modelName);
            } else {
                oldModel = userProfile.getGeminiPreferredModel();
                userProfile.setGeminiPreferredModel(modelName);
            }
        }
        
        // 如果提供者發生變化，更新提供者設置
        if (!newProvider.equals(currentProvider)) {
            userProfile.setPreferredAiProvider(newProvider);
        }
        
        userProfileRepository.save(userProfile);
        
        StringBuilder result = new StringBuilder();
        if (oldModel != null && !oldModel.isEmpty() && oldModel.equals(modelName)) {
            result.append("已設置 AI 模型為 ").append(modelName);
        } else if (oldModel != null && !oldModel.isEmpty()) {
            result.append("已將 ").append(newProvider).append(" 模型從 ").append(oldModel).append(" 更改為 ").append(modelName);
        } else {
            result.append("已設置 ").append(newProvider).append(" 模型為 ").append(modelName);
        }
        
        // 如果提供者發生變化，添加提示信息
        if (!newProvider.equals(currentProvider)) {
            result.append("\n同時已將 AI 提供者設置為 ").append(newProvider);
        }
        
        return result.toString();
    }
}
