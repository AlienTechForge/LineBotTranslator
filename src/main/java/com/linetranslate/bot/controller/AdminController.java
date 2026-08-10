package com.linetranslate.bot.controller;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.linecorp.bot.messaging.model.Message;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.AdminService;
import com.linetranslate.bot.service.line.AdminCardRenderer;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class AdminController {

    private final AdminService adminService;
    private final AdminCardRenderer cardRenderer;

    @Autowired
    public AdminController(AdminService adminService, AdminCardRenderer cardRenderer) {
        this.adminService = adminService;
        this.cardRenderer = cardRenderer;
    }

    public Message handleCommand(String userId, String command) {
        log.info("處理管理員命令: user={}, command={}",
                SafeLog.user(userId), SafeLog.content(command));

        if (!adminService.isAdmin(userId)) {
            return cardRenderer.accessDenied();
        }

        String normalizedCommand = command == null ? "" : command.trim();
        if (normalizedCommand.isEmpty()) {
            return cardRenderer.dashboard();
        }

        String[] parts = normalizedCommand.split("\\s+", 2);
        String subCommand = parts[0].toLowerCase(Locale.ROOT);
        String param = parts.length > 1 ? parts[1].trim() : "";

        return switch (subCommand) {
            case "help" -> cardRenderer.dashboard();
            case "isadmin" -> cardRenderer.success("管理員驗證", "您是已授權的管理員。");
            case "broadcast" -> {
                if (param.isEmpty()) {
                    yield inputError("請指定要廣播的訊息。例如：/admin broadcast 系統維護通知");
                }
                if (param.codePointCount(0, param.length()) > 5000) {
                    yield cardRenderer.error("廣播訊息過長", "LINE 文字訊息最多可包含 5000 個字元。");
                }
                yield handleBroadcastCommand(userId, param);
            }
            case "stats" -> cardRenderer.info("系統統計", adminService.getSystemStats());
            case "today" -> cardRenderer.info("今日狀態", adminService.getTodayStats());
            case "users" -> handleUsersCommand(userId);
            case "user" -> param.isEmpty()
                    ? inputError("請指定要查詢的用戶 ID。例如：/admin user U123456789")
                    : handleUserCommand(userId, param);
            case "nickname" -> handleNicknameCommand(param);
            case "config" -> handleConfigCommand(param);
            case "usage" -> handleUsageCommand(param);
            case "add" -> param.isEmpty()
                    ? inputError("請指定要新增的管理員用戶 ID。例如：/admin add U123456789")
                    : cardRenderer.info("新增管理員", adminService.addAdmin(param));
            case "remove" -> param.isEmpty()
                    ? inputError("請指定要移除的管理員用戶 ID。例如：/admin remove U123456789")
                    : cardRenderer.info("移除管理員", adminService.removeAdmin(param));
            default -> cardRenderer.error(
                    "未知的管理員命令",
                    "無法識別：" + subCommand + "\n請返回管理面板選擇功能。");
        };
    }

    private Message handleBroadcastCommand(String userId, String message) {
        log.info("廣播命令: user={}, content={}",
                SafeLog.user(userId), SafeLog.content(message));

        try {
            int count = adminService.broadcastMessage(message);
            return cardRenderer.success(
                    "廣播完成",
                    "已向 " + count + " 個用戶發送訊息。\n\n" + message);
        } catch (Exception e) {
            log.error("廣播訊息失敗: failure={}", SafeLog.failure(e));
            return cardRenderer.error("廣播失敗", "廣播訊息未能送出，請稍後再試。");
        }
    }

    private Message handleUsersCommand(String userId) {
        log.info("用戶列表命令: user={}", SafeLog.user(userId));

        try {
            List<Map<String, Object>> users = adminService.getRecentUsers(10);
            if (users == null || users.isEmpty()) {
                return cardRenderer.info("最近活躍用戶", "目前沒有可顯示的用戶資料。");
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
            return cardRenderer.info("最近活躍用戶", body.toString());
        } catch (Exception e) {
            log.error("獲取用戶列表失敗: failure={}", SafeLog.failure(e));
            return cardRenderer.error("用戶列表失敗", "無法取得用戶列表，請稍後再試。");
        }
    }

    private Message handleUserCommand(String adminId, String targetUserId) {
        log.info("查詢用戶命令: admin={}, target={}",
                SafeLog.user(adminId), SafeLog.user(targetUserId));

        try {
            Map<String, Object> userInfo = adminService.getUserInfo(targetUserId);
            if (userInfo == null) {
                return cardRenderer.error("找不到用戶", "查無指定的用戶資料。");
            }

            String body = "用戶 ID：" + value(userInfo, "userId") + "\n"
                    + "顯示名稱：" + value(userInfo, "displayName") + "\n"
                    + "註冊時間：" + value(userInfo, "registrationTime") + "\n"
                    + "最後活動：" + value(userInfo, "lastActiveTime") + "\n\n"
                    + "翻譯次數：" + value(userInfo, "translationCount") + "\n"
                    + "圖片翻譯：" + value(userInfo, "imageTranslationCount") + "\n\n"
                    + "預設語言：" + value(userInfo, "preferredLanguage") + "\n"
                    + "中文目標語言：" + value(userInfo, "preferredChineseTargetLanguage") + "\n"
                    + "AI 提供者：" + value(userInfo, "preferredAiProvider");
            return cardRenderer.info("用戶詳細資訊", body);
        } catch (Exception e) {
            log.error("獲取用戶資訊失敗: failure={}", SafeLog.failure(e));
            return cardRenderer.error("用戶資訊失敗", "無法取得用戶資訊，請稍後再試。");
        }
    }

    private Message handleNicknameCommand(String param) {
        if (param.isEmpty()) {
            return inputError("格式：/admin nickname [用戶 ID] [新暱稱]");
        }

        String[] parts = param.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            return inputError("請提供新暱稱。格式：/admin nickname [用戶 ID] [新暱稱]");
        }

        return cardRenderer.info(
                "更新用戶暱稱",
                adminService.setUserDisplayName(parts[0], parts[1].trim()));
    }

    private Message handleConfigCommand(String param) {
        if (param.isEmpty()) {
            String body = adminService.getSystemConfig()
                    + "\n\n可用指令：\n"
                    + "/admin config c2lang [lang]\n"
                    + "/admin config lang [lang]\n"
                    + "/admin config ai [openai|gemini]\n"
                    + "/admin config openai [model]\n"
                    + "/admin config gemini [model]\n"
                    + "/admin config ocr [on|off]";
            return cardRenderer.info("系統設定", body);
        }

        String[] parts = param.split("\\s+", 2);
        String subCommand = parts[0].toLowerCase(Locale.ROOT);
        String value = parts.length > 1 ? parts[1].trim() : "";
        if (value.isEmpty()) {
            return inputError("設定指令缺少參數。請使用 /admin config 查看格式。");
        }

        String result = switch (subCommand) {
            case "c2lang" -> adminService.setDefaultTargetLanguageForChinese(value);
            case "lang" -> adminService.setDefaultTargetLanguageForOthers(value);
            case "ai" -> adminService.setDefaultAiProvider(value);
            case "openai" -> adminService.setOpenAiDefaultModel(value);
            case "gemini" -> adminService.setGeminiDefaultModel(value);
            case "ocr" -> adminService.setOcrEnabled(isEnabled(value));
            default -> null;
        };

        if (result == null) {
            return cardRenderer.error(
                    "未知的設定命令",
                    "無法識別：" + subCommand + "\n請使用 /admin config 查看可用指令。");
        }
        return cardRenderer.info("設定結果", result);
    }

    private Message handleUsageCommand(String param) {
        if (param.isEmpty()) {
            return cardRenderer.info("本月 API 用量", adminService.getApiUsageStats());
        }

        String[] parts = param.split("\\s+", 2);
        String subCommand = parts[0].toLowerCase(Locale.ROOT);
        String value = parts.length > 1 ? parts[1].trim() : "";

        return switch (subCommand) {
            case "month" -> value.isEmpty()
                    ? inputError("請指定月份，格式為 YYYY-MM。例如：/admin usage month 2026-08")
                    : cardRenderer.info("指定月份 API 用量", adminService.getApiUsageStatsByMonth(value));
            case "provider" -> value.isEmpty()
                    ? inputError("請指定 openai 或 gemini。例如：/admin usage provider openai")
                    : cardRenderer.info("提供者 API 用量", adminService.getApiUsageStatsByProvider(value));
            case "summary" -> cardRenderer.info("API 用量總覽", adminService.getApiUsageSummary());
            default -> cardRenderer.error(
                    "未知的用量命令",
                    "無法識別：" + subCommand + "\n請使用 /admin usage、month、provider 或 summary。");
        };
    }

    private Message inputError(String message) {
        return cardRenderer.error("需要更多資料", message);
    }

    private static boolean isEnabled(String value) {
        return value.equalsIgnoreCase("on")
                || value.equals("開")
                || value.equals("啟用");
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
