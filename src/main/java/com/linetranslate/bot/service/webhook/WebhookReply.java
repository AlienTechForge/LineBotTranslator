package com.linetranslate.bot.service.webhook;

import java.util.List;

import com.linecorp.bot.messaging.model.Message;

/**
 * Answer to one webhook event.
 *
 * @param pushTarget user, group or room ID the answer can be pushed to when the
 *        reply token has expired, or {@code null} when the source is unknown
 */
public record WebhookReply(String replyToken, List<Message> messages, String pushTarget) {

    public WebhookReply(String replyToken, List<Message> messages) {
        this(replyToken, messages, null);
    }

    public WebhookReply {
        if (replyToken == null || replyToken.isBlank()) {
            throw new IllegalArgumentException("Webhook reply requires a reply token");
        }
        if (messages == null || messages.isEmpty() || messages.size() > 5
                || messages.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Webhook reply requires between one and five messages");
        }
        messages = List.copyOf(messages);
        if (pushTarget != null && pushTarget.isBlank()) {
            pushTarget = null;
        }
    }
}
