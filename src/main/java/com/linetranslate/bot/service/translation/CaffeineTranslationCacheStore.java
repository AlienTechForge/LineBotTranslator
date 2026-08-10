package com.linetranslate.bot.service.translation;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

import com.linetranslate.bot.service.ai.AiExecutionOutcome;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Bounded, expiring cache Implementation with low-cardinality metrics.
 */
@Component
public class CaffeineTranslationCacheStore implements TranslationCacheStore {

    private static final String CACHE_NAME = "translations";

    private final Cache<TranslationCacheKey, TranslationCacheKey> routeIndex;
    private final Cache<TranslationCacheKey, AiExecutionOutcome.Success> entries;
    private final Counter hits;
    private final Counter misses;
    private final Counter stored;
    private final MeterRegistry meterRegistry;

    @Autowired
    public CaffeineTranslationCacheStore(
            TranslationCacheProperties properties,
            MeterRegistry meterRegistry) {
        this(properties, meterRegistry, Ticker.systemTicker());
    }

    CaffeineTranslationCacheStore(
            TranslationCacheProperties properties,
            MeterRegistry meterRegistry,
            Ticker ticker) {
        validate(properties);
        this.meterRegistry = meterRegistry;
        this.routeIndex = Caffeine.newBuilder()
                .maximumSize(properties.getMaxEntries())
                .expireAfterWrite(properties.getTtl())
                .ticker(ticker)
                .executor(Runnable::run)
                .recordStats()
                .build();
        this.entries = Caffeine.newBuilder()
                .maximumSize(properties.getMaxEntries())
                .expireAfterWrite(properties.getTtl())
                .ticker(ticker)
                .executor(Runnable::run)
                .recordStats()
                .removalListener(this::recordRemoval)
                .build();
        this.hits = requestCounter("hit");
        this.misses = requestCounter("miss");
        this.stored = writeCounter("stored", "success");
        Gauge.builder("translation.cache.entries", entries, Cache::estimatedSize)
                .description("Current number of bounded translation cache entries")
                .tag("cache", CACHE_NAME)
                .register(meterRegistry);
    }

    @Override
    public Optional<AiExecutionOutcome.Success> find(TranslationCacheKey plannedKey) {
        TranslationCacheKey actualKey = routeIndex.getIfPresent(plannedKey);
        if (actualKey == null) {
            misses.increment();
            return Optional.empty();
        }
        AiExecutionOutcome.Success value = entries.getIfPresent(actualKey);
        if (value == null) {
            routeIndex.invalidate(plannedKey);
            misses.increment();
            return Optional.empty();
        }
        hits.increment();
        return Optional.of(value);
    }

    @Override
    public void put(
            TranslationCacheKey plannedKey,
            TranslationCacheKey actualKey,
            AiExecutionOutcome.Success value) {
        entries.put(actualKey, value);
        routeIndex.put(plannedKey, actualKey);
        stored.increment();
    }

    @Override
    public void recordSkipped(TranslationCacheSkipReason reason) {
        writeCounter("skipped", reason.name().toLowerCase(Locale.ROOT)).increment();
    }

    CacheStats stats() {
        return routeIndex.stats();
    }

    long estimatedSize() {
        return entries.estimatedSize();
    }

    Set<TranslationCacheKey> keys() {
        return Set.copyOf(entries.asMap().keySet());
    }

    void cleanUp() {
        routeIndex.cleanUp();
        entries.cleanUp();
    }

    private Counter requestCounter(String result) {
        return Counter.builder("translation.cache.requests")
                .description("Translation cache lookup results")
                .tag("cache", CACHE_NAME)
                .tag("result", result)
                .register(meterRegistry);
    }

    private Counter writeCounter(String result, String reason) {
        return Counter.builder("translation.cache.writes")
                .description("Translation cache write decisions")
                .tag("cache", CACHE_NAME)
                .tag("result", result)
                .tag("reason", reason)
                .register(meterRegistry);
    }

    private void recordRemoval(
            TranslationCacheKey ignoredKey,
            AiExecutionOutcome.Success ignoredValue,
            RemovalCause cause) {
        if (!cause.wasEvicted()) {
            return;
        }
        Counter.builder("translation.cache.evictions")
                .description("Translation cache evictions")
                .tag("cache", CACHE_NAME)
                .tag("cause", cause.name().toLowerCase(Locale.ROOT))
                .register(meterRegistry)
                .increment();
    }

    private static void validate(TranslationCacheProperties properties) {
        if (properties.getTtl() == null || properties.getTtl().isZero()
                || properties.getTtl().isNegative()) {
            throw new IllegalArgumentException("Translation cache TTL must be positive");
        }
        if (properties.getMaxEntries() < 1) {
            throw new IllegalArgumentException("Translation cache maximum entries must be positive");
        }
    }
}
