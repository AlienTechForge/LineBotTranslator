package com.linetranslate.bot.service.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;

import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.UserProfileRepository;

@SpringBootTest
@ActiveProfiles("test")
class UserProfileIndexIntegrationTests {

    private static final String COLLECTION = "user_profiles";
    private static final int CONCURRENT_FIRST_EVENTS = 8;

    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private UserProfileRepository repository;
    @Autowired
    private UserPreferencesModule module;
    @Autowired
    private UserProfileIndexInitializer initializer;

    @BeforeEach
    void dropProfiles() {
        if (mongoTemplate.collectionExists(COLLECTION)) {
            mongoTemplate.dropCollection(COLLECTION);
        }
    }

    @Test
    void startupCreatesANamedUniqueUserIdIndexIdempotently() {
        initializer.ensureUserIdIndex();
        initializer.ensureUserIdIndex();

        IndexInfo index = userIdIndex();
        assertThat(index).isNotNull();
        assertThat(index.isUnique()).isTrue();
        assertThat(index.isIndexForFields(List.of("userId"))).isTrue();
    }

    @Test
    void concurrentFirstEventsOfOneUserCreateExactlyOneProfile() throws Exception {
        initializer.ensureUserIdIndex();
        String userId = "U-concurrent-first-events";
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_FIRST_EVENTS);
        List<UserProfile> profiles = new ArrayList<>();
        try {
            List<Future<UserProfile>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_FIRST_EVENTS; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return module.profile(userId);
                }));
            }
            start.countDown();
            for (Future<UserProfile> future : futures) {
                profiles.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        long stored = mongoTemplate.count(
                Query.query(Criteria.where("userId").is(userId)), UserProfile.class);
        assertThat(stored).isEqualTo(1);
        assertThat(profiles).extracting(UserProfile::getId).containsOnly(profiles.get(0).getId());
        assertThat(repository.findByUserId(userId)).get()
                .extracting(UserProfile::getId)
                .isEqualTo(profiles.get(0).getId());
    }

    @Test
    void existingDuplicateProfilesLeaveTheIndexUnbuiltWithoutFailingStartup() {
        for (int i = 0; i < 2; i++) {
            mongoTemplate.getCollection(COLLECTION).insertOne(new Document("userId", "U-duplicated"));
        }

        assertThatCode(initializer::ensureUserIdIndex).doesNotThrowAnyException();

        assertThat(userIdIndex()).isNull();
    }

    private IndexInfo userIdIndex() {
        return mongoTemplate.indexOps(UserProfile.class).getIndexInfo().stream()
                .filter(info -> UserProfileIndexInitializer.USER_ID_INDEX_NAME.equals(info.getName()))
                .findFirst()
                .orElse(null);
    }
}
