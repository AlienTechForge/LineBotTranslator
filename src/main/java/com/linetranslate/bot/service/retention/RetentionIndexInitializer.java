package com.linetranslate.bot.service.retention;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexOptions;
import org.springframework.stereotype.Component;

import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.model.TranslationActionClaim;
import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.service.usage.AiUsageEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * Bounds the growth of {@code translation_records}, {@code translation_action_claims}
 * and {@code ai_usage_events} with one TTL index each. Spring Data auto index
 * creation is off, so the indexes are created here once the application is ready.
 *
 * <p>Every run converges on the configured retention and is safe to repeat:
 * <ul>
 * <li>No index on the key: a TTL index is created.</li>
 * <li>An index on the same key without TTL, for example the plain
 * {@code translation_record_created_at} index of {@code AdminStatsIndexInitializer},
 * whichever initializer ran first: it is converted in place with {@code collMod}
 * (MongoDB 5.1+). Only when the server refuses the conversion is our own index
 * dropped and created again.</li>
 * <li>An index on the same key with another TTL: {@code collMod} updates it.</li>
 * </ul>
 * MongoDB refuses a second index on one key pattern with other options, so the
 * {@code translation_records} index uses the key pattern and name of that
 * statistics index, and that initializer leaves an index that already has a TTL
 * alone. A blank value or {@code PT0S} disables a collection: nothing is
 * created and a TTL index of ours that already exists is left alone and logged.
 * A failure is logged and never stops startup.
 */
@Component
@Slf4j
public class RetentionIndexInitializer {

    static final String TRANSLATION_RECORD_INDEX_NAME = "translation_record_created_at";
    static final String TRANSLATION_ACTION_CLAIM_INDEX_NAME = "translation_action_claim_ttl";
    static final String AI_USAGE_EVENT_INDEX_NAME = "ai_usage_event_ttl";

    private final MongoTemplate mongoTemplate;
    private final RetentionProperties properties;

    public RetentionIndexInitializer(MongoTemplate mongoTemplate, RetentionProperties properties) {
        this.mongoTemplate = mongoTemplate;
        this.properties = properties;
    }

    /** Idempotent; each collection is handled on its own so one failure spares the others. */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureRetentionIndexes() {
        // Same {createdAt: -1} key pattern as the statistics index of the same name.
        ensure(new Policy(TranslationRecord.class, "createdAt", Sort.Direction.DESC,
                TRANSLATION_RECORD_INDEX_NAME, "app.retention.translation-records",
                properties.getTranslationRecords()));
        ensure(new Policy(TranslationActionClaim.class, "createdAt", Sort.Direction.ASC,
                TRANSLATION_ACTION_CLAIM_INDEX_NAME, "app.retention.translation-action-claims",
                properties.getTranslationActionClaims()));
        // Ascending on purpose: usage_occurred_at in AiUsageAccountingModule is a
        // descending, non-TTL index on occurredAt and must stay creatable beside this one.
        ensure(new Policy(AiUsageEvent.class, "occurredAt", Sort.Direction.ASC,
                AI_USAGE_EVENT_INDEX_NAME, "app.retention.ai-usage-events",
                properties.getAiUsageEvents()));
    }

    /**
     * Reads an ISO-8601 retention. Empty means retention is disabled (blank or a
     * zero duration); a value that cannot be a TTL index setting is rejected.
     */
    static Optional<Duration> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        Duration retention;
        try {
            retention = Duration.parse(value.strip());
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException(
                    "Retention must be an ISO-8601 duration such as P90D", invalid);
        }
        if (retention.isZero()) {
            return Optional.empty();
        }
        if (retention.isNegative()) {
            throw new IllegalArgumentException("Retention must be positive");
        }
        if (retention.getNano() != 0) {
            throw new IllegalArgumentException("Retention must be a whole number of seconds");
        }
        if (retention.getSeconds() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Retention exceeds the MongoDB TTL limit");
        }
        return Optional.of(retention);
    }

    private void ensure(Policy policy) {
        try {
            Optional<Duration> retention = parse(policy.configured());
            IndexOperations indexOps = mongoTemplate.indexOps(policy.type());
            List<IndexInfo> indexes = mongoTemplate.collectionExists(policy.type())
                    ? indexOps.getIndexInfo()
                    : List.of();
            if (retention.isEmpty()) {
                reportDisabled(policy, indexes);
            } else {
                converge(policy, indexOps, indexes, retention.get());
            }
        } catch (RuntimeException exception) {
            log.warn("無法設定資料保留 TTL 索引，該集合不會自動清除舊資料: index={}, property={}, failure={}",
                    policy.indexName(), policy.property(), SafeLog.failure(exception));
        }
    }

    private void reportDisabled(Policy policy, List<IndexInfo> indexes) {
        Optional<Duration> remaining = indexes.stream()
                .filter(index -> policy.indexName().equals(index.getName()))
                .findFirst()
                .flatMap(IndexInfo::getExpireAfter);
        if (remaining.isPresent()) {
            log.info("資料保留已停用但 TTL 索引仍在，舊資料仍會被清除，需移除請手動刪除索引: index={}, expireAfterSeconds={}",
                    policy.indexName(), remaining.get().getSeconds());
        } else {
            log.info("資料保留已停用: index={}, property={}", policy.indexName(), policy.property());
        }
    }

    private void converge(
            Policy policy,
            IndexOperations indexOps,
            List<IndexInfo> indexes,
            Duration retention) {
        IndexInfo existing = indexes.stream().filter(policy::onKey).findFirst().orElse(null);
        if (existing == null) {
            create(policy, indexOps, retention);
            log.info("資料保留 TTL 索引已建立: index={}, expireAfterSeconds={}",
                    policy.indexName(), retention.getSeconds());
            return;
        }
        Optional<Duration> current = existing.getExpireAfter();
        if (current.filter(retention::equals).isPresent()) {
            log.info("資料保留 TTL 索引已就緒: index={}, expireAfterSeconds={}",
                    SafeLog.metadata(existing.getName()), retention.getSeconds());
            return;
        }
        try {
            indexOps.alterIndex(existing.getName(), IndexOptions.expireAfter(retention));
            log.info("資料保留 TTL 索引已更新: index={}, expireAfterSeconds={}",
                    SafeLog.metadata(existing.getName()), retention.getSeconds());
        } catch (DataAccessException refused) {
            if (current.isPresent() || !policy.indexName().equals(existing.getName())) {
                throw refused;
            }
            // collMod cannot add a TTL to an existing index before MongoDB 5.1.
            log.warn("MongoDB 無法就地轉換為 TTL 索引，改為刪除後重建: index={}, failure={}",
                    policy.indexName(), SafeLog.failure(refused));
            indexOps.dropIndex(existing.getName());
            create(policy, indexOps, retention);
            log.info("資料保留 TTL 索引已重建: index={}, expireAfterSeconds={}",
                    policy.indexName(), retention.getSeconds());
        }
    }

    private void create(Policy policy, IndexOperations indexOps, Duration retention) {
        indexOps.createIndex(new Index()
                .named(policy.indexName())
                .on(policy.field(), policy.direction())
                .expire(retention));
    }

    private record Policy(
            Class<?> type,
            String field,
            Sort.Direction direction,
            String indexName,
            String property,
            String configured) {

        boolean onKey(IndexInfo index) {
            List<IndexField> fields = index.getIndexFields();
            return fields.size() == 1
                    && field.equals(fields.get(0).getKey())
                    && direction == fields.get(0).getDirection();
        }
    }
}
