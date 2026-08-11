package com.linetranslate.bot.service.webhook;

public interface WebhookEventReceiptStore {

    WebhookClaim claim(WebhookEventEnvelope envelope);

    void release(WebhookClaim claim);

    void markProcessing(WebhookClaim claim);

    void markCompleted(WebhookClaim claim, int replyAttempts);

    void markPoisoned(WebhookClaim claim, String failureType, int replyAttempts);
}
