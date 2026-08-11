package com.linetranslate.bot.controller;

import org.springframework.stereotype.Component;

import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.webhook.model.Event;
import com.linecorp.bot.webhook.model.ImageMessageContent;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.PostbackEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.line.LineInteractionModule;
import com.linetranslate.bot.service.line.LineLoadingFeedback;
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

    public LineBotController(
            LineIntentParser intentParser,
            LineInteractionModule interactionModule,
            LineLoadingFeedback loadingFeedback) {
        this.intentParser = intentParser;
        this.interactionModule = interactionModule;
        this.loadingFeedback = loadingFeedback;
    }

    public Message handleTextMessageEvent(MessageEvent event, TextMessageContent content) {
        String userId = event.source().userId();
        String receivedText = content.text();
        log.info("收到文字訊息: user={}, content={}",
                SafeLog.user(userId), SafeLog.content(receivedText));
        LineIntent intent = intentParser.parseText(receivedText);
        loadingFeedback.beforeText(event.source(), intent);
        return interactionModule.execute(userId, intent);
    }

    public Message handleImageMessageEvent(MessageEvent event, ImageMessageContent content) {
        String userId = event.source().userId();
        String messageId = content.id();
        log.info("收到圖片訊息: user={}, message={}",
                SafeLog.user(userId), SafeLog.content(messageId));
        loadingFeedback.beforeImage(event.source());
        return interactionModule.executeImage(userId, messageId);
    }

    public Message handlePostbackEvent(PostbackEvent event) {
        String userId = event.source().userId();
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
}
