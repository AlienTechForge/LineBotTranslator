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
 * range of {@code translation_records} and the {@code lastInteractionAt} range and
 * sort of {@code user_profiles}. Spring Data auto index creation is off, so without
 * them every statistics query is a collection scan.
 */
@Component
@Slf4j
public class AdminStatsIndexInitializer {

    static final String RECORD_CREATED_AT_INDEX_NAME = "translation_record_created_at";
    static final String PROFILE_LAST_INTERACTION_INDEX_NAME = "user_profile_last_interaction_at";

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
        ensureIndex(TranslationRecord.class, RECORD_CREATED_AT_INDEX_NAME, "createdAt");
        ensureIndex(UserProfile.class, PROFILE_LAST_INTERACTION_INDEX_NAME, "lastInteractionAt");
    }

    private void ensureIndex(Class<?> documentType, String name, String field) {
        try {
            mongoTemplate.indexOps(documentType).createIndex(
                    new Index().named(name).on(field, Sort.Direction.DESC));
            log.info("統計查詢索引已就緒: index={}", name);
        } catch (RuntimeException exception) {
            log.warn("無法建立統計查詢索引，統計將以全集合掃描執行: index={}, failure={}",
                    name, SafeLog.failure(exception));
        }
    }
}
