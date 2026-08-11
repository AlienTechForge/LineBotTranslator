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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linetranslate.bot.config.OpenRouterConfig;
import com.linetranslate.bot.logging.SafeLog;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Deep Module for OpenRouter model discovery. Owns wire parsing, capability
 * filtering, bounded search and stale-cache fallback.
 */
@Service
@Slf4j
public class OpenRouterModelCatalog implements AiModelCatalog {

    private static final int MAX_LIST_LIMIT = 5000;

    private final OpenRouterConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
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
        this.config = config;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
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
        Instant now = clock.instant();
        if (current != null && now.isBefore(current.expiresAt())) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            if (current != null && now.isBefore(current.expiresAt())) {
                return current;
            }
            try {
                Snapshot refreshed = fetch(now);
                snapshot = refreshed;
                return refreshed;
            } catch (RuntimeException failure) {
                log.warn("OpenRouter model catalog refresh failed: failure={}", SafeLog.failure(failure));
                if (current != null) {
                    Snapshot stale = new Snapshot(current.models(), retryAt(now), true);
                    snapshot = stale;
                    return stale;
                }
                Snapshot fallback = fallback(now);
                snapshot = fallback;
                return fallback;
            }
        }
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
