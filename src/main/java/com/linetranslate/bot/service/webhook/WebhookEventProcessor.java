package com.linetranslate.bot.service.webhook;

import java.util.Optional;

import com.linecorp.bot.webhook.model.Event;

@FunctionalInterface
public interface WebhookEventProcessor {

    Optional<WebhookReply> process(Event event);
}
