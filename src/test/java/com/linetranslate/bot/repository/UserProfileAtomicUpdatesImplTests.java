package com.linetranslate.bot.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

import com.linetranslate.bot.model.UserProfile;
import com.mongodb.client.result.UpdateResult;

class UserProfileAtomicUpdatesImplTests {

    private static final String USER_ID = "U-atomic";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 13, 12, 30, 15);

    private MongoTemplate mongoTemplate;
    private UserProfileAtomicUpdatesImpl updates;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        updates = new UserProfileAtomicUpdatesImpl(mongoTemplate);
    }

    @Test
    void preferenceSetIsOneFindAndModifyThatSetsOnlyThatFieldAndReturnsTheUpdatedProfile() {
        UserProfile stored = UserProfile.builder().userId(USER_ID).preferredLanguage("ja").build();
        when(mongoTemplate.findAndModify(
                any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
                eq(UserProfile.class))).thenReturn(stored);

        Optional<UserProfile> result = updates.setPreferredLanguage(USER_ID, "ja");

        assertThat(result).containsSame(stored);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        ArgumentCaptor<FindAndModifyOptions> options = ArgumentCaptor.forClass(FindAndModifyOptions.class);
        verify(mongoTemplate).findAndModify(
                query.capture(), update.capture(), options.capture(), eq(UserProfile.class));
        assertThat(query.getValue().getQueryObject()).isEqualTo(new Document("userId", USER_ID));
        assertThat(update.getValue().getUpdateObject())
                .isEqualTo(new Document("$set", new Document("preferredLanguage", "ja")));
        assertThat(options.getValue().isReturnNew()).isTrue();
        verify(mongoTemplate, never()).save(any());
    }

    @Test
    void everyPreferenceSetterTouchesExactlyItsOwnStoredField() {
        when(mongoTemplate.findAndModify(
                any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
                eq(UserProfile.class))).thenReturn(UserProfile.builder().userId(USER_ID).build());

        updates.setPreferredChineseTargetLanguage(USER_ID, "ko");
        updates.setPreferredModel(USER_ID, "openai/gpt-4o-mini");
        updates.setPreferredTranslationStyle(USER_ID, "business");

        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongoTemplate, org.mockito.Mockito.times(3)).findAndModify(
                any(Query.class), update.capture(), any(FindAndModifyOptions.class), eq(UserProfile.class));
        assertThat(update.getAllValues()).extracting(UpdateDefinition::getUpdateObject).containsExactly(
                new Document("$set", new Document("preferredChineseTargetLanguage", "ko")),
                new Document("$set", new Document("preferredModel", "openai/gpt-4o-mini")),
                new Document("$set", new Document("preferredTranslationStyle", "business")));
    }

    @Test
    void preferenceSetOfAMissingProfileReturnsEmptyInsteadOfCreatingOne() {
        assertThat(updates.setPreferredModel(USER_ID, "openai/gpt-4o-mini")).isEmpty();
    }

    @Test
    void displayNameIsASingleFieldSetReportingWhetherAProfileMatched() {
        when(mongoTemplate.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(UserProfile.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        assertThat(updates.setDisplayName(USER_ID, "Jason")).isTrue();

        assertThat(singleUpdate().getUpdateObject())
                .isEqualTo(new Document("$set", new Document("displayName", "Jason")));
    }

    @Test
    void displayNameOfAMissingProfileReportsNoMatch() {
        when(mongoTemplate.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(UserProfile.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThat(updates.setDisplayName(USER_ID, "Jason")).isFalse();
    }

    @Test
    void lineProfileSetsGivenFieldsKeepsAMissingPictureAndRemovesAMissingStatus() {
        matchOneProfile();

        assertThat(updates.setLineProfile(USER_ID, "Jason", null, null)).isTrue();

        assertThat(singleUpdate().getUpdateObject()).isEqualTo(new Document()
                .append("$set", new Document("displayName", "Jason"))
                .append("$unset", new Document("statusMessage", 1)));
    }

    @Test
    void lineProfileWithAllValuesSetsEveryLineField() {
        matchOneProfile();

        updates.setLineProfile(USER_ID, "Jason", "https://example.com/p.jpg", "Hi");

        assertThat(singleUpdate().getUpdateObject()).isEqualTo(new Document("$set", new Document()
                .append("displayName", "Jason")
                .append("pictureUrl", "https://example.com/p.jpg")
                .append("statusMessage", "Hi")));
    }

    @Test
    void lastInteractionIsASingleFieldSet() {
        matchOneProfile();

        assertThat(updates.setLastInteractionAt(USER_ID, AT)).isTrue();

        assertThat(singleUpdate().getUpdateObject())
                .isEqualTo(new Document("$set", new Document("lastInteractionAt", AT)));
    }

    @Test
    void translationActivityIsOneAggregationPipelineUpdateOfTheCountersAndRecentLists() {
        matchOneProfile();

        assertThat(updates.recordTranslation(USER_ID, AT, "$100 おはよう", "ja", false)).isTrue();

        UpdateDefinition update = singleUpdate();
        assertThat(update).isInstanceOf(AggregationUpdate.class);
        List<Document> pipeline = ((AggregationUpdate) update).toPipeline(Aggregation.DEFAULT_CONTEXT);
        assertThat(pipeline).containsExactly(
                set("lastInteractionAt", Date.from(AT.atZone(ZoneId.systemDefault()).toInstant())),
                set("totalTranslations", Document.parse(
                        "{$add: [{$ifNull: ['$totalTranslations', 0]}, 1]}")),
                set("textTranslations", Document.parse(
                        "{$add: [{$ifNull: ['$textTranslations', 0]}, 1]}")),
                set("recentTranslations", Document.parse(
                        "{$slice: [{$concatArrays: [[{$literal: '$100 おはよう'}],"
                                + " {$ifNull: ['$recentTranslations', []]}]}, 5]}")),
                set("recentLanguages", Document.parse(
                        "{$slice: [{$concatArrays: [[{$literal: 'ja'}], {$filter: {"
                                + "input: {$ifNull: ['$recentLanguages', []]},"
                                + " cond: {$ne: ['$$this', {$literal: 'ja'}]}}}]}, 5]}")));
    }

    @Test
    void imageActivityBumpsTheImageCounterAndNoUnsupportedLanguageTouchesRecentLanguages() {
        matchOneProfile();

        updates.recordTranslation(USER_ID, AT, "text", null, true);

        List<Document> pipeline = ((AggregationUpdate) singleUpdate()).toPipeline(Aggregation.DEFAULT_CONTEXT);
        assertThat(pipeline).extracting(stage -> ((Document) stage.get("$set")).keySet().iterator().next())
                .containsExactly("lastInteractionAt", "totalTranslations", "imageTranslations",
                        "recentTranslations");
    }

    @Test
    void activityOfAMissingProfileReportsNoMatch() {
        when(mongoTemplate.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(UserProfile.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThat(updates.recordTranslation("U-missing", AT, "text", "ja", false)).isFalse();
    }

    private void matchOneProfile() {
        when(mongoTemplate.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(UserProfile.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
    }

    private UpdateDefinition singleUpdate() {
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongoTemplate).updateFirst(query.capture(), update.capture(), eq(UserProfile.class));
        assertThat(query.getValue().getQueryObject()).isEqualTo(new Document("userId", USER_ID));
        return update.getValue();
    }

    private static Document set(String field, Object value) {
        return new Document("$set", new Document(field, value));
    }
}
