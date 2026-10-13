package com.linetranslate.bot.service.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.bot.messaging.model.ClipboardAction;
import com.linecorp.bot.messaging.model.ImageMessage;
import com.linecorp.bot.messaging.model.PostbackAction;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiModelCatalog;
import com.linetranslate.bot.service.ai.AiModelDescriptor;
import com.linetranslate.bot.service.ai.AiModelPage;
import com.linetranslate.bot.service.imageproxy.ImageProxyLinkService;
import com.linetranslate.bot.service.imageproxy.ImageProxyLinks;
import com.linetranslate.bot.service.imageproxy.DwzShortLinkService;
import com.linetranslate.bot.service.line.intent.LineIntent;
import com.linetranslate.bot.service.ocr.DownloadedImage;
import com.linetranslate.bot.service.ocr.ImageTranslationContext;
import com.linetranslate.bot.service.ocr.ImageTranslationOutcome;
import com.linetranslate.bot.service.ocr.ImageTranslationPipeline;
import com.linetranslate.bot.service.ocr.ImageTranslationPipelineResult;
import com.linetranslate.bot.service.ocr.ImageTranslationReply;
import com.linetranslate.bot.service.ocr.ImageTranslationService;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.settings.RuntimeSettings;
import com.linetranslate.bot.service.settings.RuntimeSettingsSource;
import com.linetranslate.bot.service.storage.ImageStorageResult;
import com.linetranslate.bot.service.translation.TranslationRequestKind;
import com.linetranslate.bot.service.translation.TranslationResponse;
import com.linetranslate.bot.service.translation.TranslationWorkflowResult;

class LineMessageRendererTests {

    private static final String TRUNCATION_MARKER = "…（內容過長，已截斷）";

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
                "完整翻譯訊息", "翻譯結果", "507f1f77bcf86cd799439011", "en", "ja",
                "faithful", "faithful-v1");

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
        assertThat(actions.stream()
                .filter(PostbackAction.class::isInstance)
                .map(PostbackAction.class::cast)
                .map(PostbackAction::data))
                .anySatisfy(data -> assertThat(data).contains("restyle"));
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

    @Test
    void directImageOutputSurvivesShortUrlToggleAndDwzFailure() {
        AiModelCatalog catalog = mock(AiModelCatalog.class);
        RuntimeSettingsSource settings = mock(RuntimeSettingsSource.class);
        ImageProxyLinkService proxy = mock(ImageProxyLinkService.class);
        DwzShortLinkService shortLinks = mock(DwzShortLinkService.class);
        TranslationResponse translation = TranslationResponse.plain(
                "【翻譯圖片（連結 1 小時內有效）】\nhttps://s3.azndev.com/signed\n\n翻譯文字");
        ImageTranslationReply reply = new ImageTranslationReply(
                translation, Optional.of("https://s3.azndev.com/signed"));
        RuntimeSettings enabled = new RuntimeSettings(
                "en", "zh-TW", "openai/gpt-4o-mini", true, true,
                3, 1, null, null, RuntimeSettings.Source.PERSISTED);
        when(settings.current()).thenReturn(enabled);
        when(proxy.register("https://s3.azndev.com/signed")).thenReturn(Optional.of(
                new ImageProxyLinks(
                        java.net.URI.create("https://translate.azndev.com/i/0123456789abcdefghij-_"),
                        java.net.URI.create("https://translate.azndev.com/i/0123456789abcdefghij-_/preview"))));
        when(shortLinks.shorten(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            ImageProxyLinks clean = invocation.getArgument(0);
            return Optional.of(new ImageProxyLinks(
                    java.net.URI.create("https://s.azndev.com/original"),
                    java.net.URI.create("https://s.azndev.com/preview")));
        });
        LineMessageRenderer proxyRenderer = new LineMessageRenderer(catalog, settings, proxy, shortLinks);

        ImageMessage image = (ImageMessage) proxyRenderer.imageResult(reply);

        assertThat(image.originalContentUrl().toString())
                .isEqualTo("https://s.azndev.com/original");
        assertThat(image.previewImageUrl().toString()).isEqualTo("https://s.azndev.com/preview");

        when(settings.current()).thenReturn(new RuntimeSettings(
                "en", "zh-TW", "openai/gpt-4o-mini", true, false,
                3, 2, null, null, RuntimeSettings.Source.PERSISTED));
        ImageMessage unshortened = (ImageMessage) proxyRenderer.imageResult(reply);
        assertThat(unshortened.originalContentUrl().toString())
                .isEqualTo("https://translate.azndev.com/i/0123456789abcdefghij-_");

        when(settings.current()).thenReturn(enabled);
        when(shortLinks.shorten(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        ImageMessage fallback = (ImageMessage) proxyRenderer.imageResult(reply);
        assertThat(fallback.originalContentUrl().toString())
                .isEqualTo("https://translate.azndev.com/i/0123456789abcdefghij-_");
    }

    @Test
    void directImageDoesNotRequireConfiguredTranslateProxy() {
        AiModelCatalog catalog = mock(AiModelCatalog.class);
        RuntimeSettingsSource settings = mock(RuntimeSettingsSource.class);
        ImageProxyLinkService proxy = mock(ImageProxyLinkService.class);
        DwzShortLinkService shortLinks = mock(DwzShortLinkService.class);
        String signed = "https://s3.azndev.com/line-bot/translated-images/1/image.png"
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=test%2F20260812%2Fus-east-1%2Fs3%2Faws4_request"
                + "&X-Amz-Date=20260812T071753Z&X-Amz-Expires=3600"
                + "&X-Amz-SignedHeaders=host&X-Amz-Signature=" + "a".repeat(64);
        ImageTranslationReply reply = new ImageTranslationReply(
                TranslationResponse.plain("fallback"), Optional.of(signed));
        RuntimeSettings enabled = new RuntimeSettings(
                "en", "zh-TW", "openai/gpt-4o-mini", true, true,
                3, 1, null, null, RuntimeSettings.Source.PERSISTED);
        when(settings.current()).thenReturn(enabled);
        when(proxy.register(signed)).thenReturn(Optional.empty());
        when(shortLinks.shortenSignedImage(java.net.URI.create(signed)))
                .thenReturn(Optional.of(java.net.URI.create("https://s.azndev.com/abc")));
        LineMessageRenderer directRenderer = new LineMessageRenderer(
                catalog, settings, proxy, shortLinks);

        ImageMessage shortened = (ImageMessage) directRenderer.imageResult(reply);

        assertThat(shortened.originalContentUrl().toString())
                .isEqualTo("https://s.azndev.com/abc");
        assertThat(shortened.previewImageUrl()).isEqualTo(shortened.originalContentUrl());

        when(settings.current()).thenReturn(new RuntimeSettings(
                "en", "zh-TW", "openai/gpt-4o-mini", true, false,
                3, 2, null, null, RuntimeSettings.Source.PERSISTED));
        ImageMessage unshortened = (ImageMessage) directRenderer.imageResult(reply);
        assertThat(unshortened.originalContentUrl().toString()).isEqualTo(signed);
    }

    private static final String SIGNED_PREVIEW = "https://s3.azndev.com/line-bot/translated-images/1/image-preview.jpg"
            + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
            + "&X-Amz-Credential=test%2F20260812%2Fus-east-1%2Fs3%2Faws4_request"
            + "&X-Amz-Date=20260812T071753Z&X-Amz-Expires=3600"
            + "&X-Amz-SignedHeaders=host&X-Amz-Signature=" + "c".repeat(64);

    @Test
    void withoutTheProxyTheStoredPreviewRenditionIsLinePreviewAndOriginalStaysFull() {
        RuntimeSettingsSource settings = mock(RuntimeSettingsSource.class);
        ImageProxyLinkService proxy = mock(ImageProxyLinkService.class);
        DwzShortLinkService shortLinks = mock(DwzShortLinkService.class);
        ImageTranslationReply reply = new ImageTranslationReply(
                TranslationResponse.plain("fallback"), Optional.of(SIGNED_IMAGE), Optional.of(SIGNED_PREVIEW));
        when(settings.current()).thenReturn(new RuntimeSettings(
                "en", "zh-TW", "openai/gpt-4o-mini", true, true,
                3, 1, null, null, RuntimeSettings.Source.PERSISTED));
        when(proxy.register(SIGNED_IMAGE)).thenReturn(Optional.empty());
        when(shortLinks.shortenSignedImage(java.net.URI.create(SIGNED_IMAGE)))
                .thenReturn(Optional.of(java.net.URI.create("https://s.azndev.com/abc")));
        LineMessageRenderer directRenderer = new LineMessageRenderer(
                mock(AiModelCatalog.class), settings, proxy, shortLinks);

        ImageMessage shortened = (ImageMessage) directRenderer.imageResult(reply);

        assertThat(shortened.originalContentUrl().toString()).isEqualTo("https://s.azndev.com/abc");
        assertThat(shortened.previewImageUrl().toString()).isEqualTo(SIGNED_PREVIEW);
        org.mockito.Mockito.verify(shortLinks, org.mockito.Mockito.never())
                .shortenSignedImage(java.net.URI.create(SIGNED_PREVIEW));

        when(settings.current()).thenReturn(new RuntimeSettings(
                "en", "zh-TW", "openai/gpt-4o-mini", true, false,
                3, 2, null, null, RuntimeSettings.Source.PERSISTED));
        ImageMessage unshortened = (ImageMessage) directRenderer.imageResult(reply);
        assertThat(unshortened.originalContentUrl().toString()).isEqualTo(SIGNED_IMAGE);
        assertThat(unshortened.previewImageUrl().toString()).isEqualTo(SIGNED_PREVIEW);
        assertThat(unshortened.previewImageUrl()).isNotEqualTo(unshortened.originalContentUrl());
    }

    @Test
    void withoutAStoredPreviewTheOriginalIsItsOwnPreviewAsBefore() {
        LineMessageRenderer directRenderer = imageRenderer(true);

        ImageMessage image = (ImageMessage) directRenderer.imageResult(overlayReply());

        assertThat(image.previewImageUrl()).isEqualTo(image.originalContentUrl());
    }

    @Test
    void anInsecureStoredPreviewIsIgnored() {
        ImageTranslationReply reply = new ImageTranslationReply(
                overlayReply().response(), Optional.of(SIGNED_IMAGE),
                Optional.of("http://user:secret@s3.azndev.com/preview.jpg"));

        ImageMessage image = (ImageMessage) imageRenderer(true).imageResult(reply);

        assertThat(image.previewImageUrl().toString()).isEqualTo(SIGNED_IMAGE);
    }

    @Test
    void proxyLinksKeepTheirOwnBoundedPreviewInsteadOfTheStoredRendition() {
        RuntimeSettingsSource settings = () -> null;
        ImageProxyLinkService proxy = mock(ImageProxyLinkService.class);
        when(proxy.register(SIGNED_IMAGE)).thenReturn(Optional.of(new ImageProxyLinks(
                java.net.URI.create("https://translate.azndev.com/i/0123456789abcdefghij-_"),
                java.net.URI.create("https://translate.azndev.com/i/0123456789abcdefghij-_/preview"))));
        ImageTranslationReply reply = new ImageTranslationReply(
                overlayReply().response(), Optional.of(SIGNED_IMAGE), Optional.of(SIGNED_PREVIEW));

        ImageMessage image = (ImageMessage) new LineMessageRenderer(
                mock(AiModelCatalog.class), settings, proxy, null).imageResult(reply);

        assertThat(image.originalContentUrl().toString())
                .isEqualTo("https://translate.azndev.com/i/0123456789abcdefghij-_");
        assertThat(image.previewImageUrl().toString())
                .isEqualTo("https://translate.azndev.com/i/0123456789abcdefghij-_/preview");
    }

    private static final String SIGNED_IMAGE = "https://s3.azndev.com/line-bot/translated-images/1/image.png"
            + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
            + "&X-Amz-Credential=test%2F20260812%2Fus-east-1%2Fs3%2Faws4_request"
            + "&X-Amz-Date=20260812T071753Z&X-Amz-Expires=3600"
            + "&X-Amz-SignedHeaders=host&X-Amz-Signature=" + "a".repeat(64);

    private static ImageTranslationReply overlayReply() {
        TranslationResponse response = new TranslationResponse(
                "【翻譯圖片（連結 1 小時內有效）】\n" + SIGNED_IMAGE + "\n\n"
                        + "【圖片文字辨識結果】\n\n識別的文字：\nhello\n\n翻譯結果：\n你好\n\n"
                        + "[偵測到: 英文 | 翻譯成: 繁體中文]",
                "你好", "507f1f77bcf86cd799439011", "en", "zh-TW");
        return new ImageTranslationReply(response, Optional.of(SIGNED_IMAGE));
    }

    private static ImageTranslationReply textOnlyReply() {
        TranslationResponse response = new TranslationResponse(
                "【圖片文字辨識結果】\n\n識別的文字：\nhello\n\n翻譯結果：\n你好\n\n"
                        + "[偵測到: 英文 | 翻譯成: 繁體中文]",
                "你好", "507f1f77bcf86cd799439011", "en", "zh-TW");
        return ImageTranslationReply.text(response);
    }

    private static LineMessageRenderer imageRenderer(Boolean imageTextReplyEnabled) {
        RuntimeSettingsSource settings = imageTextReplyEnabled == null
                ? () -> null
                : () -> new RuntimeSettings(
                        "en", "zh-TW", "openai/gpt-4o-mini", true, false, imageTextReplyEnabled,
                        4, 1, null, null, RuntimeSettings.Source.PERSISTED);
        return new LineMessageRenderer(mock(AiModelCatalog.class), settings, null, null);
    }

    @Test
    void overlayImageIsFollowedByTranslationTextWithQuickRepliesOnTheLastMessage() {
        List<com.linecorp.bot.messaging.model.Message> messages =
                imageRenderer(true).imageResultMessages(overlayReply());

        assertThat(messages).hasSize(2);
        ImageMessage image = (ImageMessage) messages.get(0);
        assertThat(image.originalContentUrl().toString()).isEqualTo(SIGNED_IMAGE);
        assertThat(image.quickReply()).isNull();
        TextMessage text = (TextMessage) messages.get(1);
        assertThat(text.text())
                .startsWith("【圖片文字辨識結果】")
                .contains("識別的文字：\nhello", "翻譯結果：\n你好", "[偵測到: 英文 | 翻譯成: 繁體中文]")
                .doesNotContain("X-Amz", "https://", "1 小時內有效");
        assertThat(text.quickReply()).isNotNull();
        assertThat(text.quickReply().items().stream().map(item -> item.action()))
                .anySatisfy(action -> assertThat(action)
                        .isEqualTo(new ClipboardAction("複製譯文", "你好")))
                .anySatisfy(action -> assertThat(action).isInstanceOfSatisfying(
                        PostbackAction.class,
                        postback -> assertThat(postback.data()).contains("retranslate")));
    }

    @Test
    void imageTextReplyDefaultsToOnWhenNoRuntimeSettingsAreAvailable() {
        assertThat(imageRenderer(null).imageResultMessages(overlayReply()))
                .hasSize(2)
                .satisfies(messages -> {
                    assertThat(messages.get(0)).isInstanceOf(ImageMessage.class);
                    assertThat(messages.get(1)).isInstanceOf(TextMessage.class);
                });
    }

    @Test
    void disabledImageTextReplyKeepsTheOverlayImageOnly() {
        List<com.linecorp.bot.messaging.model.Message> messages =
                imageRenderer(false).imageResultMessages(overlayReply());

        assertThat(messages).singleElement().isInstanceOf(ImageMessage.class);
        assertThat(messages.get(0)).isEqualTo(imageRenderer(false).imageResult(overlayReply()));
    }

    @Test
    void imageWithoutOverlayStillRepliesWithTheTextAndQuickRepliesRegardlessOfTheSwitch() {
        for (Boolean enabled : new Boolean[] {true, false}) {
            List<com.linecorp.bot.messaging.model.Message> messages =
                    imageRenderer(enabled).imageResultMessages(textOnlyReply());

            assertThat(messages).singleElement().isInstanceOfSatisfying(TextMessage.class, text -> {
                assertThat(text.text()).contains("識別的文字：\nhello", "翻譯結果：\n你好");
                assertThat(text.quickReply()).isNotNull();
            });
        }
    }

    @Test
    void failedImageReplyStaysASingleTextMessage() {
        ImageTranslationReply failure = ImageTranslationReply.text(
                TranslationResponse.plain("圖片翻譯處理失敗，請稍後再試。"));

        assertThat(imageRenderer(true).imageResultMessages(failure)).singleElement()
                .isInstanceOfSatisfying(TextMessage.class, text -> {
                    assertThat(text.text()).isEqualTo("圖片翻譯處理失敗，請稍後再試。");
                    assertThat(text.quickReply()).isNull();
                });
    }

    @Test
    void veryLongTranslationIsCutToLineTextLimitAndKeepsActions() {
        String translated = "長".repeat(6_000);
        var response = new TranslationResponse(
                translated + "\n\n[偵測到: 英文 | 翻譯成: 繁體中文]", translated,
                "507f1f77bcf86cd799439011", "en", "zh-TW");

        TextMessage rendered = (TextMessage) renderer.translation(response);

        assertThat(codePoints(rendered.text())).isLessThanOrEqualTo(5_000);
        assertThat(rendered.text())
                .startsWith("長".repeat(4_900))
                .endsWith(TRUNCATION_MARKER);
        assertThat(rendered.quickReply().items())
                .anySatisfy(item -> assertThat(item.action()).isInstanceOf(PostbackAction.class))
                .noneSatisfy(item -> assertThat(item.action()).isInstanceOf(ClipboardAction.class));
        assertThat(codePoints(text(renderer.translation(translated)))).isLessThanOrEqualTo(5_000);
    }

    @Test
    void veryLongRecognizedTextIsCutBeforeTheImageTranslation() {
        ImageTranslationPipeline pipeline = mock(ImageTranslationPipeline.class);
        UserProfileRepository profiles = mock(UserProfileRepository.class);
        UserPreferencesModule preferences = mock(UserPreferencesModule.class);
        when(preferences.profile("U-long")).thenReturn(UserProfile.builder().userId("U-long").build());
        when(profiles.save(any(UserProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        String recognized = "字".repeat(50_000);
        ImageTranslationContext context = new ImageTranslationContext(
                new DownloadedImage(new byte[] {1}, "image/png"),
                ImageStorageResult.notStored(), recognized, null);
        TranslationWorkflowResult translation = new TranslationWorkflowResult(
                recognized, "ja", "zh-TW", new AiExecutionResult("短譯文", "openrouter", "model"),
                20, TranslationRequestKind.IMAGE_OCR, "507f1f77bcf86cd799439011");
        when(pipeline.execute(any())).thenReturn(new ImageTranslationOutcome.Success(
                new ImageTranslationPipelineResult(context, translation)));
        ImageTranslationReply reply = new ImageTranslationService(pipeline, profiles, preferences)
                .processImageTranslationReply("U-long", "message");

        TextMessage rendered = (TextMessage) renderer.imageResult(reply);

        assertThat(codePoints(rendered.text())).isLessThanOrEqualTo(5_000);
        assertThat(rendered.text())
                .contains("識別的文字：\n字字字")
                .contains("字" + TRUNCATION_MARKER + "\n\n翻譯結果：\n短譯文\n\n")
                .endsWith("[偵測到: 日文 | 翻譯成: 繁體中文]");
        assertThat(rendered.quickReply().items())
                .anySatisfy(item -> assertThat(item.action())
                        .isEqualTo(new ClipboardAction("複製譯文", "短譯文")));
    }

    @Test
    void cutLandsOnACodePointBoundaryWhenAnEmojiSitsAtTheLimit() {
        int kept = 5_000 - codePoints(TRUNCATION_MARKER);
        // The emoji is the last kept code point; a UTF-16 cut would split its surrogate pair.
        String head = "a".repeat(kept - 1) + "😀";

        String rendered = text(renderer.translation(head + "😀b".repeat(10)));

        assertThat(rendered).isEqualTo(head + TRUNCATION_MARKER);
        assertThat(codePoints(rendered)).isEqualTo(5_000);
        assertThat(rendered.codePoints().noneMatch(
                codePoint -> Character.isSurrogate((char) codePoint))).isTrue();
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String text(com.linecorp.bot.messaging.model.Message message) {
        return ((TextMessage) message).text();
    }
}
