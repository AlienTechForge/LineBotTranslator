package com.linetranslate.bot.service.webhook;

import java.time.Instant;

import com.linecorp.bot.webhook.model.DeliveryContext;
import com.linecorp.bot.webhook.model.Event;

public record WebhookEventEnvelope(
        Event event,
        String eventId,
        Instant eventTimestamp,
        boolean redelivery) {

    public WebhookEventEnvelope {
        if (event == null) {
            throw new IllegalArgumentException("Webhook event is required");
        }
        if (eventId == null || !eventId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Webhook event ID is missing or invalid");
        }
        if (eventTimestamp == null || eventTimestamp.isBefore(Instant.EPOCH)) {
            throw new IllegalArgumentException("Webhook event timestamp is missing or invalid");
        }
    }

    public static WebhookEventEnvelope from(Event event) {
        if (event == null || event.timestamp() == null) {
            throw new IllegalArgumentException("Webhook event timestamp is missing or invalid");
        }
        DeliveryContext context = event.deliveryContext();
        return new WebhookEventEnvelope(
                event,
                event.webhookEventId(),
                Instant.ofEpochMilli(event.timestamp()),
                context != null && Boolean.TRUE.equals(context.isRedelivery()));
    }
}
