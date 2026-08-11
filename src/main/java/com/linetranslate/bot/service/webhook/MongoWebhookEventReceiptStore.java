package com.linetranslate.bot.service.webhook;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.mongodb.client.result.UpdateResult;

/**
 * Mongo-backed receipt Store with atomic claims, a reclaimable processing lease
 * and a TTL index. User payload and reply tokens are deliberately not stored.
 */
@Component
public class MongoWebhookEventReceiptStore implements WebhookEventReceiptStore {

    private static final String TTL_INDEX_NAME = "webhook_receipt_ttl";

    private final MongoTemplate mongoTemplate;
    private final WebhookIngestionProperties properties;
    private final Clock clock;
    private final AtomicBoolean ttlIndexReady = new AtomicBoolean();

    @Autowired
    public MongoWebhookEventReceiptStore(
            MongoTemplate mongoTemplate,
            WebhookIngestionProperties properties) {
        this(mongoTemplate, properties, Clock.systemUTC());
    }

    MongoWebhookEventReceiptStore(
            MongoTemplate mongoTemplate,
            WebhookIngestionProperties properties,
            Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.properties = properties;
        this.clock = clock;
        validate(properties);
    }

    @Override
    public WebhookClaim claim(WebhookEventEnvelope envelope) {
        ensureTtlIndex();
        Instant now = clock.instant();
        String token = UUID.randomUUID().toString();
        WebhookEventReceipt receipt = receipt(envelope, token, now);
        try {
            mongoTemplate.insert(receipt);
            return WebhookClaim.claimed(envelope.eventId(), token);
        } catch (DuplicateKeyException duplicate) {
            return reclaimExpired(envelope, token, now);
        }
    }

    @Override
    public void release(WebhookClaim claim) {
        requireClaimed(claim);
        mongoTemplate.remove(Query.query(activeClaim(claim)
                .and("status").is(WebhookReceiptStatus.QUEUED)), WebhookEventReceipt.class);
    }

    @Override
    public void markProcessing(WebhookClaim claim) {
        requireClaimed(claim);
        Instant now = clock.instant();
        Update update = new Update()
                .set("status", WebhookReceiptStatus.PROCESSING)
                .set("updatedAt", now)
                .set("leaseUntil", now.plus(properties.getProcessingLease()));
        assertUpdated(mongoTemplate.updateFirst(
                Query.query(activeClaim(claim).and("status").is(WebhookReceiptStatus.QUEUED)),
                update,
                WebhookEventReceipt.class), claim);
    }

    @Override
    public void markCompleted(WebhookClaim claim, int replyAttempts) {
        finish(claim, WebhookReceiptStatus.COMPLETED, null, replyAttempts);
    }

    @Override
    public void markPoisoned(WebhookClaim claim, String failureType, int replyAttempts) {
        String safeFailureType = failureType == null
                ? "UnknownFailure"
                : failureType.replaceAll("[^A-Za-z0-9_.$-]", "_");
        if (safeFailureType.length() > 128) {
            safeFailureType = safeFailureType.substring(0, 128);
        }
        finish(claim, WebhookReceiptStatus.POISONED, safeFailureType, replyAttempts);
    }

    private WebhookClaim reclaimExpired(
            WebhookEventEnvelope envelope,
            String token,
            Instant now) {
        Criteria expiredLease = new Criteria().andOperator(
                Criteria.where("status").in(
                        WebhookReceiptStatus.QUEUED,
                        WebhookReceiptStatus.PROCESSING),
                Criteria.where("leaseUntil").lte(now));
        Criteria reclaimable = new Criteria().orOperator(
                Criteria.where("expiresAt").lte(now),
                expiredLease);
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(envelope.eventId()),
                reclaimable));
        Update update = new Update()
                .set("eventTimestamp", envelope.eventTimestamp())
                .set("redelivery", envelope.redelivery())
                .set("status", WebhookReceiptStatus.QUEUED)
                .set("claimToken", token)
                .set("receivedAt", now)
                .set("updatedAt", now)
                .set("leaseUntil", now.plus(properties.getProcessingLease()))
                .set("expiresAt", now.plus(properties.getReceiptTtl()))
                .set("replyAttempts", 0)
                .unset("failureType");
        WebhookEventReceipt reclaimed = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                WebhookEventReceipt.class);
        return reclaimed == null
                ? WebhookClaim.duplicate(envelope.eventId())
                : WebhookClaim.claimed(envelope.eventId(), token);
    }

    private void finish(
            WebhookClaim claim,
            WebhookReceiptStatus status,
            String failureType,
            int replyAttempts) {
        requireClaimed(claim);
        if (replyAttempts < 0) {
            throw new IllegalArgumentException("Webhook reply attempts cannot be negative");
        }
        Instant now = clock.instant();
        Update update = new Update()
                .set("status", status)
                .set("updatedAt", now)
                .set("expiresAt", now.plus(properties.getReceiptTtl()))
                .set("replyAttempts", replyAttempts)
                .unset("leaseUntil");
        if (failureType == null) {
            update.unset("failureType");
        } else {
            update.set("failureType", failureType);
        }
        assertUpdated(mongoTemplate.updateFirst(
                Query.query(activeClaim(claim).and("status").is(WebhookReceiptStatus.PROCESSING)),
                update,
                WebhookEventReceipt.class), claim);
    }

    private WebhookEventReceipt receipt(
            WebhookEventEnvelope envelope,
            String token,
            Instant now) {
        return WebhookEventReceipt.builder()
                .webhookEventId(envelope.eventId())
                .eventTimestamp(envelope.eventTimestamp())
                .redelivery(envelope.redelivery())
                .status(WebhookReceiptStatus.QUEUED)
                .claimToken(token)
                .receivedAt(now)
                .updatedAt(now)
                .leaseUntil(now.plus(properties.getProcessingLease()))
                .expiresAt(now.plus(properties.getReceiptTtl()))
                .build();
    }

    private Criteria activeClaim(WebhookClaim claim) {
        return Criteria.where("_id").is(claim.eventId())
                .and("claimToken").is(claim.claimToken());
    }

    private void ensureTtlIndex() {
        if (ttlIndexReady.get()) {
            return;
        }
        mongoTemplate.indexOps(WebhookEventReceipt.class).createIndex(
                new Index()
                        .named(TTL_INDEX_NAME)
                        .on("expiresAt", Sort.Direction.ASC)
                        .expire(Duration.ZERO));
        ttlIndexReady.set(true);
    }

    private void assertUpdated(UpdateResult result, WebhookClaim claim) {
        if (result.getMatchedCount() != 1) {
            throw new IllegalStateException(
                    "Webhook claim is no longer active: " + claim.eventId());
        }
    }

    private static void requireClaimed(WebhookClaim claim) {
        if (claim == null || !claim.claimed()) {
            throw new IllegalArgumentException("An active webhook claim is required");
        }
    }

    private static void validate(WebhookIngestionProperties properties) {
        if (properties.getReceiptTtl() == null || properties.getReceiptTtl().isZero()
                || properties.getReceiptTtl().isNegative()) {
            throw new IllegalArgumentException("Webhook receipt TTL must be positive");
        }
        if (properties.getProcessingLease() == null || properties.getProcessingLease().isZero()
                || properties.getProcessingLease().isNegative()) {
            throw new IllegalArgumentException("Webhook processing lease must be positive");
        }
        if (properties.getReceiptTtl().compareTo(properties.getProcessingLease()) <= 0) {
            throw new IllegalArgumentException("Webhook receipt TTL must exceed the processing lease");
        }
    }
}
