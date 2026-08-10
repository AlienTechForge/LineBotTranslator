package com.linetranslate.bot.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.linecorp.bot.model.message.Message;
import com.linecorp.bot.model.message.TextMessage;
import com.linetranslate.bot.service.AdminService;
import com.linetranslate.bot.logging.SafeLog;

import lombok.extern.slf4j.Slf4j;

/**
 * 管理員命令控制器
 * 處理所有管理員相關的命令
 */
@Component
@Slf4j
public class AdminController {

    private final AdminService adminService;

    @Autowired
    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * 處理管理員命令
     * 
     * @param userId 用戶ID
     * @param command 命令內容
     * @return 回應消息
     */
    public Message handleCommand(String userId, String command) {
        log.info("處理管理員命令: user={}, command={}",
                SafeLog.user(userId), SafeLog.content(command));
        
        if (!adminService.isAdmin(userId)) {
            return new TextMessage("您沒有管理員權限。");
        }
        
        if (command.isEmpty()) {
            return new TextMessage(getAdminHelpMessage());
        }
        
        String[] parts = command.split(" ", 2);
        String subCommand = parts[0].toLowerCase();
        String param = parts.length > 1 ? parts[1] : "";
        
        switch (subCommand) {
            case "help":
                // 處理 /adminhelp 命令，直接返回管理員幫助信息
                return new TextMessage(getAdminHelpMessage());
                
            case "isadmin":
                // 處理 /isadmin 命令，確認用戶是否為管理員
                return new TextMessage("您是管理員。");
                
            case "broadcast":
                if (param.isEmpty()) {
                    return new TextMessage("請指定要廣播的消息。例如：/admin broadcast 您好，這是一條廣播消息。");
                }
                return handleBroadcastCommand(userId, param);
                
            case "stats":
                // 用現有的 getSystemStats 方法
                return new TextMessage(adminService.getSystemStats());
                
            case "users":
                return handleUsersCommand(userId);
                
            case "user":
                if (param.isEmpty()) {
                    return new TextMessage("請指定要查詢的用戶ID。例如：/admin user U123456789");
                }
                return handleUserCommand(userId, param);
                
            case "nickname":
                // 處理設置用戶暱稱的命令
                return handleNicknameCommand(userId, param);
                
            case "config":
                // 處理系統配置命令
                if (param.isEmpty()) {
                    // 如果沒有參數，顯示系統配置信息和可用的子命令
                    StringBuilder helpBuilder = new StringBuilder();
                    helpBuilder.append(adminService.getSystemConfig()).append("\n");
                    helpBuilder.append("【配置命令說明】\n");
                    helpBuilder.append("• /admin config c2lang [lang] - 設置中文翻譯默認目標語言\n");
                    helpBuilder.append("• /admin config lang [lang] - 設置其他語言翻譯默認目標語言\n");
                    helpBuilder.append("• /admin config ai [provider] - 設置默認 AI 提供者 (openai 或 gemini)\n");
                    helpBuilder.append("• /admin config openai [model] - 設置 OpenAI 默認模型\n");
                    helpBuilder.append("• /admin config gemini [model] - 設置 Gemini 默認模型\n");
                    helpBuilder.append("• /admin config ocr [on/off] - 啟用或禁用 OCR 功能\n");
                    return new TextMessage(helpBuilder.toString());
                }
                
                // 解析子命令和參數
                String[] configParts = param.split(" ", 2);
                String configSubCommand = configParts[0].toLowerCase();
                String configParam = configParts.length > 1 ? configParts[1].trim() : "";
                
                // 處理各種子命令
                switch (configSubCommand) {
                    case "c2lang":
                        if (configParam.isEmpty()) {
                            return new TextMessage("請指定中文翻譯默認目標語言。例如：/admin config c2lang en");
                        }
                        return new TextMessage(adminService.setDefaultTargetLanguageForChinese(configParam));
                        
                    case "lang":
                        if (configParam.isEmpty()) {
                            return new TextMessage("請指定其他語言翻譯默認目標語言。例如：/admin config lang zh-TW");
                        }
                        return new TextMessage(adminService.setDefaultTargetLanguageForOthers(configParam));
                        
                    case "ai":
                        if (configParam.isEmpty()) {
                            return new TextMessage("請指定默認 AI 提供者。例如：/admin config ai openai");
                        }
                        return new TextMessage(adminService.setDefaultAiProvider(configParam));
                        
                    case "openai":
                        if (configParam.isEmpty()) {
                            return new TextMessage("請指定 OpenAI 默認模型。例如：/admin config openai gpt-4o");
                        }
                        return new TextMessage(adminService.setOpenAiDefaultModel(configParam));
                        
                    case "gemini":
                        if (configParam.isEmpty()) {
                            return new TextMessage("請指定 Gemini 默認模型。例如：/admin config gemini gemini-pro");
                        }
                        return new TextMessage(adminService.setGeminiDefaultModel(configParam));
                        
                    case "ocr":
                        if (configParam.isEmpty()) {
                            return new TextMessage("請指定 OCR 功能狀態。例如：/admin config ocr on 或 /admin config ocr off");
                        }
                        boolean enabled = configParam.equalsIgnoreCase("on") || configParam.equalsIgnoreCase("開") || configParam.equalsIgnoreCase("啟用");
                        return new TextMessage(adminService.setOcrEnabled(enabled));
                        
                    default:
                        return new TextMessage("未知的配置子命令：" + configSubCommand + "\n\n請使用 /admin config 查看可用的配置命令。");
                }
                
            case "usage":
                // 處理 API 使用量和費用命令
                if (param.isEmpty()) {
                    // 如果沒有參數，顯示當前月的使用量和費用
                    return new TextMessage(adminService.getApiUsageStats());
                }
                
                // 解析子命令和參數
                String[] usageParts = param.split(" ", 2);
                String usageSubCommand = usageParts[0].toLowerCase();
                String usageParam = usageParts.length > 1 ? usageParts[1].trim() : "";
                
                // 處理各種子命令
                switch (usageSubCommand) {
                    case "month":
                        // 指定月份的使用量和費用
                        if (usageParam.isEmpty()) {
                            return new TextMessage("請指定月份（格式：YYYY-MM）。例如：/admin usage month 2025-05");
                        }
                        return new TextMessage(adminService.getApiUsageStatsByMonth(usageParam));
                        
                    case "provider":
                        // 按 AI 提供者顯示使用量和費用
                        if (usageParam.isEmpty()) {
                            return new TextMessage("請指定 AI 提供者 (openai 或 gemini)。例如：/admin usage provider openai");
                        }
                        return new TextMessage(adminService.getApiUsageStatsByProvider(usageParam));
                        
                    case "summary":
                        // 顯示所有時間的使用量和費用摘要
                        return new TextMessage(adminService.getApiUsageSummary());
                        
                    default:
                        return new TextMessage("未知的使用量子命令：" + usageSubCommand + "\n\n可用的子命令：\n• /admin usage - 顯示當前月的使用量和費用\n• /admin usage month [YYYY-MM] - 顯示指定月份的使用量和費用\n• /admin usage provider [openai/gemini] - 按 AI 提供者顯示使用量和費用\n• /admin usage summary - 顯示所有時間的使用量和費用摘要");
                }
                
            case "today":
                // 使用現有的 getTodayStats 方法
                return new TextMessage(adminService.getTodayStats());
                
            case "add":
                // 處理添加管理員命令
                if (param.isEmpty()) {
                    return new TextMessage("請指定要添加為管理員的用戶ID。例如：/admin add U123456789");
                }
                // 調用 AdminService 的 addAdmin 方法
                String addResult = adminService.addAdmin(param.trim());
                return new TextMessage(addResult);
                
            case "remove":
                // 處理移除管理員命令
                if (param.isEmpty()) {
                    return new TextMessage("請指定要移除管理員權限的用戶ID。例如：/admin remove U123456789");
                }
                // 調用 AdminService 的 removeAdmin 方法
                String removeResult = adminService.removeAdmin(param.trim());
                return new TextMessage(removeResult);
                
            default:
                return new TextMessage("未知的管理員命令：" + subCommand + "\n\n" + getAdminHelpMessage());
        }
    }
    
    /**
     * 處理廣播命令
     */
    private Message handleBroadcastCommand(String userId, String message) {
        log.info("廣播命令: user={}, content={}",
                SafeLog.user(userId), SafeLog.content(message));
        
        try {
            int count = adminService.broadcastMessage(message);
            return new TextMessage("📢 廣播成功\n\n已向 " + count + " 個用戶發送消息：\n" + message);
        } catch (Exception e) {
            log.error("廣播消息失敗: failure={}", SafeLog.failure(e));
            return new TextMessage("廣播消息失敗，請稍後再試。");
        }
    }
    
    /**
     * 處理用戶列表命令
     */
    private Message handleUsersCommand(String userId) {
        log.info("用戶列表命令: user={}", SafeLog.user(userId));
        
        try {
            List<Map<String, Object>> users = adminService.getRecentUsers(10);
            StringBuilder sb = new StringBuilder();
            sb.append("👥 最近活躍用戶\n\n");
            
            for (int i = 0; i < users.size(); i++) {
                Map<String, Object> user = users.get(i);
                sb.append(i + 1).append(". ");
                sb.append(user.get("displayName")).append(" (");
                sb.append(user.get("userId").toString().substring(0, 6)).append("...)\n");
                sb.append("   最後活動：").append(user.get("lastActiveTime")).append("\n");
            }
            
            sb.append("\n使用 /admin user [ID] 查看用戶詳細信息");
            
            return new TextMessage(sb.toString());
        } catch (Exception e) {
            log.error("獲取用戶列表失敗: failure={}", SafeLog.failure(e));
            return new TextMessage("獲取用戶列表失敗，請稍後再試。");
        }
    }
    
    /**
     * 處理查詢用戶命令
     */
    private Message handleUserCommand(String userId, String targetUserId) {
        log.info("查詢用戶命令: admin={}, target={}",
                SafeLog.user(userId), SafeLog.user(targetUserId));
        
        try {
            Map<String, Object> userInfo = adminService.getUserInfo(targetUserId);
            if (userInfo == null) {
                return new TextMessage("找不到用戶：" + targetUserId);
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("👤 用戶詳細信息\n\n");
            sb.append("• 用戶ID：").append(userInfo.get("userId")).append("\n");
            sb.append("• 顯示名稱：").append(userInfo.get("displayName")).append("\n");
            sb.append("• 註冊時間：").append(userInfo.get("registrationTime")).append("\n");
            sb.append("• 最後活動：").append(userInfo.get("lastActiveTime")).append("\n\n");
            
            sb.append("📊 使用統計\n");
            sb.append("• 翻譯次數：").append(userInfo.get("translationCount")).append("\n");
            sb.append("• 圖片翻譯次數：").append(userInfo.get("imageTranslationCount")).append("\n\n");
            
            sb.append("⚙️ 用戶設置\n");
            sb.append("• 預設翻譯語言：").append(userInfo.get("preferredLanguage")).append("\n");
            sb.append("• 中文翻譯目標語言：").append(userInfo.get("preferredChineseTargetLanguage")).append("\n");
            sb.append("• 預設 AI 提供者：").append(userInfo.get("preferredAiProvider")).append("\n");
            
            return new TextMessage(sb.toString());
        } catch (Exception e) {
            log.error("獲取用戶信息失敗: failure={}", SafeLog.failure(e));
            return new TextMessage("獲取用戶信息失敗，請稍後再試。");
        }
    }
    
    /**
     * 處理設置用戶暱稱的命令
     * 
     * @param adminId 管理員ID
     * @param param 命令參數
     * @return 回應消息
     */
    private Message handleNicknameCommand(String adminId, String param) {
        if (param.isEmpty()) {
            return new TextMessage("請提供用戶ID和新的暱稱。\n格式：/admin nickname [用戶ID] [新暱稱]\n例如：/admin nickname U123456789 張三");
        }
        
        String[] parts = param.split(" ", 2);
        if (parts.length < 2) {
            return new TextMessage("請提供新的暱稱。\n格式：/admin nickname [用戶ID] [新暱稱]\n例如：/admin nickname U123456789 張三");
        }
        
        String targetUserId = parts[0];
        String newNickname = parts[1];
        
        // 調用 AdminService 的方法設置用戶暱稱
        String result = adminService.setUserDisplayName(targetUserId, newNickname);
        return new TextMessage(result);
    }
    
    /**
     * 獲取管理員幫助信息
     */
    private String getAdminHelpMessage() {
        return "【管理員命令】\n" +
                "➖ /admin help - 顯示此幫助信息\n" +
                "➖ /admin isadmin - 確認您是否是管理員\n" +
                "➖ /admin broadcast [訊息] - 向所有用戶廣播訊息\n" +
                "➖ /admin stats - 查看系統統計信息\n" +
                "➖ /admin users - 查看用戶列表\n" +
                "➖ /admin user [用戶ID] - 查看特定用戶資料\n" +
                "➖ /admin nickname [用戶ID] [新暱稱] - 設置用戶暱稱\n" +
                "➖ /admin config - 查看和修改系統配置\n" +
                "➖ /admin usage - 查看 API 使用量和費用\n" +
                "➖ /admin add [用戶ID] - 添加管理員\n" +
                "➖ /admin remove [用戶ID] - 移除管理員權限";
    }
}
