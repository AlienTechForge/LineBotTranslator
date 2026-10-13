package com.linetranslate.bot.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.model.UserProfile;

import lombok.extern.slf4j.Slf4j;

/**
 * Creates the indexes behind the admin statistics queries: the {@code createdAt}
 * range of {@code translation_records}, the {@code lastInteractionAt} range and
 * sort of {@code user_profiles}, and the equality counts on the {@code aiProvider}
 * and {@code isImageTranslation} fields of {@code translation_records}. Spring Data
 * auto index creation is off, so without them every statistics query is a
 * collection scan. With them the two equality counts are answered from the index
 * alone, without reading any document.
 */
@Component
@Slf4j
public class AdminStatsIndexInitializer {

    static final String RECORD_CREATED_AT_INDEX_NAME = "translation_record_created_at";
    static final String PROFILE_LAST_INTERACTION_INDEX_NAME = "user_profile_last_interaction_at";
    static final String RECORD_AI_PROVIDER_INDEX_NAME = "translation_record_ai_provider";
    static final String RECORD_IMAGE_TRANSLATION_INDEX_NAME = "translation_record_is_image_translation";

    private final MongoTemplate mongoTemplate;

    public AdminStatsIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Idempotent. A failure (for example MongoDB not yet reachable) is logged and
     * does not stop startup; the statistics stay correct, only slower.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        ensureIndex(TranslationRecord.class, RECORD_CREATED_AT_INDEX_NAME, "createdAt",
                Sort.Direction.DESC);
        ensureIndex(UserProfile.class, PROFILE_LAST_INTERACTION_INDEX_NAME, "lastInteractionAt",
                Sort.Direction.DESC);
        // Equality counts only, so the direction does not matter.
        ensureIndex(TranslationRecord.class, RECORD_AI_PROVIDER_INDEX_NAME, "aiProvider",
                Sort.Direction.ASC);
        ensureIndex(TranslationRecord.class, RECORD_IMAGE_TRANSLATION_INDEX_NAME,
                "isImageTranslation", Sort.Direction.ASC);
    }

    private void ensureIndex(
            Class<?> documentType, String name, String field, Sort.Direction direction) {
        try {
            if (hasTtl(documentType, name)) {
                // RetentionIndexInitializer turned this index into a TTL index; creating it
                // again with plain options would be refused as a conflict on every start.
                log.info("統計查詢索引已就緒（含資料保留 TTL）: index={}", name);
                return;
            }
            mongoTemplate.indexOps(documentType).createIndex(
                    new Index().named(name).on(field, direction));
            log.info("統計查詢索引已就緒: index={}", name);
        } catch (RuntimeException exception) {
            log.warn("無法建立統計查詢索引，統計將以全集合掃描執行: index={}, failure={}",
                    name, SafeLog.failure(exception));
        }
    }

    private boolean hasTtl(Class<?> documentType, String name) {
        return mongoTemplate.collectionExists(documentType)
                && mongoTemplate.indexOps(documentType).getIndexInfo().stream()
                        .anyMatch(index -> name.equals(index.getName())
                                && index.getExpireAfter().isPresent());
    }
}
