package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.linetranslate.bot.model.TranslationActionClaim;
import com.linetranslate.bot.repository.TranslationActionClaimRepository;

@SpringBootTest
@ActiveProfiles("test")
class TranslationActionClaimRepositoryIntegrationTests {

    private static final String COLLECTION = "translation_action_claims";

    @Autowired
    private TranslationActionClaimRepository repository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private TranslationActionModule module;

    @BeforeEach
    void clearClaims() {
        if (mongoTemplate.collectionExists(COLLECTION)) {
            mongoTemplate.dropCollection(COLLECTION);
        }
    }

    @Test
    void deterministicIdRejectsDuplicateAndPersistsNoTranslationContent() {
        TranslationActionClaim claim = TranslationActionClaim.builder()
                .id("claim-1")
                .userId("U-user")
                .sourceRecordId("record-1")
                .targetLanguage("ja")
                .status(TranslationActionClaim.Status.PROCESSING)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        repository.insert(claim);

        assertThatThrownBy(() -> repository.insert(claim))
                .isInstanceOf(DuplicateKeyException.class);
        Document stored = mongoTemplate.getCollection(COLLECTION)
                .find(new Document("_id", "claim-1"))
                .first();
        assertThat(stored).isNotNull();
        assertThat(stored.keySet()).doesNotContain(
                "sourceText", "translatedText", "payload", "postbackData", "secret");
    }

    @Test
    void onlyOneConcurrentTakeoverWinsAClaimAbandonedPastItsLease() throws Exception {
        LocalDateTime abandonedAt = LocalDateTime.now().minusMinutes(30)
                .truncatedTo(ChronoUnit.MILLIS);
        repository.insert(claim("claim-stale", "record-1",
                TranslationActionClaim.Status.PROCESSING, abandonedAt));
        int contenders = 8;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<TranslationActionClaim> winners = new ArrayList<>();
        try {
            List<Future<TranslationActionClaim>> attempts = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                attempts.add(pool.submit(() -> {
                    start.await();
                    return module.takeOverExpired("claim-stale", "U-user", "record-1", "ja", null);
                }));
            }
            start.countDown();
            for (Future<TranslationActionClaim> attempt : attempts) {
                TranslationActionClaim taken = attempt.get(10, TimeUnit.SECONDS);
                if (taken != null) {
                    winners.add(taken);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(winners).hasSize(1);
        assertThat(winners.get(0).getStatus()).isEqualTo(TranslationActionClaim.Status.PROCESSING);
        assertThat(winners.get(0).getCreatedAt()).isEqualTo(abandonedAt);
        assertThat(winners.get(0).getUpdatedAt()).isAfter(abandonedAt);
        // The winner renewed the lease, so a later click replays instead of retrying.
        assertThat(module.takeOverExpired("claim-stale", "U-user", "record-1", "ja", null))
                .isNull();
    }

    @Test
    void claimsInsideTheirLeaseFinishedOrOwnedByAnotherUserAreNotTakenOver() {
        LocalDateTime now = LocalDateTime.now();
        repository.insert(claim("claim-fresh", "record-1",
                TranslationActionClaim.Status.PROCESSING, now.minusMinutes(1)));
        repository.insert(claim("claim-completed", "record-2",
                TranslationActionClaim.Status.COMPLETED, now.minusMinutes(30)));
        repository.insert(claim("claim-failed", "record-3",
                TranslationActionClaim.Status.FAILED, now.minusMinutes(30)));
        repository.insert(claim("claim-stale", "record-4",
                TranslationActionClaim.Status.PROCESSING, now.minusMinutes(30)));

        assertThat(module.takeOverExpired("claim-fresh", "U-user", "record-1", "ja", null))
                .isNull();
        assertThat(module.takeOverExpired("claim-completed", "U-user", "record-2", "ja", null))
                .isNull();
        assertThat(module.takeOverExpired("claim-failed", "U-user", "record-3", "ja", null))
                .isNull();
        assertThat(module.takeOverExpired("claim-stale", "U-other", "record-4", "ja", null))
                .isNull();
        assertThat(repository.findById("claim-completed").orElseThrow().getStatus())
                .isEqualTo(TranslationActionClaim.Status.COMPLETED);
    }

    private static TranslationActionClaim claim(
            String id,
            String sourceRecordId,
            TranslationActionClaim.Status status,
            LocalDateTime updatedAt) {
        return TranslationActionClaim.builder()
                .id(id)
                .userId("U-user")
                .sourceRecordId(sourceRecordId)
                .targetLanguage("ja")
                .status(status)
                .createdAt(updatedAt)
                .updatedAt(updatedAt)
                .build();
    }
}
