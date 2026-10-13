package com.linetranslate.bot.service.translation;

import java.util.Set;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Bounded memo of AI language detections. Detection runs before the
 * Translation Cache is consulted and its answer is still needed on a cache
 * hit, so without this memo every repeated text would pay a billed detection
 * attempt. It shares the Translation Cache TTL and size so a detection lives
 * about as long as the translation it serves.
 *
 * <p>The key is only a SHA-256 digest of the text. Detection model, prompt and
 * default Chinese variant are fixed per process, so they need no key part.
 * {@code unknown} is never stored, so a failed detection is retried.
 */
@Component
public class LanguageDetectionCache {

    static final String UNKNOWN = "unknown";
    private static final String CACHE_NAME = "language-detection";

    private final Cache<String, String> entries;
    private final Counter hits;
    private final Counter misses;

    public LanguageDetectionCache(TranslationCacheProperties properties, MeterRegistry meterRegistry) {
        this.entries = Caffeine.newBuilder()
                .maximumSize(properties.getMaxEntries())
                .expireAfterWrite(properties.getTtl())
                .executor(Runnable::run)
                .build();
        this.hits = requestCounter(meterRegistry, "hit");
        this.misses = requestCounter(meterRegistry, "miss");
    }

    /**
     * Returns the remembered language of {@code text}, or runs {@code detector}
     * and remembers its answer unless it is {@code unknown}. The detector runs
     * outside the cache lock because it is a remote call.
     */
    public String detect(String text, UnaryOperator<String> detector) {
        String digest = TranslationCacheKeyFactory.digest(text);
        String remembered = entries.getIfPresent(digest);
        if (remembered != null) {
            hits.increment();
            return remembered;
        }
        misses.increment();
        String detected = detector.apply(text);
        if (detected != null && !UNKNOWN.equals(detected)) {
            entries.put(digest, detected);
        }
        return detected;
    }

    Set<String> keys() {
        return Set.copyOf(entries.asMap().keySet());
    }

    private static Counter requestCounter(MeterRegistry meterRegistry, String result) {
        return Counter.builder("language.detection.cache.requests")
                .description("Language detection cache lookup results")
                .tag("cache", CACHE_NAME)
                .tag("result", result)
                .register(meterRegistry);
    }
}
