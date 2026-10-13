package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.linetranslate.bot.model.TranslationActionClaim;
import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.repository.TranslationActionClaimRepository;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.mongodb.client.result.UpdateResult;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class TranslationActionModuleTests {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-13T08:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final Duration LEASE = Duration.ofMinutes(5);

    private TranslationRecordRepository recordRepository;
    private TranslationActionClaimRepository claimRepository;
    private TranslationService translationService;
    private MongoTemplate mongoTemplate;
    private TranslationActionModule module;
    private final Logger moduleLogger =
            (Logger) org.slf4j.LoggerFactory.getLogger(TranslationActionModule.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        logs.start();
        moduleLogger.addAppender(logs);
        recordRepository = Mockito.mock(TranslationRecordRepository.class);
        claimRepository = Mockito.mock(TranslationActionClaimRepository.class);
        translationService = Mockito.mock(TranslationService.class);
        mongoTemplate = Mockito.mock(MongoTemplate.class);
        TranslationActionProperties properties = new TranslationActionProperties();
        properties.setActionClaimLease(LEASE);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(TranslationActionClaim.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        module = new TranslationActionModule(
                recordRepository, claimRepository, translationService,
                mongoTemplate, properties, CLOCK);
    }

    @AfterEach
    void tearDown() {
        moduleLogger.detachAppender(logs);
    }

    @Test
    void firstAuthorizedActionClaimsBeforeCallingProviderAndCompletesClaim() {
        TranslationRecord source = record("source-1", "U-user", "hello", "你好", "zh-TW");
        TranslationResponse translated = new TranslationResponse(
                "こんにちは", "こんにちは", "result-1", "en", "ja");
        when(recordRepository.findByIdAndUserId("source-1", "U-user"))
                .thenReturn(Optional.of(source));
        when(claimRepository.insert(any(TranslationActionClaim.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(translationService.translateExisting("U-user", "hello", "ja"))
                .thenReturn(translated);

        assertThat(module.execute("U-user", "source-1", "ja")).isEqualTo(translated);

        verify(translationService).translateExisting("U-user", "hello", "ja");
        ArgumentCaptor<TranslationActionClaim> inserted =
                ArgumentCaptor.forClass(TranslationActionClaim.class);
        verify(claimRepository).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus())
                .isEqualTo(TranslationActionClaim.Status.PROCESSING);
        assertThat(inserted.getValue().getAttempt()).isEqualTo(1);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(
                query.capture(), update.capture(), eq(TranslationActionClaim.class));
        assertThat(query.getValue().getQueryObject()).isEqualTo(new Document()
                .append("_id", inserted.getValue().getId())
                .append("attempt", 1)
                .append("status", TranslationActionClaim.Status.PROCESSING));
        assertThat(update.getValue().getUpdateObject().get("$set", Document.class))
                .isEqualTo(new Document()
                        .append("status", TranslationActionClaim.Status.COMPLETED)
                        .append("updatedAt", NOW)
                        .append("resultRecordId", "result-1"));
        verify(claimRepository, never()).save(any(TranslationActionClaim.class));
    }

    @Test
    void duplicateCompletedActionReusesSavedResultWithoutProviderCost() {
        TranslationRecord source = record("source-1", "U-user", "hello", "你好", "zh-TW");
        TranslationRecord result = record("result-1", "U-user", "hello", "こんにちは", "ja");
        TranslationActionClaim completed = TranslationActionClaim.builder()
                .id("claim-1")
                .userId("U-user")
                .sourceRecordId("source-1")
                .targetLanguage("ja")
                .status(TranslationActionClaim.Status.COMPLETED)
                .resultRecordId("result-1")
                .build();
        when(recordRepository.findByIdAndUserId("source-1", "U-user"))
                .thenReturn(Optional.of(source));
        when(claimRepository.insert(any(TranslationActionClaim.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(claimRepository.findById(any(String.class))).thenReturn(Optional.of(completed));
        when(recordRepository.findByIdAndUserId("result-1", "U-user"))
                .thenReturn(Optional.of(result));

        TranslationResponse response = module.execute("U-user", "source-1", "ja");

        assertThat(response.translatedText()).isEqualTo("こんにちは");
        assertThat(response.recordId()).isEqualTo("result-1");
        verify(translationService, never()).translateExisting(any(), any(), any());
    }

    @Test
    void duplicateInProgressActionDoesNotCallProviderAgain() {
        TranslationRecord source = record("source-1", "U-user", "hello", "你好", "zh-TW");
        TranslationActionClaim processing = TranslationActionClaim.builder()
                .id("claim-1")
                .userId("U-user")
                .sourceRecordId("source-1")
                .targetLanguage("ja")
                .status(TranslationActionClaim.Status.PROCESSING)
                .build();
        when(recordRepository.findByIdAndUserId("source-1", "U-user"))
                .thenReturn(Optional.of(source));
        when(claimRepository.insert(any(TranslationActionClaim.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(claimRepository.findById(any(String.class))).thenReturn(Optional.of(processing));

        TranslationResponse response = module.execute("U-user", "source-1", "ja");

        assertThat(response.actionable()).isFalse();
        assertThat(response.displayText()).contains("處理中");
        verify(translationService, never()).translateExisting(any(), any(), any());
    }

    @Test
    void stuckClaimPastItsLeaseIsTakenOverAndTranslatedOnce() {
        TranslationRecord source = record("source-1", "U-user", "hello", "你好", "zh-TW");
        LocalDateTime createdAt = NOW.minusMinutes(30);
        TranslationActionClaim takenOver = TranslationActionClaim.builder()
                .id("claim-1")
                .userId("U-user")
                .sourceRecordId("source-1")
                .targetLanguage("ja")
                .status(TranslationActionClaim.Status.PROCESSING)
                .attempt(2)
                .createdAt(createdAt)
                .updatedAt(NOW)
                .build();
        TranslationResponse translated = new TranslationResponse(
                "こんにちは", "こんにちは", "result-1", "en", "ja");
        when(recordRepository.findByIdAndUserId("source-1", "U-user"))
                .thenReturn(Optional.of(source));
        when(claimRepository.insert(any(TranslationActionClaim.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(TranslationActionClaim.class)))
                .thenReturn(takenOver);
        when(translationService.translateExisting("U-user", "hello", "ja"))
                .thenReturn(translated);

        assertThat(module.execute("U-user", "source-1", "ja")).isEqualTo(translated);

        verify(translationService, times(1)).translateExisting("U-user", "hello", "ja");
        verify(claimRepository, never()).findById(any(String.class));
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(query.capture(), update.capture(),
                any(FindAndModifyOptions.class), eq(TranslationActionClaim.class));
        Document criteria = query.getValue().getQueryObject();
        assertThat(criteria.get("status")).isEqualTo(TranslationActionClaim.Status.PROCESSING);
        assertThat(criteria.get("updatedAt")).isEqualTo(new Document("$lte", NOW.minus(LEASE)));
        assertThat(update.getValue().getUpdateObject().get("$set", Document.class).get("updatedAt"))
                .isEqualTo(NOW);
        assertThat(update.getValue().getUpdateObject().get("$inc", Document.class))
                .isEqualTo(new Document("attempt", 1));
        ArgumentCaptor<Query> finalQuery = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> finalUpdate = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(
                finalQuery.capture(), finalUpdate.capture(), eq(TranslationActionClaim.class));
        assertThat(finalQuery.getValue().getQueryObject().get("attempt")).isEqualTo(2);
        assertThat(finalQuery.getValue().getQueryObject().get("status"))
                .isEqualTo(TranslationActionClaim.Status.PROCESSING);
        Document finished = finalUpdate.getValue().getUpdateObject().get("$set", Document.class);
        assertThat(finished.get("status")).isEqualTo(TranslationActionClaim.Status.COMPLETED);
        assertThat(finished.get("resultRecordId")).isEqualTo("result-1");
        assertThat(finished.get("updatedAt")).isEqualTo(NOW);
        assertThat(finished).doesNotContainKey("createdAt");
        verify(claimRepository, never()).save(any(TranslationActionClaim.class));
    }

    @Test
    void stuckStyleClaimPastItsLeaseIsTakenOverAndTranslated() {
        TranslationRecord source = record("source-1", "U-user", "hello", "你好", "zh-TW");
        TranslationActionClaim takenOver = TranslationActionClaim.builder()
                .id("claim-1")
                .userId("U-user")
                .sourceRecordId("source-1")
                .targetLanguage("zh-TW")
                .stylePresetId("formal")
                .status(TranslationActionClaim.Status.PROCESSING)
                .attempt(2)
                .createdAt(NOW.minusMinutes(30))
                .updatedAt(NOW)
                .build();
        TranslationResponse translated = new TranslationResponse(
                "您好", "您好", "result-1", "en", "zh-TW", "formal", "formal-v1");
        when(recordRepository.findByIdAndUserId("source-1", "U-user"))
                .thenReturn(Optional.of(source));
        when(claimRepository.insert(any(TranslationActionClaim.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(TranslationActionClaim.class)))
                .thenReturn(takenOver);
        when(translationService.translateExisting("U-user", "hello", "zh-TW", "formal"))
                .thenReturn(translated);

        assertThat(module.executeStyle("U-user", "source-1", "formal")).isEqualTo(translated);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findAndModify(query.capture(), any(Update.class),
                any(FindAndModifyOptions.class), eq(TranslationActionClaim.class));
        assertThat(query.getValue().getQueryObject().get("stylePresetId")).isEqualTo("formal");
        ArgumentCaptor<Query> finalQuery = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> finalUpdate = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(
                finalQuery.capture(), finalUpdate.capture(), eq(TranslationActionClaim.class));
        assertThat(finalQuery.getValue().getQueryObject().get("attempt")).isEqualTo(2);
        assertThat(finalUpdate.getValue().getUpdateObject().get("$set", Document.class)
                .get("status")).isEqualTo(TranslationActionClaim.Status.COMPLETED);
        verify(claimRepository, never()).save(any(TranslationActionClaim.class));
    }

    @Test
    void claimInsideItsLeaseStillReplaysProcessingWithoutProviderCall() {
        TranslationRecord source = record("source-1", "U-user", "hello", "你好", "zh-TW");
        TranslationActionClaim processing = TranslationActionClaim.builder()
                .id("claim-1")
                .userId("U-user")
                .sourceRecordId("source-1")
                .targetLanguage("ja")
                .status(TranslationActionClaim.Status.PROCESSING)
                .createdAt(NOW.minusMinutes(1))
                .updatedAt(NOW.minusMinutes(1))
                .build();
        when(recordRepository.findByIdAndUserId("source-1", "U-user"))
                .thenReturn(Optional.of(source));
        when(claimRepository.insert(any(TranslationActionClaim.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(TranslationActionClaim.class)))
                .thenReturn(null);
        when(claimRepository.findById(any(String.class))).thenReturn(Optional.of(processing));

        TranslationResponse response = module.execute("U-user", "source-1", "ja");

        assertThat(response.actionable()).isFalse();
        assertThat(response.displayText()).contains("處理中");
        verify(translationService, never()).translateExisting(any(), any(), any());
        verify(claimRepository, never()).save(any(TranslationActionClaim.class));
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
                eq(TranslationActionClaim.class));
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findAndModify(query.capture(), any(Update.class),
                any(FindAndModifyOptions.class), eq(TranslationActionClaim.class));
        Document updatedAt = query.getValue().getQueryObject().get("updatedAt", Document.class);
        assertThat(updatedAt.get("$lte", LocalDateTime.class))
                .isBefore(processing.getUpdatedAt());
    }

    @Test
    void failedProviderOutcomeIsRecordedAsFailedWithoutResultRecord() {
        TranslationRecord source = record("source-1", "U-user", "hello", "你好", "zh-TW");
        when(recordRepository.findByIdAndUserId("source-1", "U-user"))
                .thenReturn(Optional.of(source));
        when(claimRepository.insert(any(TranslationActionClaim.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(translationService.translateExisting("U-user", "hello", "ja"))
                .thenThrow(new IllegalStateException("provider down"));

        TranslationResponse response = module.execute("U-user", "source-1", "ja");

        assertThat(response.actionable()).isFalse();
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(
                any(Query.class), update.capture(), eq(TranslationActionClaim.class));
        assertThat(update.getValue().getUpdateObject().get("$set", Document.class))
                .isEqualTo(new Document()
                        .append("status", TranslationActionClaim.Status.FAILED)
                        .append("updatedAt", NOW));
    }

    @Test
    void workerThatLostItsLeaseLeavesTheClaimUntouchedAndLogsOnlyTheClaimId() {
        TranslationRecord source = record("source-1", "U-user", "hello", "你好", "zh-TW");
        TranslationResponse translated = new TranslationResponse(
                "こんにちは", "こんにちは", "result-1", "en", "ja");
        when(recordRepository.findByIdAndUserId("source-1", "U-user"))
                .thenReturn(Optional.of(source));
        when(claimRepository.insert(any(TranslationActionClaim.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(translationService.translateExisting("U-user", "hello", "ja"))
                .thenReturn(translated);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(TranslationActionClaim.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThat(module.execute("U-user", "source-1", "ja")).isEqualTo(translated);

        ArgumentCaptor<TranslationActionClaim> inserted =
                ArgumentCaptor.forClass(TranslationActionClaim.class);
        verify(claimRepository).insert(inserted.capture());
        verify(claimRepository, never()).save(any(TranslationActionClaim.class));
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("lease lost", "claim=" + inserted.getValue().getId(),
                            "outcome=completed")
                    .doesNotContain("U-user", "hello", "こんにちは", "result-1");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void styleWorkerThatLostItsLeaseKeepsItsReplyAndTouchesNothing() {
        TranslationRecord source = record("source-1", "U-user", "hello", "你好", "zh-TW");
        TranslationResponse failed = TranslationResponse.plain("風格翻譯未完成");
        when(recordRepository.findByIdAndUserId("source-1", "U-user"))
                .thenReturn(Optional.of(source));
        when(claimRepository.insert(any(TranslationActionClaim.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(translationService.translateExisting("U-user", "hello", "zh-TW", "formal"))
                .thenReturn(failed);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(TranslationActionClaim.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThat(module.executeStyle("U-user", "source-1", "formal")).isEqualTo(failed);

        verify(claimRepository, never()).save(any(TranslationActionClaim.class));
        assertThat(logs.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("outcome=failed"));
    }

    @Test
    void claimWrittenBeforeTheFencingTokenExistedIsStillReplayed() {
        TranslationRecord source = record("source-1", "U-user", "hello", "你好", "zh-TW");
        TranslationRecord result = record("result-1", "U-user", "hello", "こんにちは", "ja");
        TranslationActionClaim legacy = TranslationActionClaim.builder()
                .id("claim-1")
                .userId("U-user")
                .sourceRecordId("source-1")
                .targetLanguage("ja")
                .status(TranslationActionClaim.Status.COMPLETED)
                .resultRecordId("result-1")
                .createdAt(NOW.minusDays(1))
                .updatedAt(NOW.minusDays(1))
                .build();
        assertThat(legacy.getAttempt()).isNull();
        when(recordRepository.findByIdAndUserId("source-1", "U-user"))
                .thenReturn(Optional.of(source));
        when(claimRepository.insert(any(TranslationActionClaim.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(claimRepository.findById(any(String.class))).thenReturn(Optional.of(legacy));
        when(recordRepository.findByIdAndUserId("result-1", "U-user"))
                .thenReturn(Optional.of(result));

        TranslationResponse response = module.execute("U-user", "source-1", "ja");

        assertThat(response.recordId()).isEqualTo("result-1");
        verify(translationService, never()).translateExisting(any(), any(), any());
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
                eq(TranslationActionClaim.class));
    }

    @Test
    void nonPositiveClaimLeaseIsRejected() {
        TranslationActionProperties properties = new TranslationActionProperties();
        properties.setActionClaimLease(Duration.ZERO);

        assertThatThrownBy(() -> new TranslationActionModule(
                recordRepository, claimRepository, translationService,
                mongoTemplate, properties, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void guessedRecordOwnedByAnotherUserCannotCreateAClaim() {
        when(recordRepository.findByIdAndUserId("source-1", "U-attacker"))
                .thenReturn(Optional.empty());

        TranslationResponse response = module.execute("U-attacker", "source-1", "ja");

        assertThat(response.actionable()).isFalse();
        verify(claimRepository, never()).insert(any(TranslationActionClaim.class));
        verify(translationService, never()).translateExisting(any(), any(), any());
    }

    @Test
    void styleActionIsAuthorizedClaimedAndDoesNotChangeTargetLanguage() {
        TranslationRecord source = record("source-1", "U-user", "hello", "你好", "zh-TW");
        TranslationResponse translated = new TranslationResponse(
                "您好", "您好", "result-1", "en", "zh-TW", "formal", "formal-v1");
        when(recordRepository.findByIdAndUserId("source-1", "U-user"))
                .thenReturn(Optional.of(source));
        when(claimRepository.insert(any(TranslationActionClaim.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(translationService.translateExisting("U-user", "hello", "zh-TW", "formal"))
                .thenReturn(translated);

        assertThat(module.executeStyle("U-user", "source-1", "formal"))
                .isEqualTo(translated);

        ArgumentCaptor<TranslationActionClaim> captor =
                ArgumentCaptor.forClass(TranslationActionClaim.class);
        verify(claimRepository).insert(captor.capture());
        assertThat(captor.getValue().getTargetLanguage()).isEqualTo("zh-TW");
        assertThat(captor.getValue().getStylePresetId()).isEqualTo("formal");
        assertThat(captor.getValue().getStatus())
                .isEqualTo(TranslationActionClaim.Status.PROCESSING);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(
                any(Query.class), update.capture(), eq(TranslationActionClaim.class));
        assertThat(update.getValue().getUpdateObject().get("$set", Document.class)
                .get("status")).isEqualTo(TranslationActionClaim.Status.COMPLETED);
    }

    @Test
    void invalidStyleNeverCreatesClaimOrCallsProvider() {
        TranslationRecord source = record("source-1", "U-user", "hello", "你好", "zh-TW");
        when(recordRepository.findByIdAndUserId("source-1", "U-user"))
                .thenReturn(Optional.of(source));

        TranslationResponse response = module.executeStyle("U-user", "source-1", "retired");

        assertThat(response.actionable()).isFalse();
        verify(claimRepository, never()).insert(any(TranslationActionClaim.class));
        verify(translationService, never()).translateExisting(any(), any(), any(), any());
    }

    private static TranslationRecord record(
            String id,
            String userId,
            String source,
            String translated,
            String target) {
        return TranslationRecord.builder()
                .id(id)
                .userId(userId)
                .sourceText(source)
                .sourceLanguage("en")
                .targetLanguage(target)
                .translatedText(translated)
                .build();
    }
}
