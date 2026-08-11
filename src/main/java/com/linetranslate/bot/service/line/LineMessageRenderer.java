package com.linetranslate.bot.service.line;

import java.util.List;

import org.springframework.stereotype.Component;

import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linetranslate.bot.config.GeminiConfig;
import com.linetranslate.bot.config.OpenAiConfig;
import com.linetranslate.bot.service.line.intent.LineIntent;

/** Central LINE message renderer for user-facing interaction results. */
@Component
public class LineMessageRenderer {

    private final OpenAiConfig openAiConfig;
    private final GeminiConfig geminiConfig;

    public LineMessageRenderer(OpenAiConfig openAiConfig, GeminiConfig geminiConfig) {
        this.openAiConfig = openAiConfig;
        this.geminiConfig = geminiConfig;
    }

    public Message help() {
        return text("🤖 LINE 翻譯機器人幫助\n\n"
                + "[💬 基本使用]\n"
                + "• 直接發送文字 → 自動檢測語言並翻譯\n"
                + "• 發送圖片 → 識別圖片中的文字並翻譯\n"
                + "• 快速翻譯:[語言代碼] [文本] → 翻譯到指定語言\n\n"
                + "[⚙️ 設置命令]\n"
                + "🔄 /setai [提供者] - 設置 AI 提供者 (openai 或 gemini)\n"
                + "🔠 /外文翻譯 [語言] - 設置偏好的目標語言\n"
                + "🀄 /中文翻譯 [語言] - 設置中文翻譯的目標語言\n"
                + "🤖 /setmodel [模型] - 設置 AI 模型\n"
                + "📋 /models - 顯示可用的 AI 模型\n\n"
                + "[ℹ️ 其他命令]\n"
                + "❓ /help - 顯示此幫助信息\n"
                + "ℹ️ /about - 關於此機器人\n"
                + "🔤 /lang - 顯示語言選擇菜單\n"
                + "📈 /status - 顯示您的所有設定\n"
                + "👤 /profile - 查看您的用戶資料");
    }

    public Message about() {
        return text("🚀 LINE 翻譯機器人\n\n"
                + "這是一個使用先進 AI 技術進行即時翻譯的 LINE 機器人。\n"
                + "支持 OpenAI 和 Google Gemini 模型。\n\n"
                + "功能：\n"
                + "• 🌐 自動語言檢測\n"
                + "• 💬 多語言翻譯\n"
                + "• 📚 批量多行文本翻譯\n"
                + "• ⭐ 語言偏好設定\n"
                + "• ⚡ 快速翻譯\n"
                + "• 📸 圖片文字識別與翻譯");
    }

    public Message languageSelection() {
        return text("🌐 語言選擇\n\n"
                + "請使用以下命令設置您偏好的語言：\n"
                + "/外文翻譯 [語言代碼]\n\n"
                + "常用語言代碼：\n"
                + "🇺🇸 英文: en\n"
                + "🇯🇵 日文: ja\n"
                + "🇰🇷 韓文: ko\n"
                + "🇨🇳 簡體中文: zh-cn\n"
                + "🇹🇼 繁體中文: zh-tw\n"
                + "🇫🇷 法文: fr\n"
                + "🇩🇪 德文: de\n"
                + "🇪🇸 西班牙文: es\n"
                + "🇮🇹 義大利文: it\n"
                + "🇷🇺 俄文: ru\n"
                + "🇵🇹 葡萄牙文: pt\n"
                + "🇹🇭 泰文: th\n"
                + "🇻🇳 越南文: vi\n"
                + "🇮🇩 印尼文: id\n");
    }

    public Message models() {
        StringBuilder body = new StringBuilder("🤖 可用的 AI 模型\n\nOpenAI 模型：\n");
        appendModels(body, openAiConfig.getAvailableModels(), openAiConfig.getModelName());
        body.append("\nGoogle Gemini 模型：\n");
        appendModels(body, geminiConfig.getAvailableModels(), geminiConfig.getModelName());
        body.append("\n使用 /setmodel [模型名稱] 設置您偏好的模型");
        return text(body.toString());
    }

    public Message translation(String result) {
        return text(result);
    }

    public Message status(String result) {
        return text(result);
    }

    public Message profile(String result) {
        return text(result);
    }

    public Message settingResult(String result) {
        return text(result);
    }

    public Message imageResult(String result) {
        return text(result);
    }

    public Message imageFailure() {
        return text("圖片處理失敗。\n請確保圖片清晰且包含可識別的文字，或稍後再試。");
    }

    public Message invalid(LineIntent.Invalid invalid) {
        return text(switch (invalid.reason()) {
            case AI_PROVIDER_REQUIRED -> "請指定 AI 提供者 (openai 或 gemini)。例如：/setai openai";
            case MODEL_REQUIRED -> "請指定 AI 模型名稱。例如：/setmodel gpt-4o";
            case FOREIGN_LANGUAGE_REQUIRED ->
                    "請指定語言代碼或名稱。例如：/外文翻譯 en 或 /外文翻譯 日文";
            case CHINESE_LANGUAGE_REQUIRED ->
                    "請指定中文翻譯的預設目標語言。例如：/中文翻譯 vi 或 /中文翻譯 越南文";
            case QUICK_TRANSLATION_FORMAT ->
                    "快速翻譯格式錯誤。正確格式：快速翻譯:[語言代碼] [文本]\n例如：快速翻譯:en 你好";
            case UNSUPPORTED_LANGUAGE -> "不支持的語言代碼：" + invalid.value()
                    + "\n請使用有效的語言代碼，例如：en, ja, zh-tw 等";
            case UNKNOWN_COMMAND -> "未知命令。發送 /help 獲取可用命令列表。";
            case UNSUPPORTED_POSTBACK -> "無法識別此互動操作。請發送 /help 查看可用功能。";
        });
    }

    private static void appendModels(StringBuilder body, List<String> models, String fallback) {
        if (models == null || models.isEmpty()) {
            body.append("• ").append(fallback).append(" (默認)\n");
            return;
        }
        for (String model : models) {
            body.append("• ").append(model).append("\n");
        }
    }

    private static Message text(String value) {
        return new TextMessage(value == null ? "" : value);
    }
}
