package com.linetranslate.bot.service.line;

import org.springframework.stereotype.Component;

import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linetranslate.bot.service.ai.AiModelCatalog;
import com.linetranslate.bot.service.ai.AiModelDescriptor;
import com.linetranslate.bot.service.ai.AiModelPage;
import com.linetranslate.bot.service.line.intent.LineIntent;

/** Central LINE message renderer for user-facing interaction results. */
@Component
public class LineMessageRenderer {

    private static final int MODEL_PAGE_LIMIT = 20;
    private final AiModelCatalog modelCatalog;

    public LineMessageRenderer(AiModelCatalog modelCatalog) {
        this.modelCatalog = modelCatalog;
    }

    public Message help() {
        return text("🤖 LINE 翻譯機器人幫助\n\n"
                + "[💬 基本使用]\n"
                + "• 直接發送文字 → 自動檢測語言並翻譯\n"
                + "• 發送圖片 → 識別圖片中的文字並翻譯\n"
                + "• 快速翻譯:[語言代碼] [文本] → 翻譯到指定語言\n\n"
                + "[⚙️ 設置命令]\n"
                + "🔠 /外文翻譯 [語言] - 設置偏好的目標語言\n"
                + "🀄 /中文翻譯 [語言] - 設置中文翻譯的目標語言\n"
                + "🤖 /model [OpenRouter 模型 slug] - 指定模型\n"
                + "📋 /models [關鍵字] - 搜尋可用模型\n\n"
                + "[ℹ️ 其他命令]\n"
                + "❓ /help - 顯示此幫助信息\n"
                + "ℹ️ /about - 關於此機器人\n"
                + "🔤 /lang - 顯示語言選擇菜單\n"
                + "📈 /status - 顯示您的所有設定\n"
                + "👤 /profile - 查看您的用戶資料");
    }

    public Message about() {
        return text("🚀 LINE 翻譯機器人\n\n"
                + "這是一個透過 OpenRouter 使用多種 AI 模型進行即時翻譯的 LINE 機器人。\n\n"
                + "功能：\n"
                + "• 🌐 自動語言檢測\n"
                + "• 💬 多語言翻譯\n"
                + "• 📚 批量多行文本翻譯\n"
                + "• ⭐ 語言與模型偏好設定\n"
                + "• ⚡ 快速翻譯\n"
                + "• 📸 圖片文字識別與翻譯");
    }

    public Message languageSelection() {
        return text("🌐 語言選擇\n\n"
                + "請使用以下命令設置您偏好的語言：\n"
                + "/外文翻譯 [語言代碼]\n\n"
                + "常用語言代碼：\n"
                + "🇺🇸 英文: en\n🇯🇵 日文: ja\n🇰🇷 韓文: ko\n"
                + "🇨🇳 簡體中文: zh-cn\n🇹🇼 繁體中文: zh-tw\n"
                + "🇫🇷 法文: fr\n🇩🇪 德文: de\n🇪🇸 西班牙文: es\n"
                + "🇮🇹 義大利文: it\n🇷🇺 俄文: ru\n🇵🇹 葡萄牙文: pt\n"
                + "🇹🇭 泰文: th\n🇻🇳 越南文: vi\n🇮🇩 印尼文: id\n");
    }

    public Message models(String query) {
        AiModelPage page = modelCatalog.list(query, MODEL_PAGE_LIMIT);
        String normalizedQuery = query == null ? "" : query.trim();
        StringBuilder body = new StringBuilder("🤖 OpenRouter 可用模型\n");
        if (!normalizedQuery.isEmpty()) {
            body.append("搜尋：").append(normalizedQuery).append("\n");
        }
        body.append("共 ").append(page.total()).append(" 個結果");
        if (page.stale()) {
            body.append("（catalog 暫時降級）");
        }
        body.append("\n\n");
        if (page.models().isEmpty()) {
            body.append("找不到符合的模型。\n");
        } else {
            for (AiModelDescriptor model : page.models()) {
                body.append("• ").append(model.id());
                if (model.inputModalities().contains("image")) {
                    body.append(" [圖]");
                }
                body.append("\n");
            }
            if (page.total() > page.models().size()) {
                body.append("\n只顯示前 ").append(page.models().size())
                        .append(" 個；用 /models [關鍵字] 縮小範圍。\n");
            }
        }
        body.append("\n使用 /model [完整 slug] 指定模型");
        return text(body.toString());
    }

    public Message translation(String result) { return text(result); }
    public Message status(String result) { return text(result); }
    public Message profile(String result) { return text(result); }
    public Message settingResult(String result) { return text(result); }
    public Message imageResult(String result) { return text(result); }

    public Message imageFailure() {
        return text("圖片處理失敗。\n請確保圖片清晰且包含可識別的文字，或稍後再試。");
    }

    public Message invalid(LineIntent.Invalid invalid) {
        return text(switch (invalid.reason()) {
            case MODEL_REQUIRED -> "請指定完整 OpenRouter 模型 slug。例如：/model openai/gpt-4o-mini";
            case INVALID_MODEL -> "模型 slug 格式無效。請先用 /models [關鍵字] 查詢完整 slug。";
            case MODEL_QUERY_TOO_LONG -> "模型搜尋關鍵字過長；請縮短至 80 個字元內。";
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

    private static Message text(String value) {
        return new TextMessage(value == null ? "" : value);
    }
}
