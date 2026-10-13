package com.linetranslate.bot.service.preference;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.model.UserProfile;

import lombok.extern.slf4j.Slf4j;

/**
 * Creates the unique {@code userId} index of {@code user_profiles}. Spring Data
 * auto index creation is off, so without it every profile lookup is a collection
 * scan and concurrent first events of one user insert two profile documents.
 */
@Component
@Slf4j
public class UserProfileIndexInitializer {

    static final String USER_ID_INDEX_NAME = "user_profile_user_id_unique";

    private final MongoTemplate mongoTemplate;

    public UserProfileIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Idempotent. A failure (duplicate profiles already stored, or MongoDB not yet
     * reachable) is logged and does not stop startup; duplicate userId documents
     * must be removed by an operator before the index can be built.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureUserIdIndex() {
        try {
            mongoTemplate.indexOps(UserProfile.class).createIndex(new Index()
                    .named(USER_ID_INDEX_NAME)
                    .on("userId", Sort.Direction.ASC)
                    .unique());
            log.info("user_profiles 唯一索引已就緒: index={}", USER_ID_INDEX_NAME);
        } catch (RuntimeException exception) {
            log.warn("無法建立 user_profiles 唯一索引，若有重複的使用者文件需先去重: index={}, failure={}",
                    USER_ID_INDEX_NAME, SafeLog.failure(exception));
        }
    }
}
