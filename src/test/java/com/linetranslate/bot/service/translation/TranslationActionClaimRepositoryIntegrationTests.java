package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.ActiveProfiles;

import com.linetranslate.bot.model.TranslationActionClaim;
import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.repository.TranslationActionClaimRepository;
import com.linetranslate.bot.repository.TranslationRecordRepository;

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

    @Test
    void staleWorkerCannotFinaliseTheClaimWhileTheFreshWorkerStillRunsIt() throws Exception {
        TranslationResponse staleResult = new TranslationResponse(
                "こんにちは", "こんにちは", "result-stale", "en", "ja");
        TranslationResponse freshResult = new TranslationResponse(
                "こんばんは", "こんばんは", "result-fresh", "en", "ja");
        try (StaleHandover handover = new StaleHandover(staleResult, freshResult)) {
            handover.startStaleWorkerAndHandOverToFreshWorker();

            handover.finishStaleWorker();

            TranslationActionClaim afterStale = onlyClaim();
            assertThat(afterStale.getStatus())
                    .isEqualTo(TranslationActionClaim.Status.PROCESSING);
            assertThat(afterStale.getResultRecordId()).isNull();
            assertThat(afterStale.getAttempt()).isEqualTo(2);

            handover.finishFreshWorker();

            TranslationActionClaim afterFresh = onlyClaim();
            assertThat(afterFresh.getStatus())
                    .isEqualTo(TranslationActionClaim.Status.COMPLETED);
            assertThat(afterFresh.getResultRecordId()).isEqualTo("result-fresh");
            assertThat(afterFresh.getAttempt()).isEqualTo(2);
        }
    }

    @Test
    void staleWorkerCannotOverwriteTheCompletedStateOfTheFreshWorker() throws Exception {
        TranslationResponse staleFailure = TranslationResponse.plain("重新翻譯未完成");
        TranslationResponse freshResult = new TranslationResponse(
                "こんばんは", "こんばんは", "result-fresh", "en", "ja");
        try (StaleHandover handover = new StaleHandover(staleFailure, freshResult)) {
            handover.startStaleWorkerAndHandOverToFreshWorker();
            handover.finishFreshWorker();

            handover.finishStaleWorker();

            TranslationActionClaim claim = onlyClaim();
            assertThat(claim.getStatus()).isEqualTo(TranslationActionClaim.Status.COMPLETED);
            assertThat(claim.getResultRecordId()).isEqualTo("result-fresh");
            assertThat(claim.getAttempt()).isEqualTo(2);
            assertThat(handover.staleResponse().get(10, TimeUnit.SECONDS)).isEqualTo(staleFailure);
        }
    }

    @Test
    void claimStoredWithoutAFencingTokenIsTakenOverAndFinalisedByItsNewWorker() {
        AdjustableClock clock = new AdjustableClock();
        TranslationResponse first = new TranslationResponse(
                "こんにちは", "こんにちは", "result-1", "en", "ja");
        TranslationResponse second = new TranslationResponse(
                "こんばんは", "こんばんは", "result-2", "en", "ja");
        TranslationService provider = Mockito.mock(TranslationService.class);
        when(provider.translateExisting("U-user", "hello", "ja")).thenReturn(first, second);
        TranslationActionModule actions = actionModule(provider, clock);
        actions.execute("U-user", "record-1", "ja");
        TranslationActionClaim written = onlyClaim();
        assertThat(written.getAttempt()).isEqualTo(1);
        // Rewrite the document the way a build without the fencing token left it behind.
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(written.getId())),
                new Update()
                        .unset("attempt")
                        .unset("resultRecordId")
                        .set("status", TranslationActionClaim.Status.PROCESSING),
                TranslationActionClaim.class);
        Document legacy = mongoTemplate.getCollection(COLLECTION)
                .find(new Document("_id", written.getId()))
                .first();
        assertThat(legacy).isNotNull().doesNotContainKey("attempt");
        clock.advance(Duration.ofMinutes(10));

        assertThat(actions.execute("U-user", "record-1", "ja")).isEqualTo(second);

        TranslationActionClaim claim = onlyClaim();
        assertThat(claim.getStatus()).isEqualTo(TranslationActionClaim.Status.COMPLETED);
        assertThat(claim.getResultRecordId()).isEqualTo("result-2");
        assertThat(claim.getAttempt()).isEqualTo(1);
    }

    private TranslationActionClaim onlyClaim() {
        List<TranslationActionClaim> claims = mongoTemplate.findAll(TranslationActionClaim.class);
        assertThat(claims).hasSize(1);
        return claims.get(0);
    }

    private TranslationActionModule actionModule(TranslationService provider, Clock clock) {
        TranslationRecordRepository records = Mockito.mock(TranslationRecordRepository.class);
        when(records.findByIdAndUserId("record-1", "U-user")).thenReturn(Optional.of(
                TranslationRecord.builder()
                        .id("record-1")
                        .userId("U-user")
                        .sourceText("hello")
                        .sourceLanguage("en")
                        .targetLanguage("zh-TW")
                        .translatedText("你好")
                        .build()));
        TranslationActionProperties properties = new TranslationActionProperties();
        properties.setActionClaimLease(Duration.ofMinutes(5));
        return new TranslationActionModule(
                records, repository, provider, mongoTemplate, properties, clock);
    }

    /**
     * Two workers for the same action: the first stalls inside the provider call past its
     * lease, a second click then takes the claim over, and each is released on demand.
     */
    private final class StaleHandover implements AutoCloseable {

        private final AdjustableClock clock = new AdjustableClock();
        private final TranslationActionModule actions;
        private final ExecutorService pool = Executors.newFixedThreadPool(2);
        private final CountDownLatch staleInProvider = new CountDownLatch(1);
        private final CountDownLatch freshInProvider = new CountDownLatch(1);
        private final CountDownLatch releaseStale = new CountDownLatch(1);
        private final CountDownLatch releaseFresh = new CountDownLatch(1);
        private Future<TranslationResponse> stale;
        private Future<TranslationResponse> fresh;

        StaleHandover(TranslationResponse staleOutcome, TranslationResponse freshOutcome) {
            AtomicInteger providerCalls = new AtomicInteger();
            TranslationService provider = Mockito.mock(TranslationService.class);
            when(provider.translateExisting("U-user", "hello", "ja")).thenAnswer(invocation -> {
                if (providerCalls.incrementAndGet() == 1) {
                    staleInProvider.countDown();
                    awaitRelease(releaseStale);
                    return staleOutcome;
                }
                freshInProvider.countDown();
                awaitRelease(releaseFresh);
                return freshOutcome;
            });
            actions = actionModule(provider, clock);
        }

        void startStaleWorkerAndHandOverToFreshWorker() throws InterruptedException {
            stale = pool.submit(() -> actions.execute("U-user", "record-1", "ja"));
            assertThat(staleInProvider.await(10, TimeUnit.SECONDS)).isTrue();
            clock.advance(Duration.ofMinutes(10));
            fresh = pool.submit(() -> actions.execute("U-user", "record-1", "ja"));
            assertThat(freshInProvider.await(10, TimeUnit.SECONDS)).isTrue();
        }

        void finishStaleWorker() throws Exception {
            releaseStale.countDown();
            stale.get(10, TimeUnit.SECONDS);
        }

        void finishFreshWorker() throws Exception {
            releaseFresh.countDown();
            fresh.get(10, TimeUnit.SECONDS);
        }

        Future<TranslationResponse> staleResponse() {
            return stale;
        }

        @Override
        public void close() {
            releaseStale.countDown();
            releaseFresh.countDown();
            pool.shutdownNow();
        }

        private static void awaitRelease(CountDownLatch latch) throws InterruptedException {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("worker was never released");
            }
        }
    }

    private static final class AdjustableClock extends Clock {

        private final AtomicReference<Instant> now =
                new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MILLIS));

        void advance(Duration amount) {
            now.updateAndGet(current -> current.plus(amount));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
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
