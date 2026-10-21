package com.linetranslate.bot.service.line;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.linecorp.bot.messaging.model.ClipboardAction;
import com.linecorp.bot.messaging.model.ImageMessage;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.PostbackAction;
import com.linecorp.bot.messaging.model.QuickReply;
import com.linecorp.bot.messaging.model.QuickReplyItem;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.ai.AiModelCatalog;
import com.linetranslate.bot.service.ai.AiModelDescriptor;
import com.linetranslate.bot.service.ai.AiModelPage;
import com.linetranslate.bot.service.line.intent.LineIntent;
import com.linetranslate.bot.service.imageproxy.DwzShortLinkService;
import com.linetranslate.bot.service.imageproxy.ImageProxyLinkService;
import com.linetranslate.bot.service.imageproxy.ImageProxyLinks;
import com.linetranslate.bot.service.ocr.ImageTranslationReply;
import com.linetranslate.bot.service.settings.RuntimeSettings;
import com.linetranslate.bot.service.settings.RuntimeSettingsSource;
import com.linetranslate.bot.service.translation.TranslationResponse;
import com.linetranslate.bot.service.translation.TranslationStylePreset;
import com.linetranslate.bot.util.LineTextLimit;

import lombok.extern.slf4j.Slf4j;

/** Central LINE message renderer for user-facing interaction results. */
@Component
@Slf4j
public class LineMessageRenderer {

    private static final int MODEL_PAGE_LIMIT = 20;
    private static final int CLIPBOARD_TEXT_LIMIT = 1_000;
    private static final List<TargetAction> COMMON_TARGETS = List.of(
            new TargetAction("英文", "en"),
            new TargetAction("日文", "ja"),
            new TargetAction("韓文", "ko"),
            new TargetAction("繁中", "zh-TW"));
    private final AiModelCatalog modelCatalog;
    private final RuntimeSettingsSource runtimeSettingsSource;
    private final ImageProxyLinkService imageProxyLinkService;
    private final DwzShortLinkService dwzShortLinkService;

    @Autowired
    public LineMessageRenderer(
            AiModelCatalog modelCatalog,
            RuntimeSettingsSource runtimeSettingsSource,
            ImageProxyLinkService imageProxyLinkService,
            DwzShortLinkService dwzShortLinkService) {
        this.modelCatalog = modelCatalog;
        this.runtimeSettingsSource = runtimeSettingsSource;
        this.imageProxyLinkService = imageProxyLinkService;
        this.dwzShortLinkService = dwzShortLinkService;
    }

    public Message help() {
        return text("🤖 LINE 翻譯機器人幫助\n\n"
                + "[💬 基本使用]\n"
                + "• 直接發送文字 → 自動檢測語言並翻譯\n"
                + "• 發送圖片 → 翻譯圖片中的文字，回傳翻譯後的圖片\n"
                + "• 快速翻譯:[語言代碼] [文本] → 翻譯到指定語言\n\n"
                + "[⚙️ 設置命令]\n"
                + "🔠 /外文翻譯 [語言] - 設置偏好的目標語言\n"
                + "🀄 /中文翻譯 [語言] - 設置中文翻譯的目標語言\n"
                + "🤖 /model [OpenRouter 模型 slug] - 指定模型\n"
                + "📋 /models [關鍵字] - 搜尋可用模型\n\n"
                + "🎨 /styles - 列出翻譯風格\n"
                + "🎨 /style [ID] - 設定預設風格\n"
                + "✨ /translate-style [ID] [文字] - 僅本次使用指定風格\n\n"
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

    public Message styles() {
        StringBuilder body = new StringBuilder("🎨 可用翻譯風格\n\n");
        for (TranslationStylePreset preset : TranslationStylePreset.available()) {
            body.append("• ").append(preset.id()).append(" — ")
                    .append(preset.localizedName("zh-TW")).append("\n");
        }
        body.append("\n/style [ID] 設定預設；/translate-style [ID] [文字] 僅套用一次。");
        return text(body.toString());
    }

    public Message translation(String result) { return text(result); }
    public Message translation(TranslationResponse result) { return interactiveTranslation(result); }
    public Message status(String result) { return text(result); }
    public Message profile(String result) { return text(result); }
    public Message settingResult(String result) { return text(result); }
    public Message imageResult(String result) { return text(result); }
    public Message imageResult(TranslationResponse result) { return interactiveTranslation(result); }
    public Message imageResult(ImageTranslationReply reply) {
        return deliver(reply).message();
    }

    /**
     * Picks how the image reply is delivered: proxy links, the signed MinIO URL (optionally
     * shortened), or plain text when there is no safe generated image. Pure apart from the proxy
     * and short-link calls; {@link #imageResultMessages} logs the choice.
     */
    private ImageDelivery deliver(ImageTranslationReply reply) {
        if (reply == null) {
            return ImageDelivery.textFallback(text(""), "no_reply");
        }
        Optional<URI> signedImage = reply.renderedImageUrl()
                .flatMap(LineMessageRenderer::safeHttpsUri);
        if (signedImage.isEmpty()) {
            return ImageDelivery.textFallback(
                    interactiveTranslation(reply.response()),
                    reply.renderedImageUrl().isPresent() ? "unsafe_image_url" : "no_rendered_image");
        }
        RuntimeSettings settings = runtimeSettingsSource.current();
        boolean shorten = settings != null && settings.shortUrlEnabled();
        if (imageProxyLinkService != null) {
            Optional<ImageProxyLinks> links = reply.renderedImageUrl()
                    .flatMap(imageProxyLinkService::register);
            if (links.isPresent()) {
                ImageProxyLinks selected = links.get();
                ImageProxyLinks clean = selected;
                if (shorten && dwzShortLinkService != null) {
                    selected = dwzShortLinkService.shorten(selected).orElse(selected);
                }
                return new ImageDelivery(
                        new ImageMessage(selected.original(), selected.preview()),
                        ImageBranch.PROXY_LINKS, "none", selected.original().getHost(),
                        true, selected != clean);
            }
        }
        URI direct = signedImage.get();
        URI selected = direct;
        if (shorten && dwzShortLinkService != null) {
            selected = dwzShortLinkService.shortenSignedImage(selected).orElse(selected);
        }
        // Without the proxy nothing resizes the original, and LINE rejects a preview above
        // 1 MB. The storage service saved a 240 px rendition for that case; it stays a signed
        // URL because LINE's servers are the only ones that fetch it.
        Optional<URI> storedPreview = reply.renderedPreviewUrl().flatMap(LineMessageRenderer::safeHttpsUri);
        return new ImageDelivery(
                new ImageMessage(selected, storedPreview.orElse(selected)),
                ImageBranch.SIGNED_IMAGE, "none", selected.getHost(),
                storedPreview.isPresent(), selected != direct);
    }

    /**
     * Image reply as an ordered message list: the picture, then the warning line when the picture is
     * only usable ({@code tier: warned}), then, when the administrator switched
     * {@code imageTextReplyEnabled} on (it is off by default: picture-only replies), the recognised
     * text and translation with their quick replies. Quick replies only show on the last message.
     *
     * <p>Logs which delivery branch was taken, with the image host (never the URL: a signed URL
     * carries its signature) and whether the stored preview was available, because a LINE image
     * that fails to load leaves no other trace in this service.
     */
    public List<Message> imageResultMessages(ImageTranslationReply reply) {
        ImageDelivery delivery = deliver(reply);
        Message primary = delivery.message();
        boolean picture = primary instanceof ImageMessage;
        boolean warning = picture && reply.warning().isPresent();
        boolean textReply = picture && imageTextReplyEnabled();
        List<Message> messages = new ArrayList<>();
        messages.add(primary);
        if (warning) {
            messages.add(text(reply.warning().get()));
        }
        if (textReply) {
            messages.add(interactiveTranslation(withoutImageLink(reply)));
        }
        String host = delivery.imageHost() == null ? "none" : SafeLog.metadata(delivery.imageHost());
        log.info("Image reply composed: branch={}, reason={}, imageHost={}, previewUrlPresent={}, "
                        + "shortened={}, warning={}, textReply={}, messages={}",
                delivery.branch(), delivery.reason(), host,
                delivery.previewUrlPresent(), delivery.shortened(), warning, textReply, messages.size());
        return List.copyOf(messages);
    }

    private boolean imageTextReplyEnabled() {
        RuntimeSettings settings = runtimeSettingsSource.current();
        return settings != null && settings.imageTextReplyEnabled();
    }

    /** The image message already shows the artifact, so its signed link is not repeated in text. */
    private static TranslationResponse withoutImageLink(ImageTranslationReply reply) {
        TranslationResponse response = reply.response();
        if (response.displayText() == null || reply.renderedImageUrl().isEmpty()) {
            return response;
        }
        String display = response.displayText().replaceFirst(
                "^[^\\n]*\\n" + Pattern.quote(reply.renderedImageUrl().get()) + "\\n\\n", "");
        return new TranslationResponse(
                display,
                response.translatedText(),
                response.recordId(),
                response.sourceLanguage(),
                response.targetLanguage(),
                response.stylePresetId(),
                response.stylePromptVersion());
    }

    private static Optional<URI> safeHttpsUri(String raw) {
        try {
            URI uri = URI.create(raw);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null
                    ? Optional.of(uri) : Optional.empty();
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public Message imageFailure() {
        return text("圖片處理失敗。\n請確保圖片清晰且包含可識別的文字，或稍後再試。");
    }

    public Message unidentifiedSender() {
        return text("⚠️ 無法辨識此聊天室中的傳送者，因此無法為您翻譯。\n"
                + "請先將本機器人加為好友並同意 LINE 使用條款，或直接在一對一聊天中使用。");
    }

    public Message invalid(LineIntent.Invalid invalid) {
        return text(switch (invalid.reason()) {
            case MODEL_REQUIRED -> "請指定完整 OpenRouter 模型 slug。例如：/model openai/gpt-4o-mini";
            case INVALID_MODEL -> "模型 slug 格式無效。請先用 /models [關鍵字] 查詢完整 slug。";
            case MODEL_QUERY_TOO_LONG -> "模型搜尋關鍵字過長；請縮短至 80 個字元內。";
            case TRANSLATION_ACTION_FORMAT -> "翻譯操作已失效，請從新的翻譯結果重試。";
            case STYLE_REQUIRED -> "請指定風格 ID。先用 /styles 查看可用風格。";
            case INVALID_STYLE -> "不支援的翻譯風格：" + invalid.value() + "。請用 /styles 查看可用風格。";
            case STYLED_TRANSLATION_FORMAT ->
                    "格式錯誤。請使用 /translate-style [風格 ID] [要翻譯的文字]。";
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
        return new TextMessage(lineText(value));
    }

    /** LINE rejects an oversized text message outright, so every text sent here is fitted. */
    private static String lineText(String value) {
        return value == null ? "" : LineTextLimit.fit(value);
    }

    private Message interactiveTranslation(TranslationResponse response) {
        if (response == null || !response.actionable()) {
            return text(response == null ? "" : response.displayText());
        }

        List<QuickReplyItem> items = new ArrayList<>();
        String translatedText = response.translatedText();
        if (translatedText.codePointCount(0, translatedText.length()) <= CLIPBOARD_TEXT_LIMIT) {
            items.add(new QuickReplyItem(new ClipboardAction("複製譯文", translatedText)));
        }
        for (TargetAction target : COMMON_TARGETS) {
            if (!target.language().equalsIgnoreCase(response.targetLanguage())) {
                items.add(postbackItem(
                        target.label(),
                        "改翻譯為" + target.label(),
                        response.recordId(),
                        target.language()));
            }
        }
        items.add(postbackItem(
                "重新翻譯",
                "重新翻譯",
                response.recordId(),
                response.targetLanguage()));
        for (TranslationStylePreset preset : TranslationStylePreset.available()) {
            if (!preset.id().equals(response.stylePresetId())) {
                items.add(stylePostbackItem(
                        preset.localizedName("zh-TW"),
                        response.recordId(),
                        preset.id()));
            }
        }

        return new TextMessage.Builder(lineText(response.displayText()))
                .quickReply(new QuickReply(items))
                .build();
    }

    private QuickReplyItem stylePostbackItem(
            String label,
            String recordId,
            String presetId) {
        String command = "/restyle " + recordId + " " + presetId;
        String data = "command=" + URLEncoder.encode(command, StandardCharsets.UTF_8);
        return new QuickReplyItem(new PostbackAction(
                label, data, "改用「" + label + "」風格", null, null, null));
    }

    private QuickReplyItem postbackItem(
            String label,
            String displayText,
            String recordId,
            String targetLanguage) {
        String command = "/retranslate " + recordId + " " + targetLanguage;
        String data = "command=" + URLEncoder.encode(command, StandardCharsets.UTF_8);
        return new QuickReplyItem(new PostbackAction(
                label, data, displayText, null, null, null));
    }

    private record TargetAction(String label, String language) {
    }

    private enum ImageBranch {
        /** Clean {@code /i/{token}} links served by the image proxy. */
        PROXY_LINKS,
        /** The signed MinIO URL, or its short link, handed to LINE directly. */
        SIGNED_IMAGE,
        /** No safe generated image: the reply is text only. */
        TEXT_FALLBACK
    }

    /**
     * The chosen image reply and what to log about the choice. {@code imageHost} is the host LINE
     * is told to fetch the original from, or {@code null} for the text fallback.
     */
    private record ImageDelivery(
            Message message,
            ImageBranch branch,
            String reason,
            String imageHost,
            boolean previewUrlPresent,
            boolean shortened) {

        static ImageDelivery textFallback(Message message, String reason) {
            return new ImageDelivery(message, ImageBranch.TEXT_FALLBACK, reason, null, false, false);
        }
    }
}
