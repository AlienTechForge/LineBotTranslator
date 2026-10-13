package com.linetranslate.bot.repository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

import com.linetranslate.bot.model.UserProfile;

/** Spring Data repository fragment implementing {@link UserProfileAtomicUpdates}. */
public class UserProfileAtomicUpdatesImpl implements UserProfileAtomicUpdates {

    private final MongoTemplate mongoTemplate;

    public UserProfileAtomicUpdatesImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<UserProfile> setPreferredLanguage(String userId, String language) {
        return setAndReturn(userId, "preferredLanguage", language);
    }

    @Override
    public Optional<UserProfile> setPreferredChineseTargetLanguage(String userId, String language) {
        return setAndReturn(userId, "preferredChineseTargetLanguage", language);
    }

    @Override
    public Optional<UserProfile> setPreferredModel(String userId, String model) {
        return setAndReturn(userId, "preferredModel", model);
    }

    @Override
    public Optional<UserProfile> setPreferredTranslationStyle(String userId, String presetId) {
        return setAndReturn(userId, "preferredTranslationStyle", presetId);
    }

    @Override
    public boolean setDisplayName(String userId, String displayName) {
        return apply(userId, new Update().set("displayName", displayName));
    }

    @Override
    public boolean setLineProfile(
            String userId, String displayName, String pictureUrl, String statusMessage) {
        Update update = new Update();
        setOrUnset(update, "displayName", displayName);
        if (pictureUrl != null) {
            update.set("pictureUrl", pictureUrl);
        }
        setOrUnset(update, "statusMessage", statusMessage);
        return apply(userId, update);
    }

    @Override
    public boolean setLastInteractionAt(String userId, LocalDateTime at) {
        return apply(userId, new Update().set("lastInteractionAt", at));
    }

    @Override
    public boolean recordTranslation(
            String userId,
            LocalDateTime at,
            String translatedText,
            String targetLanguage,
            boolean imageTranslation) {
        // One aggregation-pipeline update: the server applies every stage to one document
        // atomically. $ifNull keeps documents that lack a field (or hold null) valid, and $literal
        // keeps user text starting with "$" from being read as a field path.
        String kindCounter = imageTranslation ? "imageTranslations" : "textTranslations";
        AggregationUpdate update = AggregationUpdate.update()
                .set("lastInteractionAt").toValue(toDate(at))
                .set("totalTranslations").toValue(increment("totalTranslations"))
                .set(kindCounter).toValue(increment(kindCounter))
                .set("recentTranslations").toValue(newestFirst("recentTranslations", translatedText, false));
        if (targetLanguage != null) {
            update.set("recentLanguages").toValue(newestFirst("recentLanguages", targetLanguage, true));
        }
        return apply(userId, update);
    }

    private Optional<UserProfile> setAndReturn(String userId, String field, String value) {
        return Optional.ofNullable(mongoTemplate.findAndModify(
                byUserId(userId),
                new Update().set(field, value),
                FindAndModifyOptions.options().returnNew(true),
                UserProfile.class));
    }

    private boolean apply(String userId, UpdateDefinition update) {
        return mongoTemplate.updateFirst(byUserId(userId), update, UserProfile.class)
                .getMatchedCount() > 0;
    }

    private static Query byUserId(String userId) {
        return Query.query(Criteria.where("userId").is(userId));
    }

    private static void setOrUnset(Update update, String field, String value) {
        if (value == null) {
            update.unset(field);
        } else {
            update.set(field, value);
        }
    }

    /** {@code {$add: [{$ifNull: ["$field", 0]}, 1]}}. */
    private static Document increment(String field) {
        return new Document("$add", List.of(ifNull(field, 0), 1));
    }

    /**
     * Expression for {@code [value] + existing}, cut to {@link #RECENT_LIMIT}; with
     * {@code removeExisting} an earlier occurrence of {@code value} is dropped first.
     */
    private static Document newestFirst(String field, String value, boolean removeExisting) {
        Document literal = new Document("$literal", value);
        Object existing = ifNull(field, List.of());
        if (removeExisting) {
            existing = new Document("$filter", new Document("input", existing)
                    .append("cond", new Document("$ne", List.of("$$this", literal))));
        }
        return new Document("$slice", List.of(
                new Document("$concatArrays", List.of(List.of(literal), existing)),
                RECENT_LIMIT));
    }

    private static Document ifNull(String field, Object fallback) {
        return new Document("$ifNull", List.of("$" + field, fallback));
    }

    /** Same instant conversion Spring Data applies when it maps a {@link LocalDateTime}. */
    private static Date toDate(LocalDateTime at) {
        return Date.from(at.atZone(ZoneId.systemDefault()).toInstant());
    }
}
