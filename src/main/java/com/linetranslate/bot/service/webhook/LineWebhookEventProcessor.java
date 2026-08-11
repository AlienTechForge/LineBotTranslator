package com.linetranslate.bot.service.webhook;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.webhook.model.Event;
import com.linecorp.bot.webhook.model.ImageMessageContent;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.ReplyEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import com.linetranslate.bot.controller.LineBotController;

/** LINE-specific Adapter between parsed webhook models and application handlers. */
@Component
public class LineWebhookEventProcessor implements WebhookEventProcessor {

    private final LineBotController lineBotController;

    public LineWebhookEventProcessor(LineBotController lineBotController) {
        this.lineBotController = lineBotController;
    }

    @Override
    public Optional<WebhookReply> process(Event event) {
        Message response = route(event);
        if (response == null || !(event instanceof ReplyEvent replyEvent)) {
            return Optional.empty();
        }
        return Optional.of(new WebhookReply(replyEvent.replyToken(), List.of(response)));
    }

    private Message route(Event event) {
        if (event instanceof MessageEvent messageEvent) {
            if (messageEvent.message() instanceof TextMessageContent text) {
                return lineBotController.handleTextMessageEvent(messageEvent, text);
            }
            if (messageEvent.message() instanceof ImageMessageContent image) {
                return lineBotController.handleImageMessageEvent(messageEvent, image);
            }
        }
        lineBotController.handleDefaultMessageEvent(event);
        return null;
    }
}
