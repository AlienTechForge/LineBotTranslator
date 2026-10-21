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

    Optional<TranslationRecord> findByIdAndUserId(String id, String userId);

    long countByAiProvider(String aiProvider);

    long countByIsImageTranslation(boolean isImageTranslation);

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

    /**
     * What the image-translator service cost for the image records created in
     * {@code [start, end)}: the sum of their {@code imageCostUsd} and how many carried one. Empty
     * when there are none.
     */
    @Aggregation(pipeline = {
            "{ $match: { createdAt: { $gte: ?0, $lt: ?1 }, isImageTranslation: true,"
                    + " imageCostUsd: { $ne: null } } }",
            "{ $group: { _id: null, totalUsd: { $sum: '$imageCostUsd' }, records: { $sum: 1 } } }"
    })
    Optional<ImageCostSummary> sumImageCostByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    record KeyedCount(String key, long count) {
    }

    record ImageCostSummary(double totalUsd, long records) {
    }

    record TranslationSummary(long total, long imageTranslations, double averageProcessingTimeMs) {
    }
}
