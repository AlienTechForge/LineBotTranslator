package com.linetranslate.bot.service.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexOptions;

import com.linetranslate.bot.model.TranslationActionClaim;
import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.service.usage.AiUsageEvent;

class RetentionIndexInitializerTests {

    private MongoTemplate mongoTemplate;
    private IndexOperations recordIndexes;
    private IndexOperations claimIndexes;
    private IndexOperations usageIndexes;
    private RetentionProperties properties;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        recordIndexes = mock(IndexOperations.class);
        claimIndexes = mock(IndexOperations.class);
        usageIndexes = mock(IndexOperations.class);
        when(mongoTemplate.indexOps(TranslationRecord.class)).thenReturn(recordIndexes);
        when(mongoTemplate.indexOps(TranslationActionClaim.class)).thenReturn(claimIndexes);
        when(mongoTemplate.indexOps(AiUsageEvent.class)).thenReturn(usageIndexes);
        when(mongoTemplate.collectionExists(any(Class.class))).thenReturn(true);
        when(recordIndexes.getIndexInfo()).thenReturn(List.of());
        when(claimIndexes.getIndexInfo()).thenReturn(List.of());
        when(usageIndexes.getIndexInfo()).thenReturn(List.of());
        properties = new RetentionProperties();
    }

    @Test
    void defaultsAreNinetyDaysSevenDaysAndTwentyFourMonths() {
        assertThat(RetentionIndexInitializer.parse(properties.getTranslationRecords()))
                .contains(Duration.ofDays(90));
        assertThat(RetentionIndexInitializer.parse(properties.getTranslationActionClaims()))
                .contains(Duration.ofDays(7));
        assertThat(RetentionIndexInitializer.parse(properties.getAiUsageEvents()))
                .contains(Duration.ofDays(730));
    }

    @ParameterizedTest
    @ValueSource(strings = {"P90D", "p90d", "  P90D  ", "PT2160H", "PT7776000S"})
    void parsesIsoDurations(String value) {
        assertThat(RetentionIndexInitializer.parse(value)).contains(Duration.ofDays(90));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "PT0S", "P0D", "-PT0S"})
    void blankAndZeroDisableRetention(String value) {
        assertThat(RetentionIndexInitializer.parse(value)).isEqualTo(Optional.empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "90", "90d", "banana", "P1M", "P1Y", "-P1D", "P-1D", "PT0.5S", "PT1.5S",
            "PT2147483648S", "P99999999D"})
    void rejectsValuesThatCannotBeATtl(String value) {
        assertThatThrownBy(() -> RetentionIndexInitializer.parse(value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTheLargestTtlMongoDbAllows() {
        assertThat(RetentionIndexInitializer.parse("PT2147483647S"))
                .contains(Duration.ofSeconds(Integer.MAX_VALUE));
    }

    @Test
    void createsOneNamedTtlIndexPerCollection() {
        initializer().ensureRetentionIndexes();

        assertThat(created(recordIndexes)).containsExactly(
                new Created("translation_record_created_at", new Document("createdAt", -1), 7_776_000L));
        assertThat(created(claimIndexes)).containsExactly(
                new Created("translation_action_claim_ttl", new Document("createdAt", 1), 604_800L));
        assertThat(created(usageIndexes)).containsExactly(
                new Created("ai_usage_event_ttl", new Document("occurredAt", 1), 63_072_000L));
    }

    @Test
    void disabledCollectionsCreateNothingAndLeaveAnExistingTtlIndexAlone() {
        properties.setTranslationRecords("");
        properties.setTranslationActionClaims("PT0S");
        when(recordIndexes.getIndexInfo()).thenReturn(List.of(
                index("translation_record_created_at", "createdAt", -1, 100L)));

        initializer().ensureRetentionIndexes();

        verify(recordIndexes, never()).createIndex(any(IndexDefinition.class));
        verify(recordIndexes, never()).alterIndex(any(), any());
        verify(recordIndexes, never()).dropIndex(any());
        verify(claimIndexes, never()).createIndex(any(IndexDefinition.class));
        assertThat(created(usageIndexes)).hasSize(1);
    }

    @Test
    void anInvalidValueSkipsOnlyItsOwnCollection() {
        properties.setTranslationRecords("90 days");

        assertThatCode(() -> initializer().ensureRetentionIndexes()).doesNotThrowAnyException();

        verify(recordIndexes, never()).createIndex(any(IndexDefinition.class));
        assertThat(created(claimIndexes)).hasSize(1);
        assertThat(created(usageIndexes)).hasSize(1);
    }

    @Test
    void anUnreachableDatabaseIsLoggedNotThrownAndSparesTheOtherCollections() {
        when(recordIndexes.getIndexInfo()).thenThrow(new DataAccessResourceFailureException("down"));
        doThrow(new DataAccessResourceFailureException("down"))
                .when(claimIndexes).createIndex(any(IndexDefinition.class));

        assertThatCode(() -> initializer().ensureRetentionIndexes()).doesNotThrowAnyException();

        assertThat(created(usageIndexes)).hasSize(1);
    }

    @Test
    void convertsAPlainIndexOnTheSameKeyInPlace() {
        when(recordIndexes.getIndexInfo()).thenReturn(List.of(
                index("translation_record_created_at", "createdAt", -1, null)));

        initializer().ensureRetentionIndexes();

        verify(recordIndexes).alterIndex(
                eq("translation_record_created_at"),
                optionsWithExpiry(Duration.ofDays(90)));
        verify(recordIndexes, never()).createIndex(any(IndexDefinition.class));
        verify(recordIndexes, never()).dropIndex(any());
    }

    @Test
    void updatesADifferentTtlInPlace() {
        when(claimIndexes.getIndexInfo()).thenReturn(List.of(
                index("translation_action_claim_ttl", "createdAt", 1, 3_600L)));

        initializer().ensureRetentionIndexes();

        verify(claimIndexes).alterIndex(
                eq("translation_action_claim_ttl"),
                optionsWithExpiry(Duration.ofDays(7)));
        verify(claimIndexes, never()).dropIndex(any());
    }

    @Test
    void leavesAnIndexAloneWhenItsTtlAlreadyMatches() {
        when(usageIndexes.getIndexInfo()).thenReturn(List.of(
                index("ai_usage_event_ttl", "occurredAt", 1, Duration.ofDays(730).getSeconds())));

        initializer().ensureRetentionIndexes();

        verify(usageIndexes, never()).alterIndex(any(), any());
        verify(usageIndexes, never()).createIndex(any(IndexDefinition.class));
        verify(usageIndexes, never()).dropIndex(any());
    }

    @Test
    void ignoresIndexesOnTheOtherDirectionOfTheKey() {
        // usage_occurred_at is the descending, non-TTL index of the usage module.
        when(usageIndexes.getIndexInfo()).thenReturn(List.of(
                index("usage_occurred_at", "occurredAt", -1, null)));

        initializer().ensureRetentionIndexes();

        verify(usageIndexes, never()).alterIndex(any(), any());
        verify(usageIndexes, never()).dropIndex(any());
        assertThat(created(usageIndexes)).hasSize(1);
    }

    @Test
    void dropsAndRecreatesOurIndexWhenTheServerCannotConvertIt() {
        when(recordIndexes.getIndexInfo()).thenReturn(List.of(
                index("translation_record_created_at", "createdAt", -1, null)));
        doThrow(new UncategorizedMongoDbException("collMod refused", null))
                .when(recordIndexes).alterIndex(any(), any());

        initializer().ensureRetentionIndexes();

        verify(recordIndexes).dropIndex("translation_record_created_at");
        assertThat(created(recordIndexes)).containsExactly(
                new Created("translation_record_created_at", new Document("createdAt", -1), 7_776_000L));
    }

    @Test
    void neverDropsAForeignIndexOrOneThatAlreadyHasATtl() {
        when(recordIndexes.getIndexInfo()).thenReturn(List.of(
                index("someone_elses_created_at", "createdAt", -1, null)));
        doThrow(new UncategorizedMongoDbException("collMod refused", null))
                .when(recordIndexes).alterIndex(any(), any());
        when(claimIndexes.getIndexInfo()).thenReturn(List.of(
                index("translation_action_claim_ttl", "createdAt", 1, 60L)));
        doThrow(new UncategorizedMongoDbException("collMod refused", null))
                .when(claimIndexes).alterIndex(any(), any());

        assertThatCode(() -> initializer().ensureRetentionIndexes()).doesNotThrowAnyException();

        verify(recordIndexes, never()).dropIndex(any());
        verify(claimIndexes, never()).dropIndex(any());
        verify(recordIndexes, never()).createIndex(any(IndexDefinition.class));
        verify(claimIndexes, never()).createIndex(any(IndexDefinition.class));
    }

    @Test
    void aMissingCollectionIsTreatedAsHavingNoIndexes() {
        when(mongoTemplate.collectionExists(TranslationRecord.class)).thenReturn(false);

        initializer().ensureRetentionIndexes();

        verify(recordIndexes, never()).getIndexInfo();
        assertThat(created(recordIndexes)).hasSize(1);
    }

    private RetentionIndexInitializer initializer() {
        return new RetentionIndexInitializer(mongoTemplate, properties);
    }

    private record Created(String name, Document keys, long expireAfterSeconds) {
    }

    /** Every index created through the operations. */
    private static List<Created> created(IndexOperations operations) {
        ArgumentCaptor<IndexDefinition> captor = ArgumentCaptor.forClass(IndexDefinition.class);
        verify(operations, atLeast(0)).createIndex(captor.capture());
        return captor.getAllValues().stream()
                .map(definition -> new Created(
                        definition.getIndexOptions().getString("name"),
                        definition.getIndexKeys(),
                        ((Number) definition.getIndexOptions().get("expireAfterSeconds")).longValue()))
                .toList();
    }

    private static IndexOptions optionsWithExpiry(Duration expiry) {
        return argThat(
                options -> options != null && expiry.equals(options.getExpire()));
    }

    private static IndexInfo index(String name, String field, int direction, Long expireAfterSeconds) {
        Document document = new Document("v", 2)
                .append("key", new Document(field, direction))
                .append("name", name);
        if (expireAfterSeconds != null) {
            document.append("expireAfterSeconds", expireAfterSeconds);
        }
        return IndexInfo.indexInfoOf(document);
    }
}
