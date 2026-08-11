package com.linetranslate.bot.service.line;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.linecorp.bot.messaging.model.Message;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.AdminService;
import com.linetranslate.bot.service.ai.AiModelPage;
import com.linetranslate.bot.service.line.intent.AdminIntent;

import lombok.extern.slf4j.Slf4j;

/**
 * Deep Module that authorizes and executes validated administrator intents.
 * Authorization always precedes any sensitive service call.
 */
@Service
@Slf4j
public class AdminInteractionModule {

    private final AdminService adminService;
    private final AdminCardRenderer renderer;

    public AdminInteractionModule(AdminService adminService, AdminCardRenderer renderer) {
        this.adminService = adminService;
        this.renderer = renderer;
    }

    public Message execute(String userId, AdminIntent intent) {
        if (!adminService.isAdmin(userId)) {
            return renderer.accessDenied();
        }
        if (intent == null) {
            return renderer.error("未知的管理員命令", "無法識別此操作。請返回管理面板選擇功能。");
        }

        return switch (intent.action()) {
            case DASHBOARD -> renderer.dashboard();
            case VERIFY -> renderer.success("管理員驗證", "您是已授權的管理員。");
            case BROADCAST -> broadcast(userId, intent.value());
            case STATS -> renderer.info("系統統計", adminService.getSystemStats());
            case TODAY -> renderer.info("今日狀態", adminService.getTodayStats());
            case USERS -> users(userId);
            case USER -> user(userId, intent.value());
            case NICKNAME -> renderer.info(
                    "更新用戶暱稱",
                    adminService.setUserDisplayName(intent.value(), intent.secondary()));
            case MODELS -> models(intent.value());
            case CONFIG_SHOW -> renderer.card(
                    "系統設定",
                    systemConfig(),
                    List.of(
                            new AdminCardRenderer.AdminCardAction("選擇預設模型", "/admin models"),
                            new AdminCardRenderer.AdminCardAction("返回管理面板", "/admin")));
            case CONFIG_C2LANG -> configResult(
                    adminService.setDefaultTargetLanguageForChinese(intent.value(), userId));
            case CONFIG_LANGUAGE -> configResult(
                    adminService.setDefaultTargetLanguageForOthers(intent.value(), userId));
            case CONFIG_MODEL -> configResult(adminService.setOpenRouterDefaultModel(intent.value(), userId));
            case CONFIG_OCR -> configResult(adminService.setOcrEnabled(intent.value(), userId));
            case USAGE_CURRENT_MONTH -> renderer.info("本月 API 用量", adminService.getApiUsageStats());
            case USAGE_DAY -> renderer.info("每日 API 用量", adminService.getApiUsageStatsByDay(intent.value()));
            case USAGE_MONTH -> renderer.info(
                    "指定月份 API 用量", adminService.getApiUsageStatsByMonth(intent.value()));
            case USAGE_PROVIDER -> renderer.info(
                    "提供者 API 用量", adminService.getApiUsageStatsByProvider(intent.value()));
            case USAGE_MODEL -> renderer.info(
                    "模型 API 用量", adminService.getApiUsageStatsByModel(intent.value()));
            case USAGE_TYPE -> renderer.info(
                    "內容類型 API 用量", adminService.getApiUsageStatsByContentKind(intent.value()));
            case USAGE_SUMMARY -> renderer.info("API 用量總覽", adminService.getApiUsageSummary());
            case ADD_ADMIN -> renderer.info("新增管理員", adminService.addAdmin(intent.value()));
            case REMOVE_ADMIN -> renderer.info("移除管理員", adminService.removeAdmin(intent.value()));
            case UNKNOWN_CONFIG -> renderer.error(
                    "未知的設定命令",
                    "無法識別：" + intent.value() + "\n請使用 /admin config 查看可用指令。");
            case UNKNOWN_USAGE -> renderer.error(
                    "未知的用量命令",
                    "無法識別：" + intent.value()
                            + "\n請使用 day、month、provider、model、type 或 summary。");
            case UNKNOWN -> renderer.error(
                    "未知的管理員命令",
                    "無法識別：" + intent.value() + "\n請返回管理面板選擇功能。");
            case INVALID -> invalid(intent.problem());
        };
    }

    private Message broadcast(String userId, String message) {
        log.info("廣播命令: user={}, content={}", SafeLog.user(userId), SafeLog.content(message));
        try {
            int count = adminService.broadcastMessage(message);
            return renderer.success("廣播完成", "已向 " + count + " 個用戶發送訊息。\n\n" + message);
        } catch (Exception exception) {
            log.error("廣播訊息失敗: failure={}", SafeLog.failure(exception));
            return renderer.error("廣播失敗", "廣播訊息未能送出，請稍後再試。");
        }
    }

    private Message users(String userId) {
        log.info("用戶列表命令: user={}", SafeLog.user(userId));
        try {
            List<Map<String, Object>> users = adminService.getRecentUsers(10);
            if (users == null || users.isEmpty()) {
                return renderer.info("最近活躍用戶", "目前沒有可顯示的用戶資料。");
            }
            StringBuilder body = new StringBuilder();
            for (int index = 0; index < users.size(); index++) {
                Map<String, Object> user = users.get(index);
                body.append(index + 1).append(". ")
                        .append(value(user, "displayName")).append(" (")
                        .append(maskUserId(value(user, "userId"))).append(")\n")
                        .append("最後活動：").append(value(user, "lastActiveTime")).append("\n");
                if (index < users.size() - 1) {
                    body.append("\n");
                }
            }
            body.append("\n使用 /admin user [完整 ID] 查看詳細資訊。");
            return renderer.info("最近活躍用戶", body.toString());
        } catch (Exception exception) {
            log.error("獲取用戶列表失敗: failure={}", SafeLog.failure(exception));
            return renderer.error("用戶列表失敗", "無法取得用戶列表，請稍後再試。");
        }
    }

    private Message user(String adminId, String targetUserId) {
        log.info("查詢用戶命令: admin={}, target={}",
                SafeLog.user(adminId), SafeLog.user(targetUserId));
        try {
            Map<String, Object> userInfo = adminService.getUserInfo(targetUserId);
            if (userInfo == null) {
                return renderer.error("找不到用戶", "查無指定的用戶資料。");
            }
            String body = "用戶 ID：" + value(userInfo, "userId") + "\n"
                    + "顯示名稱：" + value(userInfo, "displayName") + "\n"
                    + "註冊時間：" + value(userInfo, "registrationTime") + "\n"
                    + "最後活動：" + value(userInfo, "lastActiveTime") + "\n\n"
                    + "翻譯次數：" + value(userInfo, "translationCount") + "\n"
                    + "圖片翻譯：" + value(userInfo, "imageTranslationCount") + "\n\n"
                    + "預設語言：" + value(userInfo, "preferredLanguage") + "\n"
                    + "中文目標語言：" + value(userInfo, "preferredChineseTargetLanguage") + "\n"
                    + "AI 提供者：" + value(userInfo, "aiProvider") + "\n"
                    + "模型：" + value(userInfo, "preferredModel");
            return renderer.info("用戶詳細資訊", body);
        } catch (Exception exception) {
            log.error("獲取用戶資訊失敗: failure={}", SafeLog.failure(exception));
            return renderer.error("用戶資訊失敗", "無法取得用戶資訊，請稍後再試。");
        }
    }

    private String systemConfig() {
        return adminService.getSystemConfig()
                + "\n\n可用指令：\n"
                + "/admin config c2lang [lang]\n"
                + "/admin config lang [lang]\n"
                + "/admin config model [OpenRouter slug]\n"
                + "/admin config ocr [on|off]";
    }

    private Message models(String query) {
        try {
            AiModelPage page = adminService.getAvailableModels(query, 8);
            return renderer.modelSelection(
                    page, query, adminService.getOpenRouterDefaultModel());
        } catch (RuntimeException exception) {
            log.error("取得 OpenRouter 模型列表失敗: failure={}", SafeLog.failure(exception));
            return renderer.error("模型列表失敗", "無法取得模型列表，請稍後再試。");
        }
    }

    private Message configResult(String result) {
        return renderer.info("設定結果", result);
    }

    private Message invalid(AdminIntent.Problem problem) {
        if (problem == AdminIntent.Problem.BROADCAST_TOO_LONG) {
            return renderer.error("廣播訊息過長", "LINE 文字訊息最多可包含 5000 個字元。");
        }
        String message = switch (problem) {
            case BROADCAST_REQUIRED -> "請指定要廣播的訊息。例如：/admin broadcast 系統維護通知";
            case USER_REQUIRED -> "請指定要查詢的用戶 ID。例如：/admin user U123456789";
            case NICKNAME_FORMAT -> "格式：/admin nickname [用戶 ID] [新暱稱]";
            case NICKNAME_REQUIRED -> "請提供新暱稱。格式：/admin nickname [用戶 ID] [新暱稱]";
            case MODEL_QUERY_TOO_LONG -> "模型搜尋字最多 80 個字元。";
            case CONFIG_VALUE_REQUIRED -> "設定指令缺少參數。請使用 /admin config 查看格式。";
            case USAGE_DAY_REQUIRED ->
                    "請指定日期，格式為 YYYY-MM-DD。例如：/admin usage day 2026-08-11";
            case USAGE_MONTH_REQUIRED ->
                    "請指定月份，格式為 YYYY-MM。例如：/admin usage month 2026-08";
            case USAGE_PROVIDER_REQUIRED ->
                    "請指定提供者維度。例如：/admin usage provider openrouter";
            case USAGE_MODEL_REQUIRED -> "請指定模型。例如：/admin usage model gpt-4o";
            case USAGE_TYPE_REQUIRED -> "請指定 text 或 image。例如：/admin usage type image";
            case ADD_ADMIN_REQUIRED ->
                    "請指定要新增的管理員用戶 ID。例如：/admin add U123456789";
            case REMOVE_ADMIN_REQUIRED ->
                    "請指定要移除的管理員用戶 ID。例如：/admin remove U123456789";
            case BROADCAST_TOO_LONG -> throw new IllegalStateException("Handled above");
        };
        return renderer.error("需要更多資料", message);
    }

    private static String value(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value == null || value.toString().isBlank() ? "未設定" : value.toString();
    }

    private static String maskUserId(String userId) {
        if (userId == null || userId.isBlank() || "未設定".equals(userId)) {
            return "未設定";
        }
        int visible = Math.min(6, userId.length());
        return userId.substring(0, visible) + "…";
    }
}
