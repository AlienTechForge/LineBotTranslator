package com.linetranslate.bot.service.translation;

import java.util.Optional;

import com.linetranslate.bot.service.ai.AiExecutionOutcome;

/**
 * Store Interface kept independent from the cache Implementation.
 */
public interface TranslationCacheStore {

    Optional<AiExecutionOutcome.Success> find(TranslationCacheKey plannedKey);

    void put(
            TranslationCacheKey plannedKey,
            TranslationCacheKey actualKey,
            AiExecutionOutcome.Success value);

    void recordSkipped(TranslationCacheSkipReason reason);
}
