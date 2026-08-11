package com.linetranslate.bot.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.bot.webhook.model.DeliveryContext;
import com.linecorp.bot.webhook.model.Event;

@SpringBootTest
@ActiveProfiles("test")
class WebhookEventReceiptStoreIntegrationTests {

    private static final String COLLECTION = "webhook_event_receipts";

    @Autowired
    private MongoTemplate mongoTemplate;

    private MutableClock clock;
    private WebhookIngestionProperties properties;
    private MongoWebhookEventReceiptStore store;

    @BeforeEach
    void setUp() {
        if (mongoTemplate.collectionExists(COLLECTION)) {
            mongoTemplate.dropCollection(COLLECTION);
        }
        clock = new MutableClock(Instant.parse("2026-08-10T00:00:00Z"));
        properties = new WebhookIngestionProperties();
        properties.setReceiptTtl(Duration.ofDays(7));
        properties.setProcessingLease(Duration.ofMinutes(5));
        store = new MongoWebhookEventReceiptStore(mongoTemplate, properties, clock);
    }

    @Test
    void atomicClaimPersistsMetadataAndRejectsDuplicateAfterCompletion() {
        WebhookEventEnvelope envelope = envelope("event-once", 2_000L, false);

        WebhookClaim first = store.claim(envelope);
        WebhookClaim duplicateWhileQueued = store.claim(envelope);
        store.markProcessing(first);
        store.markCompleted(first, 1);
        WebhookClaim duplicateAfterCompletion = store.claim(envelope);

        assertThat(first.claimed()).isTrue();
        assertThat(duplicateWhileQueued.claimed()).isFalse();
        assertThat(duplicateAfterCompletion.claimed()).isFalse();
        Document receipt = mongoTemplate.getCollection(COLLECTION)
                .find(new Document("_id", "event-once"))
                .first();
        assertThat(receipt).isNotNull();
        assertThat(receipt.getString("status")).isEqualTo("COMPLETED");
        assertThat(receipt.getInteger("replyAttempts")).isEqualTo(1);
        assertThat(receipt.keySet())
                .doesNotContain("payload", "text", "sourceText", "replyToken", "userId");
    }

    @Test
    void expiredProcessingLeaseCanBeReclaimedAfterAWorkerCrash() {
        WebhookEventEnvelope original = envelope("event-lease", 2_000L, false);
        WebhookClaim first = store.claim(original);
        store.markProcessing(first);

        clock.advance(Duration.ofMinutes(6));
        WebhookClaim reclaimed = store.claim(envelope("event-lease", 2_000L, true));

        assertThat(reclaimed.claimed()).isTrue();
        assertThat(reclaimed.claimToken()).isNotEqualTo(first.claimToken());
    }

    @Test
    void queueRejectionReleaseAllowsImmediateSafeRedelivery() {
        WebhookEventEnvelope original = envelope("event-release", 2_000L, false);
        WebhookClaim first = store.claim(original);

        store.release(first);
        WebhookClaim redelivery = store.claim(envelope("event-release", 2_000L, true));

        assertThat(redelivery.claimed()).isTrue();
    }

    @Test
    void uniqueOutOfOrderEventsRemainIndependentAndTtlIndexExists() {
        WebhookClaim newer = store.claim(envelope("event-newer", 3_000L, false));
        WebhookClaim older = store.claim(envelope("event-older", 1_000L, true));

        assertThat(newer.claimed()).isTrue();
        assertThat(older.claimed()).isTrue();
        assertThat(mongoTemplate.getCollection(COLLECTION).listIndexes().into(new ArrayList<>()))
                .anySatisfy(index -> {
                    assertThat(index.get("key", Document.class)).containsEntry("expiresAt", 1);
                    assertThat(((Number) index.get("expireAfterSeconds")).longValue()).isZero();
                });
    }

    private static WebhookEventEnvelope envelope(String eventId, long timestamp, boolean redelivery) {
        Event event = mock(Event.class);
        when(event.webhookEventId()).thenReturn(eventId);
        when(event.timestamp()).thenReturn(timestamp);
        when(event.deliveryContext()).thenReturn(new DeliveryContext(redelivery));
        return WebhookEventEnvelope.from(event);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
