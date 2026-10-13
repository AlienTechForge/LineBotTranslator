package com.linetranslate.bot.service.ai;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linetranslate.bot.config.OpenRouterConfig;
import com.linetranslate.bot.logging.SafeLog;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Deep Module for OpenRouter model discovery. Owns wire parsing, capability
 * filtering, bounded search and stale-cache fallback.
 *
 * <p>An expired snapshot is served as-is while one background refresh runs, so
 * callers never wait on the provider once a snapshot exists. Only lookups made
 * before any snapshot exists wait, and they all share a single fetch. {@link #warmUp()}
 * starts that first fetch in the background once the application is ready, so the
 * first lookup normally finds the snapshot already loaded.
 */
@Service
@Slf4j
public class OpenRouterModelCatalog implements AiModelCatalog {

    private static final int MAX_LIST_LIMIT = 5000;

    private final OpenRouterConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ExecutorService refreshExecutor;
    private final AtomicReference<CompletableFuture<Snapshot>> inFlight = new AtomicReference<>();
    private volatile Snapshot snapshot;

    @Autowired
    public OpenRouterModelCatalog(
            OpenRouterConfig config,
            @Qualifier("openRouterHttpClient") OkHttpClient httpClient,
            ObjectMapper objectMapper) {
        this(config, httpClient, objectMapper, Clock.systemUTC());
    }

    OpenRouterModelCatalog(
            OpenRouterConfig config,
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock) {
        this(config, httpClient, objectMapper, clock, newRefreshExecutor());
    }

    OpenRouterModelCatalog(
            OpenRouterConfig config,
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock,
            ExecutorService refreshExecutor) {
        this.config = config;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.refreshExecutor = refreshExecutor;
    }

    @PreDestroy
    void shutdown() {
        refreshExecutor.shutdownNow();
    }

    /**
     * Hands one refresh to the refresh executor and returns without waiting for it. It goes
     * through the same single-flight path as a lookup: a refresh that is already running is
     * joined, and a snapshot that is still fresh is left alone, so repeated calls never start
     * a second fetch. A failed fetch is handled and logged by that path and leaves the
     * fallback snapshot, exactly as a failed first lookup would.
     */
    @Override
    public void warmUp() {
        if (refreshExecutor.isShutdown()) {
            // A rejected task would run the fetch on the calling thread; warm-up must not block.
            return;
        }
        refresh();
    }

    @Override
    public AiModelPage list(String query, int limit) {
        Snapshot current = currentSnapshot();
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<AiModelDescriptor> matches = current.models().values().stream()
                .filter(model -> normalizedQuery.isEmpty()
                        || model.id().toLowerCase(Locale.ROOT).contains(normalizedQuery)
                        || model.displayName().toLowerCase(Locale.ROOT).contains(normalizedQuery))
                .toList();
        int boundedLimit = Math.max(0, Math.min(limit, MAX_LIST_LIMIT));
        return new AiModelPage(
                matches.stream().limit(boundedLimit).toList(),
                matches.size(),
                current.stale());
    }

    @Override
    public Optional<AiModelDescriptor> find(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(currentSnapshot().models().get(modelId.trim()));
    }

    private Snapshot currentSnapshot() {
        Snapshot current = snapshot;
        if (current == null) {
            return refresh().join();
        }
        if (!clock.instant().isBefore(current.expiresAt())) {
            refresh();
        }
        return current;
    }

    /** Single-flight: joins the running refresh or starts exactly one. */
    private CompletableFuture<Snapshot> refresh() {
        while (true) {
            CompletableFuture<Snapshot> running = inFlight.get();
            if (running != null) {
                return running;
            }
            CompletableFuture<Snapshot> flight = new CompletableFuture<>();
            if (!inFlight.compareAndSet(null, flight)) {
                continue;
            }
            Snapshot latest = snapshot;
            if (latest != null && clock.instant().isBefore(latest.expiresAt())) {
                // Another refresh finished between the caller's expiry check and this claim.
                inFlight.compareAndSet(flight, null);
                flight.complete(latest);
                return flight;
            }
            try {
                refreshExecutor.execute(() -> runRefresh(flight));
            } catch (RejectedExecutionException shutDown) {
                runRefresh(flight);
            }
            return flight;
        }
    }

    private void runRefresh(CompletableFuture<Snapshot> flight) {
        Snapshot refreshed = null;
        try {
            refreshed = refreshNow();
        } finally {
            // Release the slot before completing the flight. Completing first wakes the callers
            // waiting on it, and one of them can look up the snapshot again before this thread
            // clears the slot; if that snapshot is already expired the lookup would join this
            // finished flight instead of starting the next refresh, and that refresh is lost.
            inFlight.compareAndSet(flight, null);
            if (refreshed != null) {
                flight.complete(refreshed);
            } else {
                flight.completeExceptionally(new IllegalStateException("OpenRouter model catalog refresh aborted"));
            }
        }
    }

    private Snapshot refreshNow() {
        Snapshot previous = snapshot;
        long startedAt = System.nanoTime();
        Snapshot next;
        try {
            next = fetch(clock.instant());
            log.info("OpenRouter model catalog refreshed: models={}, latencyMs={}",
                    next.models().size(), elapsedMillis(startedAt));
        } catch (RuntimeException failure) {
            Instant now = clock.instant();
            next = previous != null
                    ? new Snapshot(previous.models(), retryAt(now), true)
                    : fallback(now);
            log.warn("OpenRouter model catalog refresh failed: failure={}, latencyMs={}, serving={}",
                    SafeLog.failure(failure),
                    elapsedMillis(startedAt),
                    previous != null ? "stale" : "default");
        }
        snapshot = next;
        return next;
    }

    private Snapshot fetch(Instant now) {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalStateException("OpenRouter API key is unavailable");
        }
        Request.Builder builder = new Request.Builder()
                .url(config.normalizedApiUrl() + "/models?output_modalities=text")
                .get();
        addAuthorization(builder);
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            ResponseBody body = response.body();
            String json = body == null ? "" : body.string();
            if (!response.isSuccessful() || json.isBlank()) {
                throw new IllegalStateException("OpenRouter model catalog HTTP " + response.code());
            }
            Map<String, AiModelDescriptor> models = parse(json);
            if (models.isEmpty()) {
                throw new IllegalStateException("OpenRouter model catalog returned no text models");
            }
            return new Snapshot(Map.copyOf(models), now.plus(ttl()), false);
        } catch (IOException failure) {
            throw new IllegalStateException("OpenRouter model catalog transport failure", failure);
        }
    }

    private Map<String, AiModelDescriptor> parse(String json) throws IOException {
        Map<String, AiModelDescriptor> models = new LinkedHashMap<>();
        JsonNode data = objectMapper.readTree(json).path("data");
        if (!data.isArray()) {
            return models;
        }
        for (JsonNode item : data) {
            String id = item.path("id").asText("").trim();
            Set<String> inputs = strings(item.path("architecture").path("input_modalities"));
            Set<String> outputs = strings(item.path("architecture").path("output_modalities"));
            if (id.isEmpty() || !inputs.contains("text") || !outputs.contains("text")) {
                continue;
            }
            try {
                models.put(id, new AiModelDescriptor(
                        id,
                        item.path("name").asText(id),
                        inputs,
                        outputs,
                        decimal(item.path("pricing").path("prompt")),
                        decimal(item.path("pricing").path("completion"))));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed remote catalog entries; never reflect them into LINE output.
            }
        }
        return models;
    }

    private Snapshot fallback(Instant now) {
        String model = config.getModelName();
        AiModelDescriptor descriptor = new AiModelDescriptor(
                model,
                model,
                Set.of("text", "image"),
                Set.of("text"),
                null,
                null);
        return new Snapshot(Map.of(model, descriptor), retryAt(now), true);
    }

    private void addAuthorization(Request.Builder builder) {
        String apiKey = config.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }
    }

    private Duration ttl() {
        Duration configured = config.getCatalogTtl();
        return configured == null || configured.isNegative() || configured.isZero()
                ? Duration.ofMinutes(15)
                : configured;
    }

    private Instant retryAt(Instant now) {
        return now.plus(Duration.ofSeconds(Math.min(60, Math.max(1, ttl().toSeconds()))));
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private static ExecutorService newRefreshExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "openrouter-catalog-refresh");
                    thread.setDaemon(true);
                    return thread;
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        if (array.isArray()) {
            for (JsonNode value : array) {
                String normalized = value.asText("").trim().toLowerCase(Locale.ROOT);
                if (!normalized.isEmpty()) {
                    values.add(normalized);
                }
            }
        }
        return Set.copyOf(values);
    }

    private static BigDecimal decimal(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record Snapshot(Map<String, AiModelDescriptor> models, Instant expiresAt, boolean stale) {
    }
}
