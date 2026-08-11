package com.linetranslate.bot.service.webhook;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document("webhook_event_receipts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class WebhookEventReceipt {

    @Id
    private String webhookEventId;

    private Instant eventTimestamp;
    private boolean redelivery;
    private WebhookReceiptStatus status;
    private String claimToken;
    private Instant receivedAt;
    private Instant updatedAt;
    private Instant leaseUntil;

    @Indexed(expireAfter = "0s")
    private Instant expiresAt;

    private int replyAttempts;
    private String failureType;
}
