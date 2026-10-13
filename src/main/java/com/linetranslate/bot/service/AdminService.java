package com.linetranslate.bot.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;

import com.linetranslate.bot.config.AppConfig;
import com.linetranslate.bot.config.OpenRouterConfig;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.ai.AiModelCatalog;
import com.linetranslate.bot.service.ai.AiModelPage;
import com.linetranslate.bot.service.line.LineUserProfileService;
import com.linetranslate.bot.service.preference.UserPreferences;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.settings.InvalidRuntimeSettingException;
import com.linetranslate.bot.service.settings.RuntimeSettingKey;
import com.linetranslate.bot.service.settings.RuntimeSettings;
import com.linetranslate.bot.service.settings.RuntimeSettingsModule;
import com.linetranslate.bot.service.settings.RuntimeSettingsPersistenceException;
import com.linetranslate.bot.service.usage.AiUsageAccountingModule;
import com.linetranslate.bot.service.usage.UsageContentKind;
import com.linetranslate.bot.service.usage.UsageQuery;
import com.linetranslate.bot.service.usage.UsageReportRenderer;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class AdminService {

    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Taipei");

    @Value("${admin.users:}")
    private List<String> adminUsers;
    
    // 測試模式，避免訊息真的發送給客戶
    @Value("${APP_BROADCAST_TEST_MODE:false}")
    private boolean broadcastTestMode;

    private final TranslationRecordRepository translationRecordRepository;
    private final UserProfileRepository userProfileRepository;
    private final MessagingApiClient messagingApiClient;
    private final DateTimeFormatter dateTimeFormatter;
    private final OpenRouterConfig openRouterConfig;
    private final AiModelCatalog modelCatalog;
    private final LineUserProfileService lineUserProfileService;
    private final UserPreferencesModule userPreferencesModule;
    private final RuntimeSettingsModule runtimeSettingsModule;
    private final AiUsageAccountingModule usageAccountingModule;
    private final UsageReportRenderer usageReportRenderer;
    
    @Autowired
    public AdminService(
            TranslationRecordRepository translationRecordRepository,
            UserProfileRepository userProfileRepository,
            MessagingApiClient messagingApiClient,
            OpenRouterConfig openRouterConfig,
            AiModelCatalog modelCatalog,
            LineUserProfileService lineUserProfileService,
            UserPreferencesModule userPreferencesModule,
            RuntimeSettingsModule runtimeSettingsModule,
            AiUsageAccountingModule usageAccountingModule,
            UsageReportRenderer usageReportRenderer) {
        this.translationRecordRepository = translationRecordRepository;
        this.userProfileRepository = userProfileRepository;
        this.messagingApiClient = messagingApiClient;
        this.openRouterConfig = openRouterConfig;
        this.modelCatalog = modelCatalog;
        this.lineUserProfileService = lineUserProfileService;
        this.userPreferencesModule = userPreferencesModule;
        this.runtimeSettingsModule = runtimeSettingsModule;
        this.usageAccountingModule = usageAccountingModule;
        this.usageReportRenderer = usageReportRenderer;
        this.dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    }

    /** Compatibility constructor for focused SDK contract tests. */
    public AdminService(
            TranslationRecordRepository translationRecordRepository,
            UserProfileRepository userProfileRepository,
            MessagingApiClient messagingApiClient,
            AppConfig appConfig,
            OpenRouterConfig openRouterConfig,
            AiModelCatalog modelCatalog,
            LineUserProfileService lineUserProfileService,
            UserPreferencesModule userPreferencesModule) {
        this(translationRecordRepository, userProfileRepository, messagingApiClient,
                openRouterConfig, modelCatalog, lineUserProfileService,
                userPreferencesModule, null, null, null);
    }

    /**
     * 檢查用戶是否是管理員
     *
     * @param userId 用戶 ID
     * @return 是否是管理員
     */
    public boolean isAdmin(String userId) {
        return adminUsers.contains(userId);
    }
    
    /**
     * 獲取管理員用戶列表
     *
     * @return 管理員用戶列表
     */
    public List<String> getAdminUsers() {
        return adminUsers;
    }
    
    /**
     * 添加管理員
     *
     * @param userId 要添加為管理員的用戶 ID
     * @return 操作結果訊息
     */
    public String addAdmin(String userId) {
        log.info("嘗試添加管理員: user={}", SafeLog.user(userId));
        
        // 檢查用戶 ID 是否有效
        Optional<UserProfile> userOpt = userProfileRepository.findByUserId(userId);
        if (!userOpt.isPresent()) {
            log.warn("找不到用戶: user={}", SafeLog.user(userId));
            return "添加管理員失敗：找不到用戶 " + userId;
        }
        
        // 檢查用戶是否已經是管理員
        if (adminUsers.contains(userId)) {
            log.info("用戶已經是管理員: user={}", SafeLog.user(userId));
            return "用戶 " + userId + " 已經是管理員";
        }
        
        // 添加到管理員列表
        adminUsers.add(userId);
        log.info("成功添加管理員: user={}", SafeLog.user(userId));
        
        UserProfile user = userOpt.get();
        String displayName = user.getDisplayName() != null ? user.getDisplayName() : "用戶" + userId.substring(Math.max(0, userId.length() - 6));
        
        return "成功添加管理員：" + displayName + " (ID: " + userId + ")";
    }
    
    /**
     * 移除管理員
     *
     * @param userId 要移除管理員權限的用戶 ID
     * @return 操作結果訊息
     */
    public String removeAdmin(String userId) {
        log.info("嘗試移除管理員: user={}", SafeLog.user(userId));
        
        // 檢查用戶 ID 是否有效
        Optional<UserProfile> userOpt = userProfileRepository.findByUserId(userId);
        if (!userOpt.isPresent()) {
            log.warn("找不到用戶: user={}", SafeLog.user(userId));
            return "移除管理員失敗：找不到用戶 " + userId;
        }
        
        // 檢查用戶是否為管理員
        if (!adminUsers.contains(userId)) {
            log.info("用戶不是管理員: user={}", SafeLog.user(userId));
            return "用戶 " + userId + " 不是管理員";
        }
        
        // 檢查是否為最後一個管理員
        if (adminUsers.size() <= 1) {
            log.warn("無法移除最後一個管理員");
            return "移除管理員失敗：無法移除最後一個管理員";
        }
        
        // 從管理員列表中移除
        adminUsers.remove(userId);
        log.info("成功移除管理員: user={}", SafeLog.user(userId));
        
        UserProfile user = userOpt.get();
        String displayName = user.getDisplayName() != null ? user.getDisplayName() : "用戶" + userId.substring(Math.max(0, userId.length() - 6));
        
        return "成功移除管理員：" + displayName + " (ID: " + userId + ")";
    }

    /**
     * 向所有用戶廣播消息
     * 
     * @param message 消息內容
     * @return 嘗試、送達與失敗的用戶數；測試模式下模擬發送計為送達
     */
    public BroadcastResult broadcastMessage(String message) {
        // 獲取所有用戶
        List<UserProfile> allUsers = userProfileRepository.findAll();

        // 過濾掉無效的用戶 ID
        List<UserProfile> validUsers = allUsers.stream()
                .filter(user -> user.getUserId() != null && !user.getUserId().isEmpty())
                .collect(Collectors.toList());

        int sentCount = 0;
        int failedCount = 0;
        TextMessage textMessage = new TextMessage(message);

        for (UserProfile user : validUsers) {
            try {
                if (!broadcastTestMode) {
                    // 使用 LINE Messaging API 實際發送消息；測試模式不實際發送
                    PushMessageRequest pushMessage = new PushMessageRequest.Builder(
                            user.getUserId(), List.of(textMessage))
                            .notificationDisabled(false)
                            .build();
                    messagingApiClient.pushMessage(UUID.randomUUID(), pushMessage).get();
                }
                sentCount++;
            } catch (Exception e) {
                failedCount++;
                log.warn("廣播消息發送失敗: user={}, failure={}",
                        SafeLog.user(user.getUserId()), SafeLog.failure(e));
            }
        }

        log.info("廣播消息完成: testMode={}, attempted={}, sent={}, failed={}",
                broadcastTestMode, validUsers.size(), sentCount, failedCount);
        return new BroadcastResult(validUsers.size(), sentCount, failedCount, broadcastTestMode);
    }
    
    /**
     * 獲取最近活躍的用戶
     * 
     * @param limit 限制數量
     * @return 用戶列表
     */
    public List<Map<String, Object>> getRecentUsers(int limit) {
        log.info("獲取最近活躍的用戶，限制數量: {}", limit);
        
        // 按最後互動時間排序獲取用戶；尚未完成翻譯（lastInteractionAt 為空）的用戶以首次互動時間排序。
        // 兩組用戶各由資料庫排序並只取前 limit 筆，合併後的前 limit 筆即為全體的前 limit 筆。
        List<UserProfile> candidates = new ArrayList<>(userProfileRepository
                .findByLastInteractionAtNotNullOrderByLastInteractionAtDesc(Limit.of(limit)));
        candidates.addAll(userProfileRepository
                .findByLastInteractionAtIsNullOrderByFirstInteractionAtDesc(Limit.of(limit)));
        List<UserProfile> recentUsers = candidates.stream()
                .sorted(Comparator.comparing(
                        (UserProfile u) -> u.getLastInteractionAt() != null ? u.getLastInteractionAt() : u.getFirstInteractionAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .collect(Collectors.toList());
        
        // 轉換為 Map 列表
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserProfile user : recentUsers) {
            Map<String, Object> userMap = new HashMap<>();
            userMap.put("userId", user.getUserId());
            userMap.put("displayName", user.getDisplayName() != null ? user.getDisplayName() : "用戶" + user.getUserId().substring(0, 6));
            LocalDateTime lastActive = user.getLastInteractionAt() != null ? user.getLastInteractionAt() : user.getFirstInteractionAt();
            userMap.put("lastActiveTime", lastActive != null ? dateTimeFormatter.format(lastActive) : "N/A");
            userMap.put("totalTranslations", user.getTotalTranslations());
            result.add(userMap);
        }
        
        return result;
    }
    
    /**
     * 獲取用戶詳細信息
     * 
     * @param userId 用戶ID
     * @return 用戶詳細信息
     */
    public Map<String, Object> getUserInfo(String userId) {
        log.info("獲取用戶詳細信息: user={}", SafeLog.user(userId));
        
        Optional<UserProfile> userOpt = userProfileRepository.findByUserId(userId);
        if (!userOpt.isPresent()) {
            log.warn("找不到用戶: user={}", SafeLog.user(userId));
            return null;
        }
        
        UserProfile user = userOpt.get();
        UserPreferences preferences = userPreferencesModule.resolve(user);
        Map<String, Object> userInfo = new HashMap<>();
        
        // 基本信息
        userInfo.put("userId", user.getUserId());
        userInfo.put("displayName", user.getDisplayName() != null ? user.getDisplayName() : "用戶" + user.getUserId().substring(0, 6));
        userInfo.put("registrationTime", user.getFirstInteractionAt() != null ? dateTimeFormatter.format(user.getFirstInteractionAt()) : "N/A");
        userInfo.put("lastActiveTime", user.getLastInteractionAt() != null ? dateTimeFormatter.format(user.getLastInteractionAt()) : "N/A");
        
        // 統計信息
        userInfo.put("translationCount", user.getTotalTranslations());
        userInfo.put("textTranslationCount", user.getTextTranslations());
        userInfo.put("imageTranslationCount", user.getImageTranslations());
        
        // 用戶設置
        userInfo.put("preferredLanguage", preferences.targetLanguage());
        userInfo.put("preferredChineseTargetLanguage", preferences.chineseTargetLanguage());
        userInfo.put("aiProvider", "openrouter");
        userInfo.put("preferredModel", valueOrUnavailable(preferences.model()));
        
        return userInfo;
    }

    private static String valueOrUnavailable(String value) {
        return value == null || value.isBlank() ? "N/A" : value;
    }
    
    /**
     * 獲取系統統計信息
     *
     * @return 統計信息字符串
     */
    public String getSystemStats() {
        log.info("獲取系統統計信息");
        
        // 統計基本數據
        long totalUsers = userProfileRepository.count();
        long totalTranslations = translationRecordRepository.count();
        long imageTranslations = translationRecordRepository.countByIsImageTranslation(true);
        long textTranslations = totalTranslations - imageTranslations;
        
        // 獲取過去24小時的活躍用戶
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        long activeUsersLast24h = userProfileRepository.countByLastInteractionAtAfter(yesterday);
        
        // 統計每個 AI 提供商的使用情況
        long openRouterCount = translationRecordRepository.countByAiProvider("openrouter");
        
        // 統計過去7天每天的翻譯量（由資料庫依日期分組計數並排序）
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        List<TranslationRecordRepository.KeyedCount> dailyTranslations = translationRecordRepository
                .countPerDayByCreatedAtBetween(weekAgo, LocalDateTime.now(), persistenceTimeZone());
        
        // 格式化輸出
        StringBuilder stats = new StringBuilder();
        stats.append("系統統計\n");
        stats.append("-------------------\n");
        stats.append("總用戶數: ").append(totalUsers).append("\n");
        stats.append("總翻譯次數: ").append(totalTranslations).append("\n");
        stats.append("  文字翻譯: ").append(textTranslations).append("\n");
        stats.append("  圖片翻譯: ").append(imageTranslations).append("\n");
        stats.append("過去24小時活躍用戶: ").append(activeUsersLast24h).append("\n");
        stats.append("\nAI 提供商使用情況\n");
        stats.append("-------------------\n");
        
        double openRouterRatio = totalTranslations == 0 ? 0 : (double) openRouterCount / totalTranslations * 100;
        stats.append("OpenRouter: ").append(openRouterCount).append(" (")
                .append(String.format("%.1f%%", openRouterRatio)).append(")\n");
        
        stats.append("\n過去7天翻譯量\n");
        stats.append("-------------------\n");
        
        // 按日期排序顯示每天的翻譯量
        for (TranslationRecordRepository.KeyedCount day : dailyTranslations) {
            stats.append(day.key()).append(": ").append(day.count()).append("\n");
        }
        
        return stats.toString();
    }

    /**
     * Spring Data 以 JVM 預設時區把 LocalDateTime 存成時間點，依日期分組時須使用同一時區，
     * 才與 LocalDateTime 的日期一致。固定位移的時區轉成 MongoDB 接受的 "+hh:mm" 格式。
     */
    private static String persistenceTimeZone() {
        ZoneId zone = ZoneId.systemDefault().normalized();
        if (zone instanceof ZoneOffset offset) {
            return offset.getTotalSeconds() == 0 ? "+00:00" : offset.getId();
        }
        return zone.getId();
    }
    
    /**
     * 獲取今日統計信息
     *
     * @return 今日統計信息
     */
    public String getTodayStats() {
        // 獲取今日開始和結束時間
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime todayEnd = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);

        // 由資料庫彙總今日翻譯記錄：總數、圖片翻譯數與平均處理時間
        Optional<TranslationRecordRepository.TranslationSummary> todaySummary =
                translationRecordRepository.summarizeByCreatedAtBetween(todayStart, todayEnd);

        // 計算統計數據
        long totalTranslations = todaySummary.map(TranslationRecordRepository.TranslationSummary::total).orElse(0L);
        long imageTranslations = todaySummary.map(TranslationRecordRepository.TranslationSummary::imageTranslations).orElse(0L);
        long textTranslations = totalTranslations - imageTranslations;

        // 計算平均處理時間
        double avgProcessingTime = todaySummary
                .map(TranslationRecordRepository.TranslationSummary::averageProcessingTimeMs)
                .orElse(0.0);

        // 獲取 AI 提供者使用統計
        Map<String, Long> providerStats = new HashMap<>();
        for (TranslationRecordRepository.KeyedCount provider
                : translationRecordRepository.countPerAiProviderByCreatedAtBetween(todayStart, todayEnd)) {
            providerStats.put(provider.key(), provider.count());
        }

        // 生成統計信息字符串
        StringBuilder statsBuilder = new StringBuilder();
        statsBuilder.append("【今日統計】\n\n");

        statsBuilder.append("今日總翻譯次數：").append(totalTranslations).append("\n");
        statsBuilder.append("文字翻譯：").append(textTranslations).append(" 次\n");
        statsBuilder.append("圖片翻譯：").append(imageTranslations).append(" 次\n");
        statsBuilder.append("平均處理時間：").append(String.format("%.2f", avgProcessingTime / 1000)).append(" 秒\n\n");

        statsBuilder.append("【AI 提供者使用情況】\n");
        for (Map.Entry<String, Long> entry : providerStats.entrySet()) {
            statsBuilder.append(entry.getKey()).append("：").append(entry.getValue()).append(" 次\n");
        }
        
        return statsBuilder.toString();
    }
    
    /**
     * 獲取系統配置信息
     *
     * @return 系統配置信息字符串
     */
    public String getSystemConfig() {
        RuntimeSettings runtime = runtimeSettingsModule.current();
        StringBuilder configBuilder = new StringBuilder();
        configBuilder.append("⚙️ 系統配置信息\n\n");
        
        // 翻譯相關配置
        configBuilder.append("【翻譯設定】\n");
        configBuilder.append("• 中文翻譯默認目標語言: ").append(runtime.defaultTargetLanguageForChinese()).append("\n");
        configBuilder.append("• 其他語言翻譯默認目標語言: ").append(runtime.defaultTargetLanguageForOthers()).append("\n");
        configBuilder.append("• OCR 功能: ").append(runtime.ocrEnabled() ? "已啟用" : "已禁用").append("\n\n");
        configBuilder.append("• 圖片短網址直接輸出: ")
                .append(runtime.shortUrlEnabled() ? "已啟用" : "已禁用")
                .append("\n\n");
        
        configBuilder.append("【OpenRouter 設定】\n");
        configBuilder.append("• 默認模型: ").append(runtime.openRouterDefaultModel()).append("\n");
        configBuilder.append("• Catalog 模型數: ").append(modelCatalog.list("", 0).total()).append("\n");
        configBuilder.append("• API 狀態: ").append(openRouterConfig.getApiKey() != null
                && !openRouterConfig.getApiKey().isBlank() ? "已配置" : "未配置").append("\n\n");
        
        // 管理員設定
        configBuilder.append("【管理員設定】\n");
        configBuilder.append("• 管理員數量: ").append(adminUsers.size()).append("\n");
        configBuilder.append("• 管理員列表: \n");
        
        for (String adminId : adminUsers) {
            Optional<UserProfile> userOpt = userProfileRepository.findByUserId(adminId);
            String displayName = userOpt.map(UserProfile::getDisplayName).orElse("未知用戶");
            configBuilder.append("  - ").append(displayName).append(" (ID: ").append(adminId).append(")\n");
        }
        
        return configBuilder.toString();
    }
    
    /**
     * 設置中文翻譯默認目標語言
     *
     * @param language 語言代碼
     * @return 操作結果訊息
     */
    public String setDefaultTargetLanguageForChinese(String language, String operatorId) {
        return updateSetting(
                RuntimeSettingKey.DEFAULT_CHINESE_TARGET_LANGUAGE,
                language,
                operatorId,
                "✅ 已將中文翻譯默認目標語言設置為: " + language);
    }
    
    /**
     * 設置其他語言翻譯默認目標語言
     *
     * @param language 語言代碼
     * @return 操作結果訊息
     */
    public String setDefaultTargetLanguageForOthers(String language, String operatorId) {
        return updateSetting(
                RuntimeSettingKey.DEFAULT_OTHER_TARGET_LANGUAGE,
                language,
                operatorId,
                "✅ 已將其他語言翻譯默認目標語言設置為: " + language);
    }
    
    public String setOpenRouterDefaultModel(String model, String operatorId) {
        return updateSetting(
                RuntimeSettingKey.OPENROUTER_DEFAULT_MODEL,
                model,
                operatorId,
                "✅ 已將 OpenRouter 默認模型設置為: " + model);
    }

    public AiModelPage getAvailableModels(String query, int limit) {
        return modelCatalog.list(query, limit);
    }

    public String getOpenRouterDefaultModel() {
        return runtimeSettingsModule.current().openRouterDefaultModel();
    }
    
    /**
     * 設置 OCR 功能開關
     *
     * @param enabled 是否啟用 OCR
     * @return 操作結果訊息
     */
    public String setOcrEnabled(String enabled, String operatorId) {
        try {
            RuntimeSettings updated = runtimeSettingsModule.update(
                    RuntimeSettingKey.OCR_ENABLED, enabled, operatorId);
            return "✅ 已" + (updated.ocrEnabled() ? "啟用" : "禁用") + " OCR 功能";
        } catch (InvalidRuntimeSettingException failure) {
            return "❌ 無效的設定值，請確認格式與可用選項";
        } catch (RuntimeSettingsPersistenceException failure) {
            log.error("持久化 OCR 設定失敗: failure={}", SafeLog.failure(failure));
            return "❌ 設置未能保存，請稍後再試";
        }
    }

    public String setShortUrlEnabled(String enabled, String operatorId) {
        try {
            RuntimeSettings updated = runtimeSettingsModule.update(
                    RuntimeSettingKey.SHORT_URL_ENABLED, enabled, operatorId);
            return "✅ 已" + (updated.shortUrlEnabled() ? "啟用" : "禁用")
                    + "圖片短網址直接輸出";
        } catch (InvalidRuntimeSettingException failure) {
            return "❌ 無效的設定值，請使用 on 或 off";
        } catch (RuntimeSettingsPersistenceException failure) {
            log.error("持久化短網址設定失敗: failure={}", SafeLog.failure(failure));
            return "❌ 設置未能保存，請稍後再試";
        }
    }

    private String updateSetting(
            RuntimeSettingKey key,
            String value,
            String operatorId,
            String successMessage) {
        try {
            runtimeSettingsModule.update(key, value, operatorId);
            return successMessage;
        } catch (InvalidRuntimeSettingException failure) {
            return "❌ 無效的設定值，請確認格式與可用選項";
        } catch (RuntimeSettingsPersistenceException failure) {
            log.error("持久化 runtime 設定失敗: key={}, failure={}",
                    key, SafeLog.failure(failure));
            return "❌ 設置未能保存，請稍後再試";
        }
    }
    
    /**
 * 獲取當前月份的 API 使用量和費用統計
 *
 * @return API 使用量和費用統計信息
 */
public String getApiUsageStats() {
    return getApiUsageStatsByMonth(YearMonth.now(REPORTING_ZONE).toString());
}

/**
 * 獲取指定月份的 API 使用量和費用統計
 *
 * @param month 月份，格式為 YYYY-MM
 * @return API 使用量和費用統計信息
 */
public String getApiUsageStatsByMonth(String month) {
    try {
        YearMonth parsed = YearMonth.parse(month);
        return renderUsage(month, UsageQuery.forMonth(parsed, REPORTING_ZONE));
    } catch (java.time.format.DateTimeParseException failure) {
        return "❌ 月份格式無效，請使用 YYYY-MM";
    }
}

/**
 * 按 AI 提供者獲取 API 使用量和費用統計
 *
 * @param provider AI 提供者維度
 * @return API 使用量和費用統計信息
 */
public String getApiUsageStatsByProvider(String provider) {
    if (provider == null || !provider.matches("(?i)^[a-z0-9._-]{1,64}$")) {
        return "❌ 無效的 AI 提供者名稱。";
    }
    return renderUsage(
            provider + " 提供者",
            UsageQuery.all().withProvider(provider));
}

/**
 * 獲取所有時間的 API 使用量和費用摘要
 *
 * @return API 使用量和費用摘要信息
 */
public String getApiUsageSummary() {
    return renderUsage("全部期間", UsageQuery.all());
}

public String getApiUsageStatsByDay(String day) {
    try {
        LocalDate parsed = LocalDate.parse(day);
        return renderUsage(day, UsageQuery.forDay(parsed, REPORTING_ZONE));
    } catch (java.time.format.DateTimeParseException failure) {
        return "❌ 日期格式無效，請使用 YYYY-MM-DD";
    }
}

public String getApiUsageStatsByModel(String model) {
    if (model == null || model.isBlank() || model.length() > 128) {
        return "❌ 無效的模型名稱";
    }
    return renderUsage(model + " 模型", UsageQuery.all().withModel(model));
}

public String getApiUsageStatsByContentKind(String kind) {
    UsageContentKind contentKind;
    if ("text".equalsIgnoreCase(kind) || "文字".equals(kind)) {
        contentKind = UsageContentKind.TEXT;
    } else if ("image".equalsIgnoreCase(kind) || "圖片".equals(kind)) {
        contentKind = UsageContentKind.IMAGE;
    } else {
        return "❌ 無效的類型，請使用 text 或 image";
    }
    return renderUsage(
            contentKind == UsageContentKind.TEXT ? "文字" : "圖片",
            UsageQuery.all().withContentKind(contentKind));
}

private String renderUsage(String title, UsageQuery query) {
    try {
        return usageReportRenderer.render(title, usageAccountingModule.report(query));
    } catch (RuntimeException failure) {
        log.error("獲取 usage accounting 報表失敗: failure={}", SafeLog.failure(failure));
        return "❌ 獲取 API 使用量和費用統計失敗，請稍後再試";
    }
}

/**
 * 設置用戶的顯示名稱
 *
 * @param userId 用戶 ID
 * @param displayName 新的顯示名稱
 * @return 操作結果訊息
 */
public String setUserDisplayName(String userId, String displayName) {
    return lineUserProfileService.setUserDisplayName(userId, displayName);
}

}
