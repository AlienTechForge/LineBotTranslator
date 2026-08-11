package com.linetranslate.bot.service.webhook;

import java.util.List;

import com.linecorp.bot.messaging.model.Message;

public record WebhookReply(String replyToken, List<Message> messages) {

    public WebhookReply {
        if (replyToken == null || replyToken.isBlank()) {
            throw new IllegalArgumentException("Webhook reply requires a reply token");
        }
        if (messages == null || messages.isEmpty() || messages.size() > 5
                || messages.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Webhook reply requires between one and five messages");
        }
        messages = List.copyOf(messages);
    }
}
