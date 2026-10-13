package com.linetranslate.bot.controller;

import java.util.List;

import org.springframework.stereotype.Component;

import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.webhook.model.Event;
import com.linecorp.bot.webhook.model.ImageMessageContent;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.PostbackEvent;
import com.linecorp.bot.webhook.model.Source;
import com.linecorp.bot.webhook.model.TextMessageContent;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.line.LineInteractionModule;
import com.linetranslate.bot.service.line.LineLoadingFeedback;
import com.linetranslate.bot.service.line.LineLoadingSession;
import com.linetranslate.bot.service.line.LineMessageRenderer;
import com.linetranslate.bot.service.line.intent.LineIntent;
import com.linetranslate.bot.service.line.intent.LineIntentParser;

import lombok.extern.slf4j.Slf4j;

/** Thin LINE SDK Adapter. Parsing, execution and rendering live behind Module seams. */
@Component
@Slf4j
public class LineBotController {

    private final LineIntentParser intentParser;
    private final LineInteractionModule interactionModule;
    private final LineLoadingFeedback loadingFeedback;
    private final LineMessageRenderer renderer;

    public LineBotController(
            LineIntentParser intentParser,
            LineInteractionModule interactionModule,
            LineLoadingFeedback loadingFeedback,
            LineMessageRenderer renderer) {
        this.intentParser = intentParser;
        this.interactionModule = interactionModule;
        this.loadingFeedback = loadingFeedback;
        this.renderer = renderer;
    }

    public Message handleTextMessageEvent(MessageEvent event, TextMessageContent content) {
        String userId = event.source().userId();
        if (isUnidentified(userId)) {
            return unidentifiedSender("text", event.source());
        }
        String receivedText = content.text();
        log.info("收到文字訊息: user={}, content={}",
                SafeLog.user(userId), SafeLog.content(receivedText));
        LineIntent intent = intentParser.parseText(receivedText);
        loadingFeedback.beforeText(event.source(), intent);
        return interactionModule.execute(userId, intent);
    }

    public List<Message> handleImageMessageEvent(MessageEvent event, ImageMessageContent content) {
        String userId = event.source().userId();
        if (isUnidentified(userId)) {
            return List.of(unidentifiedSender("image", event.source()));
        }
        String messageId = content.id();
        log.info("收到圖片訊息: user={}, message={}",
                SafeLog.user(userId), SafeLog.content(messageId));
        try (LineLoadingSession ignored = loadingFeedback.startImage(event.source())) {
            return interactionModule.executeImage(userId, messageId);
        }
    }

    public Message handlePostbackEvent(PostbackEvent event) {
        String userId = event.source().userId();
        if (isUnidentified(userId)) {
            return unidentifiedSender("postback", event.source());
        }
        String data = event.postback() == null ? null : event.postback().data();
        log.info("收到 postback: user={}, data={}",
                SafeLog.user(userId), SafeLog.content(data));
        LineIntent intent = intentParser.parsePostback(data);
        loadingFeedback.beforeText(event.source(), intent);
        return interactionModule.execute(userId, intent);
    }

    public void handleDefaultMessageEvent(Event event) {
        log.info("收到未處理的事件: type={}",
                event == null ? "unknown" : event.getClass().getSimpleName());
    }

    /**
     * LINE omits the sender's user ID in group and room events when the sender has not
     * consented to sharing it. Without an identity there is no profile or preference to
     * translate with, so answer with guidance instead of entering any pipeline.
     */
    private Message unidentifiedSender(String eventType, Source source) {
        log.info("略過無法辨識傳送者的事件: event={}, source={}",
                eventType, source.getClass().getSimpleName());
        return renderer.unidentifiedSender();
    }

    private static boolean isUnidentified(String userId) {
        return userId == null || userId.isBlank();
    }
}
