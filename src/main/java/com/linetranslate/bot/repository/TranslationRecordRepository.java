package com.linetranslate.bot.repository;

import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.linetranslate.bot.model.TranslationRecord;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TranslationRecordRepository extends MongoRepository<TranslationRecord, String> {

    List<TranslationRecord> findByUserId(String userId);

    Optional<TranslationRecord> findByIdAndUserId(String id, String userId);

    List<TranslationRecord> findByUserIdOrderByTimestampDesc(String userId);

    List<TranslationRecord> findByUserIdAndTimestampBetween(String userId, LocalDateTime start, LocalDateTime end);

    List<TranslationRecord> findBySourceTextAndSourceLanguageAndTargetLanguage(
            String sourceText, String sourceLanguage, String targetLanguage);

    long countByAiProvider(String aiProvider);

    long countByIsImageTranslation(boolean isImageTranslation);

    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    /**
     * Records per calendar day ({@code MM-dd}, ascending) created strictly between
     * {@code start} and {@code end}, counted by MongoDB. {@code timeZone} must be the
     * zone the {@code LocalDateTime} values were persisted in.
     */
    @Aggregation(pipeline = {
            "{ $match: { createdAt: { $gt: ?0, $lt: ?1 } } }",
            "{ $group: { _id: { $dateToString: { format: '%m-%d', date: '$createdAt', timezone: ?2 } },"
                    + " count: { $sum: 1 } } }",
            "{ $sort: { _id: 1 } }",
            "{ $project: { _id: 0, key: '$_id', count: 1 } }"
    })
    List<KeyedCount> countPerDayByCreatedAtBetween(
            LocalDateTime start, LocalDateTime end, String timeZone);

    /**
     * Count, image count and mean processing time of the records created strictly
     * between {@code start} and {@code end}; empty when there are none. A record
     * without a processing time counts as 0 ms, as the mapped primitive field did.
     */
    @Aggregation(pipeline = {
            "{ $match: { createdAt: { $gt: ?0, $lt: ?1 } } }",
            "{ $group: { _id: null, total: { $sum: 1 },"
                    + " imageTranslations: { $sum: { $cond: ['$isImageTranslation', 1, 0] } },"
                    + " averageProcessingTimeMs: { $avg: { $ifNull: ['$processingTimeMs', 0] } } } }"
    })
    Optional<TranslationSummary> summarizeByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    /** Records per non-null AI provider created strictly between {@code start} and {@code end}. */
    @Aggregation(pipeline = {
            "{ $match: { createdAt: { $gt: ?0, $lt: ?1 }, aiProvider: { $ne: null } } }",
            "{ $group: { _id: '$aiProvider', count: { $sum: 1 } } }",
            "{ $project: { _id: 0, key: '$_id', count: 1 } }"
    })
    List<KeyedCount> countPerAiProviderByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    record KeyedCount(String key, long count) {
    }

    record TranslationSummary(long total, long imageTranslations, double averageProcessingTimeMs) {
    }
}
