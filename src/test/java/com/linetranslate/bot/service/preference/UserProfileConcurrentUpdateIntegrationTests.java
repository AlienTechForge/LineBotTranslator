package com.linetranslate.bot.service.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.UserProfileResponse;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.line.LineUserProfileService;
import com.linetranslate.bot.service.translation.CachedTranslationAdapter;
import com.linetranslate.bot.service.translation.LanguageDetectionService;
import com.linetranslate.bot.service.translation.TranslationRequestKind;
import com.linetranslate.bot.service.translation.TranslationWorkflowModule;
import com.linetranslate.bot.service.translation.TranslationWorkflowOutcome;
import com.linetranslate.bot.service.translation.TranslationWorkflowRequest;

/**
 * Two worker threads handling events of the same LINE user must never overwrite each
 * other's profile fields. Runs against a throw-away database so it cannot interfere with
 * the shared test database of the other integration tests.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserProfileConcurrentUpdateIntegrationTests {

    private static final String COLLECTION = "user_profiles";
    private static final String DATABASE =
            "linebot_profile_updates_" + UUID.randomUUID().toString().replace("-", "");
    private static final int ROUNDS = 25;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("mongodb.database", () -> DATABASE);
    }

    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private UserProfileRepository repository;
    @Autowired
    private TranslationRecordRepository recordRepository;
    @Autowired
    private UserPreferencesModule preferences;
    @Autowired
    private LineUserProfileService lineUserProfileService;
    @Autowired
    private UserProfileIndexInitializer indexInitializer;

    private TranslationWorkflowModule workflow;
    private LineUserProfileService lineSync;

    @BeforeEach
    void freshCollection() {
        if (mongoTemplate.collectionExists(COLLECTION)) {
            mongoTemplate.dropCollection(COLLECTION);
        }
        indexInitializer.ensureUserIdIndex();
        LanguageDetectionService detection = mock(LanguageDetectionService.class);
        when(detection.detectLanguage(anyString())).thenReturn("en");
        CachedTranslationAdapter adapter = mock(CachedTranslationAdapter.class);
        when(adapter.translate(any(UserPreferences.class), anyString(), anyString()))
                .thenAnswer(invocation -> new AiExecutionOutcome.Success(new AiExecutionResult(
                        "translated:" + invocation.getArgument(1), "openrouter", "model-actual")));
        workflow = new TranslationWorkflowModule(detection, adapter, recordRepository, preferences);
        MessagingApiClient lineClient = mock(MessagingApiClient.class);
        when(lineClient.getProfile(anyString())).thenReturn(CompletableFuture.completedFuture(new Result<>(
                "request-id", null, new UserProfileResponse(
                        "LINE Name", "id", URI.create("https://example.com/p.jpg"), "Hello", "zh-TW"))));
        lineSync = new LineUserProfileService(lineClient, repository, recordRepository, preferences);
    }

    @AfterAll
    void dropThrowAwayDatabase() {
        mongoTemplate.getDb().drop();
    }

    @Test
    void preferenceChangesAndTranslationActivityOfOneUserAllSurviveConcurrentHandling() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String userId = "U-mixed-" + round;
            preferences.profile(userId);
            UserProfile snapshotForWorkflow = preferences.profile(userId);

            runConcurrently(List.<Callable<Object>>of(
                    () -> preferences.updateTargetLanguage(userId, "ja"),
                    () -> preferences.updateChineseTargetLanguage(userId, "ko"),
                    () -> preferences.updateTranslationStyle(userId, "business"),
                    () -> { lineSync.syncUserProfile(userId); return null; },
                    () -> translate(snapshotForWorkflow, "hello", "fr", TranslationRequestKind.STANDARD_TEXT)));

            UserProfile stored = repository.findByUserId(userId).orElseThrow();
            assertThat(stored.getPreferredLanguage()).as("round %d", round).isEqualTo("ja");
            assertThat(stored.getPreferredChineseTargetLanguage()).as("round %d", round).isEqualTo("ko");
            assertThat(stored.getPreferredTranslationStyle()).as("round %d", round).isEqualTo("business");
            assertThat(stored.getDisplayName()).as("round %d", round).isEqualTo("LINE Name");
            assertThat(stored.getPictureUrl()).as("round %d", round).isEqualTo("https://example.com/p.jpg");
            assertThat(stored.getStatusMessage()).as("round %d", round).isEqualTo("Hello");
            assertThat(stored.getTotalTranslations()).as("round %d", round).isEqualTo(1);
            assertThat(stored.getRecentLanguagesList()).as("round %d", round).containsExactly("fr");
            assertThat(stored.getRecentTranslations()).as("round %d", round)
                    .containsExactly("translated:hello");
        }
    }

    @Test
    void concurrentTranslationsOfOneUserKeepEveryCounterAndRecentEntry() throws Exception {
        String userId = "U-counters";
        preferences.profile(userId);
        List<String> targets = List.of("en", "ja", "ko", "fr", "de", "es", "it", "pt");
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            UserProfile ownSnapshot = preferences.profile(userId);
            String target = targets.get(i);
            TranslationRequestKind kind = i % 4 == 3
                    ? TranslationRequestKind.IMAGE_OCR : TranslationRequestKind.STANDARD_TEXT;
            tasks.add(() -> translate(ownSnapshot, "text-" + target, target, kind));
        }

        runConcurrently(tasks);

        UserProfile stored = repository.findByUserId(userId).orElseThrow();
        assertThat(stored.getTotalTranslations()).isEqualTo(8);
        assertThat(stored.getTextTranslations()).isEqualTo(6);
        assertThat(stored.getImageTranslations()).isEqualTo(2);
        assertThat(stored.getRecentTranslations()).hasSize(5).doesNotHaveDuplicates();
        assertThat(stored.getRecentLanguagesList()).hasSize(5).doesNotHaveDuplicates()
                .isSubsetOf(targets);
        assertThat(stored.getLastInteractionAt()).isNotNull();
        assertThat(recordRepository.findAll().stream().filter(r -> userId.equals(r.getUserId())).count())
                .isEqualTo(8);
    }

    @Test
    void concurrentFirstDisplayNameSettingsCreateOneProfileAndNeverReportFailure() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String userId = "U-first-name-" + round;
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String name = "Name-" + i;
                tasks.add(() -> lineUserProfileService.setUserDisplayName(userId, name));
            }

            List<String> replies = runConcurrently(tasks);

            assertThat(replies).as("round %d", round).noneMatch(reply -> reply.startsWith("❌"));
            assertThat(mongoTemplate.count(
                    Query.query(Criteria.where("userId").is(userId)), UserProfile.class))
                    .as("round %d", round).isEqualTo(1);
            assertThat(repository.findByUserId(userId).orElseThrow().getDisplayName())
                    .as("round %d", round).startsWith("Name-");
        }
    }

    @Test
    void documentsStoredBeforeTheChangeStillTakeEveryUpdate() {
        mongoTemplate.getCollection(COLLECTION).insertOne(new Document()
                .append("userId", "U-legacy")
                .append("displayName", "Legacy User")
                .append("totalTranslations", 3));
        String originalId = repository.findByUserId("U-legacy").orElseThrow().getId();

        preferences.updateTargetLanguage("U-legacy", "ja");
        preferences.updateTranslationStyle("U-legacy", "casual");
        translate(preferences.profile("U-legacy"), "hello", "fr", TranslationRequestKind.STANDARD_TEXT);
        lineUserProfileService.setUserDisplayName("U-legacy", "Still Legacy");

        UserProfile stored = repository.findByUserId("U-legacy").orElseThrow();
        assertThat(stored.getId()).isEqualTo(originalId);
        assertThat(mongoTemplate.count(
                Query.query(Criteria.where("userId").is("U-legacy")), UserProfile.class)).isEqualTo(1);
        assertThat(stored.getPreferredLanguage()).isEqualTo("ja");
        assertThat(stored.getPreferredTranslationStyle()).isEqualTo("casual");
        assertThat(stored.getDisplayName()).isEqualTo("Still Legacy");
        assertThat(stored.getTotalTranslations()).isEqualTo(4);
        assertThat(stored.getTextTranslations()).isEqualTo(1);
        assertThat(stored.getRecentLanguagesList()).containsExactly("fr");
        assertThat(stored.getRecentTranslations()).containsExactly("translated:hello");
    }

    @Test
    void documentsWithExplicitNullArraysStillTakeTranslationActivity() {
        mongoTemplate.getCollection(COLLECTION).insertOne(new Document()
                .append("userId", "U-null-arrays")
                .append("recentTranslations", null)
                .append("recentLanguages", null));

        translate(preferences.profile("U-null-arrays"), "hello", "ja", TranslationRequestKind.STANDARD_TEXT);

        UserProfile stored = repository.findByUserId("U-null-arrays").orElseThrow();
        assertThat(stored.getRecentLanguagesList()).containsExactly("ja");
        assertThat(stored.getRecentTranslations()).containsExactly("translated:hello");
        assertThat(stored.getTotalTranslations()).isEqualTo(1);
    }

    @Test
    void recentEntriesStayNewestFirstDeduplicatedAndBoundedAndTextIsStoredLiterally() {
        String userId = "U-recents";
        preferences.profile(userId);

        for (String target : List.of("en", "ja", "en", "ko", "fr", "de", "es")) {
            translate(preferences.profile(userId), "$" + target, target, TranslationRequestKind.STANDARD_TEXT);
        }

        UserProfile stored = repository.findByUserId(userId).orElseThrow();
        assertThat(stored.getRecentLanguagesList()).containsExactly("es", "de", "fr", "ko", "en");
        assertThat(stored.getRecentTranslations()).containsExactly(
                "translated:$es", "translated:$de", "translated:$fr", "translated:$ko", "translated:$en");
        assertThat(stored.getTotalTranslations()).isEqualTo(7);
    }

    private Object translate(UserProfile snapshot, String text, String target, TranslationRequestKind kind) {
        TranslationWorkflowOutcome outcome = workflow.execute(new TranslationWorkflowRequest(
                snapshot, text, target, kind, null, kind.isImage() ? Boolean.FALSE : null, Instant.now()));
        assertThat(outcome).isInstanceOf(TranslationWorkflowOutcome.Success.class);
        return outcome;
    }

    private static <T> List<T> runConcurrently(List<? extends Callable<T>> tasks) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
