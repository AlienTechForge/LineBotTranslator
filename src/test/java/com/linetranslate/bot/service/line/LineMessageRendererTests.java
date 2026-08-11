package com.linetranslate.bot.service.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.bot.messaging.model.TextMessage;
import com.linetranslate.bot.config.GeminiConfig;
import com.linetranslate.bot.config.OpenAiConfig;
import com.linetranslate.bot.service.line.intent.LineIntent;

class LineMessageRendererTests {

    private LineMessageRenderer renderer;

    @BeforeEach
    void setUp() {
        OpenAiConfig openAiConfig = mock(OpenAiConfig.class);
        GeminiConfig geminiConfig = mock(GeminiConfig.class);
        when(openAiConfig.getAvailableModels()).thenReturn(List.of("gpt-4o", "gpt-4o-mini"));
        when(geminiConfig.getAvailableModels()).thenReturn(List.of("gemini-1.5-pro"));
        renderer = new LineMessageRenderer(openAiConfig, geminiConfig);
    }

    @Test
    void helpAboutLanguageAndModelsKeepExistingVisibleContract() {
        assertThat(text(renderer.help())).isEqualTo(
                "🤖 LINE 翻譯機器人幫助\n\n"
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
        assertThat(text(renderer.about())).contains("🚀 LINE 翻譯機器人", "支持 OpenAI 和 Google Gemini 模型。");
        assertThat(text(renderer.languageSelection())).contains("🌐 語言選擇", "🇻🇳 越南文: vi");
        assertThat(text(renderer.models())).isEqualTo(
                "🤖 可用的 AI 模型\n\n"
                        + "OpenAI 模型：\n• gpt-4o\n• gpt-4o-mini\n\n"
                        + "Google Gemini 模型：\n• gemini-1.5-pro\n\n"
                        + "使用 /setmodel [模型名稱] 設置您偏好的模型");
    }

    @Test
    void statusTranslationAndErrorsUseOneRendererSeam() {
        assertThat(text(renderer.status("status-body"))).isEqualTo("status-body");
        assertThat(text(renderer.translation("translated"))).isEqualTo("translated");
        assertThat(text(renderer.imageFailure())).isEqualTo(
                "圖片處理失敗。\n請確保圖片清晰且包含可識別的文字，或稍後再試。");
        assertThat(text(renderer.invalid(new LineIntent.Invalid(
                LineIntent.InvalidReason.AI_PROVIDER_REQUIRED, ""))))
                .isEqualTo("請指定 AI 提供者 (openai 或 gemini)。例如：/setai openai");
        assertThat(text(renderer.invalid(new LineIntent.Invalid(
                LineIntent.InvalidReason.UNSUPPORTED_LANGUAGE, "xx"))))
                .isEqualTo("不支持的語言代碼：xx\n請使用有效的語言代碼，例如：en, ja, zh-tw 等");
    }

    private static String text(com.linecorp.bot.messaging.model.Message message) {
        return ((TextMessage) message).text();
    }
}
