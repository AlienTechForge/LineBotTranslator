package com.linetranslate.bot.service.webhook;

@FunctionalInterface
public interface WebhookReplySender {

    void send(WebhookReply reply);
}
