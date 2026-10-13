package com.linetranslate.bot.service.webhook;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.webhook.model.Event;
import com.linecorp.bot.webhook.model.GroupSource;
import com.linecorp.bot.webhook.model.ImageMessageContent;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.PostbackEvent;
import com.linecorp.bot.webhook.model.ReplyEvent;
import com.linecorp.bot.webhook.model.RoomSource;
import com.linecorp.bot.webhook.model.Source;
import com.linecorp.bot.webhook.model.TextMessageContent;
import com.linecorp.bot.webhook.model.UserSource;
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
        List<Message> response = route(event);
        if (response == null || response.isEmpty() || !(event instanceof ReplyEvent replyEvent)) {
            return Optional.empty();
        }
        return Optional.of(new WebhookReply(
                replyEvent.replyToken(), response, pushTarget(event.source())));
    }

    /** The chat the answer can be pushed to if the reply token has expired. */
    private static String pushTarget(Source source) {
        if (source instanceof UserSource user) {
            return user.userId();
        }
        if (source instanceof GroupSource group) {
            return group.groupId();
        }
        if (source instanceof RoomSource room) {
            return room.roomId();
        }
        return null;
    }

    private List<Message> route(Event event) {
        if (event instanceof PostbackEvent postbackEvent) {
            return single(lineBotController.handlePostbackEvent(postbackEvent));
        }
        if (event instanceof MessageEvent messageEvent) {
            if (messageEvent.message() instanceof TextMessageContent text) {
                return single(lineBotController.handleTextMessageEvent(messageEvent, text));
            }
            if (messageEvent.message() instanceof ImageMessageContent image) {
                return lineBotController.handleImageMessageEvent(messageEvent, image);
            }
        }
        lineBotController.handleDefaultMessageEvent(event);
        return null;
    }

    private static List<Message> single(Message message) {
        return message == null ? null : List.of(message);
    }
}
