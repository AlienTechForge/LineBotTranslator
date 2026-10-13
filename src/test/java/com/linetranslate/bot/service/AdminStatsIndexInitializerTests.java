package com.linetranslate.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;

import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.model.UserProfile;

class AdminStatsIndexInitializerTests {

    private MongoTemplate mongoTemplate;
    private IndexOperations recordIndexes;
    private IndexOperations profileIndexes;
    private AdminStatsIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        recordIndexes = mock(IndexOperations.class);
        profileIndexes = mock(IndexOperations.class);
        when(mongoTemplate.indexOps(TranslationRecord.class)).thenReturn(recordIndexes);
        when(mongoTemplate.indexOps(UserProfile.class)).thenReturn(profileIndexes);
        when(mongoTemplate.collectionExists(any(Class.class))).thenReturn(true);
        when(recordIndexes.getIndexInfo()).thenReturn(List.of());
        when(profileIndexes.getIndexInfo()).thenReturn(List.of());
        initializer = new AdminStatsIndexInitializer(mongoTemplate);
    }

    @Test
    void createsEachStatisticsIndexOnceUnderItsOwnName() {
        initializer.ensureIndexes();

        assertThat(created(recordIndexes)).containsExactly(
                new Created("translation_record_created_at", new Document("createdAt", -1)),
                new Created("translation_record_ai_provider", new Document("aiProvider", 1)),
                new Created("translation_record_is_image_translation",
                        new Document("isImageTranslation", 1)));
        assertThat(created(profileIndexes)).containsExactly(
                new Created("user_profile_last_interaction_at",
                        new Document("lastInteractionAt", -1)));
    }

    @Test
    void anIndexThatAlreadyExistsIsCreatedAgainAsANoOpAndNothingIsDropped() {
        when(recordIndexes.getIndexInfo()).thenReturn(List.of(
                index("translation_record_created_at", "createdAt", -1, null),
                index("translation_record_ai_provider", "aiProvider", 1, null),
                index("translation_record_is_image_translation", "isImageTranslation", 1, null)));

        // MongoDB answers a createIndex with an identical definition without changing
        // anything, so the initializer may repeat it on every start.
        assertThatCode(() -> {
            initializer.ensureIndexes();
            initializer.ensureIndexes();
        }).doesNotThrowAnyException();

        assertThat(created(recordIndexes)).hasSize(6);
        assertThat(created(profileIndexes)).hasSize(2);
        verifyNoDrops();
    }

    @Test
    void anIndexThatCarriesATtlIsNotCreatedAgain() {
        when(recordIndexes.getIndexInfo()).thenReturn(List.of(
                index("translation_record_created_at", "createdAt", -1, 7_776_000L)));

        initializer.ensureIndexes();

        assertThat(created(recordIndexes)).extracting(Created::name).containsExactly(
                "translation_record_ai_provider", "translation_record_is_image_translation");
        assertThat(created(profileIndexes)).hasSize(1);
    }

    @Test
    void aRefusedIndexIsLoggedAndTheOthersAreStillCreated() {
        doThrow(new DataAccessResourceFailureException("refused"))
                .when(recordIndexes).createIndex(any(IndexDefinition.class));

        assertThatCode(initializer::ensureIndexes).doesNotThrowAnyException();

        assertThat(created(recordIndexes)).hasSize(3);
        assertThat(created(profileIndexes)).hasSize(1);
    }

    @Test
    void anUnreachableDatabaseDoesNotStopStartup() {
        when(mongoTemplate.collectionExists(any(Class.class)))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThatCode(initializer::ensureIndexes).doesNotThrowAnyException();

        assertThat(created(recordIndexes)).isEmpty();
        assertThat(created(profileIndexes)).isEmpty();
    }

    @Test
    void aMissingCollectionIsNotInspectedForIndexes() {
        when(mongoTemplate.collectionExists(any(Class.class))).thenReturn(false);

        initializer.ensureIndexes();

        assertThat(created(recordIndexes)).hasSize(3);
        assertThat(created(profileIndexes)).hasSize(1);
        verify(recordIndexes, never()).getIndexInfo();
        verify(profileIndexes, never()).getIndexInfo();
    }

    private void verifyNoDrops() {
        verify(recordIndexes, never()).dropIndex(any());
        verify(profileIndexes, never()).dropIndex(any());
    }

    private static List<Created> created(IndexOperations operations) {
        ArgumentCaptor<IndexDefinition> captor = ArgumentCaptor.forClass(IndexDefinition.class);
        verify(operations, atLeast(0)).createIndex(captor.capture());
        return captor.getAllValues().stream()
                .map(definition -> new Created(
                        definition.getIndexOptions().getString("name"),
                        definition.getIndexKeys()))
                .toList();
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

    private record Created(String name, Document keys) {
    }
}
