package com.linetranslate.bot.service.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.bot.messaging.model.ClipboardAction;
import com.linecorp.bot.messaging.model.PostbackAction;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linetranslate.bot.service.ai.AiModelCatalog;
import com.linetranslate.bot.service.ai.AiModelDescriptor;
import com.linetranslate.bot.service.ai.AiModelPage;
import com.linetranslate.bot.service.line.intent.LineIntent;
import com.linetranslate.bot.service.translation.TranslationResponse;

class LineMessageRendererTests {

    private LineMessageRenderer renderer;

    @BeforeEach
    void setUp() {
        AiModelCatalog catalog = mock(AiModelCatalog.class);
        when(catalog.list("claude", 20)).thenReturn(new AiModelPage(List.of(
                new AiModelDescriptor(
                        "anthropic/claude-sonnet-4",
                        "Claude Sonnet 4",
                        Set.of("text", "image"),
                        Set.of("text"),
                        null,
                        null)), 1, false));
        renderer = new LineMessageRenderer(catalog);
    }

    @Test
    void helpAboutLanguageAndModelsExposeOpenRouterConversationContract() {
        assertThat(text(renderer.help()))
                .contains("/model [OpenRouter 模型 slug]", "/models [關鍵字]")
                .doesNotContain("/setai", "Gemini");
        assertThat(text(renderer.about())).contains("透過 OpenRouter 使用多種 AI 模型");
        assertThat(text(renderer.languageSelection())).contains("🌐 語言選擇", "🇻🇳 越南文: vi");
        assertThat(text(renderer.models("claude")))
                .contains("搜尋：claude", "共 1 個結果", "anthropic/claude-sonnet-4 [圖]",
                        "/model [完整 slug]");
    }

    @Test
    void statusTranslationAndErrorsUseOneRendererSeam() {
        assertThat(text(renderer.status("status-body"))).isEqualTo("status-body");
        assertThat(text(renderer.translation("translated"))).isEqualTo("translated");
        assertThat(text(renderer.imageFailure())).isEqualTo(
                "圖片處理失敗。\n請確保圖片清晰且包含可識別的文字，或稍後再試。");
        assertThat(text(renderer.invalid(new LineIntent.Invalid(
                LineIntent.InvalidReason.MODEL_REQUIRED, ""))))
                .contains("/model openai/gpt-4o-mini");
        assertThat(text(renderer.invalid(new LineIntent.Invalid(
                LineIntent.InvalidReason.UNSUPPORTED_LANGUAGE, "xx"))))
                .isEqualTo("不支持的語言代碼：xx\n請使用有效的語言代碼，例如：en, ja, zh-tw 等");
    }

    @Test
    void modelListingStaysInsideLineTextLimitForMaximumLengthSlugs() {
        List<AiModelDescriptor> models = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> new AiModelDescriptor(
                        "p/" + "m".repeat(190) + index,
                        "ignored",
                        Set.of("text"),
                        Set.of("text"),
                        null,
                        null))
                .toList();
        AiModelCatalog catalog = mock(AiModelCatalog.class);
        when(catalog.list("", 20)).thenReturn(new AiModelPage(models, 100, false));

        String rendered = text(new LineMessageRenderer(catalog).models(""));

        assertThat(rendered.length()).isLessThanOrEqualTo(5_000);
        assertThat(rendered).contains("只顯示前 20 個");
    }

    @Test
    void actionableTranslationUsesClipboardAndOpaquePostbacks() {
        var response = new TranslationResponse(
                "完整翻譯訊息", "翻譯結果", "507f1f77bcf86cd799439011", "en", "ja");

        TextMessage rendered = (TextMessage) renderer.translation(response);

        assertThat(rendered.text()).isEqualTo("完整翻譯訊息");
        assertThat(rendered.quickReply()).isNotNull();
        var actions = rendered.quickReply().items().stream()
                .map(item -> item.action())
                .toList();
        assertThat(actions).hasSizeLessThanOrEqualTo(13);
        assertThat(actions).anySatisfy(action -> assertThat(action)
                .isEqualTo(new ClipboardAction("複製譯文", "翻譯結果")));
        assertThat(actions.stream()
                .filter(PostbackAction.class::isInstance)
                .map(PostbackAction.class::cast))
                .isNotEmpty()
                .allSatisfy(action -> {
                    assertThat(action.data()).startsWith("command=").hasSizeLessThanOrEqualTo(300);
                    assertThat(action.data()).doesNotContain("翻譯結果", "完整翻譯訊息");
                });
    }

    @Test
    void oversizedClipboardPayloadKeepsReadableTextFallback() {
        String translated = "a".repeat(1_001);
        var response = new TranslationResponse(
                translated, translated, "507f1f77bcf86cd799439011", "en", "ja");

        TextMessage rendered = (TextMessage) renderer.translation(response);

        assertThat(rendered.text()).isEqualTo(translated);
        assertThat(rendered.quickReply().items())
                .noneSatisfy(item -> assertThat(item.action())
                        .isInstanceOf(ClipboardAction.class));
    }

    private static String text(com.linecorp.bot.messaging.model.Message message) {
        return ((TextMessage) message).text();
    }
}
