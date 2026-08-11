package com.linetranslate.bot.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.linetranslate.bot.model.TranslationRecord;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TranslationRecordRepository extends MongoRepository<TranslationRecord, String> {

    List<TranslationRecord> findByUserId(String userId);

    List<TranslationRecord> findByUserIdOrderByTimestampDesc(String userId);

    List<TranslationRecord> findByUserIdAndTimestampBetween(String userId, LocalDateTime start, LocalDateTime end);

    List<TranslationRecord> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    List<TranslationRecord> findBySourceTextAndSourceLanguageAndTargetLanguage(
            String sourceText, String sourceLanguage, String targetLanguage);

    long countByAiProvider(String aiProvider);

    long countByIsImageTranslation(boolean isImageTranslation);

    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}
