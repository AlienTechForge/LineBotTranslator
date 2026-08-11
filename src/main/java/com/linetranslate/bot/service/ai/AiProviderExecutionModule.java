package com.linetranslate.bot.service.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.preference.UserPreferences;

import lombok.extern.slf4j.Slf4j;

/**
 * Deep Module for provider selection, model validation, bounded fallback and
 * observable structured outcomes.
 */
@Service
@Slf4j
public class AiProviderExecutionModule {

    private final Map<String, AiProviderAdapter> adapters;
    private final String defaultProvider;

    public AiProviderExecutionModule(
            List<AiProviderAdapter> adapters,
            @Value("${app.ai.default-provider:openai}") String defaultProvider) {
        Map<String, AiProviderAdapter> indexedAdapters = new LinkedHashMap<>();
        for (AiProviderAdapter adapter : adapters) {
            indexedAdapters.put(normalizeProvider(adapter.providerName()), adapter);
        }
        this.adapters = Map.copyOf(indexedAdapters);
        String configuredDefault = normalizeProvider(defaultProvider);
        this.defaultProvider = indexedAdapters.containsKey(configuredDefault)
                ? configuredDefault
                : indexedAdapters.keySet().stream().findFirst().orElse(configuredDefault);

        if (indexedAdapters.isEmpty()) {
            log.warn("No AI provider Adapter is configured");
        } else {
            log.info("AI provider execution Module initialized: default={}, adapters={}",
                    this.defaultProvider, indexedAdapters.keySet());
        }
    }

    public AiExecutionOutcome translateTextOutcome(
            UserPreferences preferences,
            String text,
            String targetLanguage) {
        return executeWithFallback(
                AiProviderOperation.TRANSLATE_TEXT,
                preferredProvider(preferences),
                adapter -> preferredModel(adapter, preferences),
                model -> AiProviderRequest.translate(model, text, targetLanguage));
    }

    /**
     * Resolves the primary text route without executing a provider request. Cache
     * callers use this immutable fact as part of their isolation key.
     */
    public AiProviderRoute planText(UserPreferences preferences) {
        String requestedProvider = preferredProvider(preferences);
        AiProviderAdapter primary = adapters.get(requestedProvider);
        if (primary == null) {
            primary = adapters.get(alternateProvider(requestedProvider));
        }
        if (primary == null) {
            return new AiProviderRoute("none", "none");
        }
        return new AiProviderRoute(
                primary.providerName(),
                effectiveModel(primary, preferredModel(primary, preferences)));
    }

    public AiExecutionResult translateText(
            UserPreferences preferences,
            String text,
            String targetLanguage) {
        return translateTextOutcome(preferences, text, targetLanguage).resultOrThrow();
    }

    public AiExecutionOutcome processImageOutcome(
            UserPreferences preferences,
            String prompt,
            String imageData) {
        return executeWithFallback(
                AiProviderOperation.PROCESS_IMAGE,
                preferredProvider(preferences),
                adapter -> preferredModel(adapter, preferences),
                model -> AiProviderRequest.image(model, prompt, imageData));
    }

    public AiExecutionResult processImage(
            UserPreferences preferences,
            String prompt,
            String imageData) {
        return processImageOutcome(preferences, prompt, imageData).resultOrThrow();
    }

    public AiExecutionOutcome generateTextOutcome(
            String provider,
            String requestedModel,
            String prompt) {
        String normalizedProvider = normalizeProvider(provider == null ? defaultProvider : provider);
        return executeWithFallback(
                AiProviderOperation.GENERATE_TEXT,
                normalizedProvider,
                adapter -> normalizedProvider.equals(normalizeProvider(adapter.providerName()))
                        ? requestedModel
                        : adapter.defaultModel(),
                model -> AiProviderRequest.generate(model, prompt));
    }

    public AiExecutionResult generateText(String provider, String requestedModel, String prompt) {
        return generateTextOutcome(provider, requestedModel, prompt).resultOrThrow();
    }

    private AiExecutionOutcome executeWithFallback(
            AiProviderOperation operation,
            String requestedProvider,
            Function<AiProviderAdapter, String> requestedModel,
            Function<String, AiProviderRequest> requestFactory) {
        List<AiProviderAttempt> attempts = new ArrayList<>();
        AiProviderAdapter primary = adapters.get(requestedProvider);
        AiProviderAdapter fallback = adapters.get(alternateProvider(requestedProvider));

        if (primary == null) {
            primary = fallback;
            fallback = null;
        }
        if (primary == null) {
            AiProviderException unavailable = failure(
                    AiProviderException.Outcome.CONFIGURATION_ERROR,
                    "none",
                    "none",
                    "NO_CONFIGURED_PROVIDER");
            return new AiExecutionOutcome.Failure(AiExecutionFailure.from(unavailable, attempts));
        }

        AttemptResult primaryResult = attempt(
                operation,
                primary,
                effectiveModel(primary, requestedModel.apply(primary)),
                requestFactory,
                attempts);
        if (primaryResult.response() != null) {
            return success(primary, primaryResult.response(), attempts, false);
        }

        AiProviderException primaryFailure = primaryResult.failure();
        if (!isFallbackEligible(primaryFailure) || fallback == null) {
            logFailure("total_failure", operation, primaryFailure);
            return new AiExecutionOutcome.Failure(AiExecutionFailure.from(primaryFailure, attempts));
        }

        AttemptResult fallbackResult = attempt(
                operation,
                fallback,
                effectiveModel(fallback, requestedModel.apply(fallback)),
                requestFactory,
                attempts);
        if (fallbackResult.response() != null) {
            return success(fallback, fallbackResult.response(), attempts, true);
        }

        AiProviderException fallbackFailure = fallbackResult.failure();
        logFailure("total_failure", operation, fallbackFailure);
        return new AiExecutionOutcome.Failure(AiExecutionFailure.from(fallbackFailure, attempts));
    }

    private AttemptResult attempt(
            AiProviderOperation operation,
            AiProviderAdapter adapter,
            String model,
            Function<String, AiProviderRequest> requestFactory,
            List<AiProviderAttempt> attempts) {
        AiProviderRequest request = requestFactory.apply(model);
        if (!adapter.capabilities().contains(operation)) {
            return failedAttempt(adapterFailure(
                    adapter,
                    model,
                    AiProviderException.Outcome.CONFIGURATION_ERROR,
                    "UNSUPPORTED_OPERATION"), attempts, 0);
        }
        if (!adapter.availableModels().contains(model)) {
            return failedAttempt(adapterFailure(
                    adapter,
                    model,
                    AiProviderException.Outcome.CONFIGURATION_ERROR,
                    "UNSUPPORTED_MODEL"), attempts, 0);
        }

        long startedAt = System.nanoTime();
        try {
            AiProviderResponse response = adapter.execute(request);
            long latencyMillis = elapsedMillis(startedAt);
            attempts.add(AiProviderAttempt.success(
                    adapter.providerName(), response.model(), latencyMillis));
            return new AttemptResult(response, null);
        } catch (AiProviderException failure) {
            long latencyMillis = elapsedMillis(startedAt);
            logFailure("provider_failure", operation, failure);
            return failedAttempt(failure, attempts, latencyMillis);
        } catch (RuntimeException unexpected) {
            long latencyMillis = elapsedMillis(startedAt);
            AiProviderException normalized = new AiProviderException(
                    AiProviderException.Outcome.UNEXPECTED_ERROR,
                    adapter.providerName(),
                    model,
                    unexpected.getClass().getSimpleName(),
                    UUID.randomUUID().toString(),
                    -1,
                    unexpected);
            logFailure("provider_failure", operation, normalized);
            return failedAttempt(normalized, attempts, latencyMillis);
        }
    }

    private AttemptResult failedAttempt(
            AiProviderException failure,
            List<AiProviderAttempt> attempts,
            long latencyMillis) {
        attempts.add(AiProviderAttempt.failure(failure, latencyMillis));
        return new AttemptResult(null, failure);
    }

    private AiExecutionOutcome success(
            AiProviderAdapter adapter,
            AiProviderResponse response,
            List<AiProviderAttempt> attempts,
            boolean fallbackUsed) {
        long totalLatency = attempts.stream().mapToLong(AiProviderAttempt::latencyMillis).sum();
        AiExecutionResult execution = new AiExecutionResult(
                response.text(),
                adapter.providerName(),
                response.model(),
                response.tokenUsage(),
                totalLatency,
                fallbackUsed,
                attempts);
        log.info("AI provider execution success: provider={}, model={}, fallback={}, latencyMs={}, tokens={}",
                SafeLog.metadata(execution.providerName()),
                SafeLog.metadata(execution.modelName()),
                execution.fallbackUsed(),
                execution.latencyMillis(),
                execution.tokenUsage().totalTokens());
        return new AiExecutionOutcome.Success(execution);
    }

    private String preferredProvider(UserPreferences preferences) {
        String preferred = preferences == null ? null : preferences.provider();
        return normalizeProvider(preferred == null || preferred.isBlank() ? defaultProvider : preferred);
    }

    private String preferredModel(AiProviderAdapter adapter, UserPreferences preferences) {
        if (preferences == null) {
            return adapter.defaultModel();
        }
        String preferred = preferences.modelFor(normalizeProvider(adapter.providerName()));
        return effectiveModel(adapter, preferred);
    }

    private String effectiveModel(AiProviderAdapter adapter, String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            return adapter.defaultModel();
        }
        String normalized = requestedModel.trim();
        return adapter.availableModels().contains(normalized)
                ? normalized
                : adapter.defaultModel();
    }

    private boolean isFallbackEligible(AiProviderException failure) {
        return failure.getOutcome() != AiProviderException.Outcome.SAFETY_BLOCKED;
    }

    private String alternateProvider(String provider) {
        return "openai".equals(provider) ? "gemini" : "openai";
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "openai";
        }
        return "gemini".equals(provider.trim().toLowerCase(Locale.ROOT)) ? "gemini" : "openai";
    }

    private AiProviderException adapterFailure(
            AiProviderAdapter adapter,
            String model,
            AiProviderException.Outcome outcome,
            String reason) {
        return failure(outcome, adapter.providerName(), model, reason);
    }

    private AiProviderException failure(
            AiProviderException.Outcome outcome,
            String provider,
            String model,
            String reason) {
        return new AiProviderException(
                outcome,
                provider,
                model,
                reason,
                UUID.randomUUID().toString(),
                -1,
                null);
    }

    private void logFailure(
            String stage,
            AiProviderOperation operation,
            AiProviderException failure) {
        log.warn("AI provider execution failure: stage={}, operation={}, provider={}, model={}, "
                        + "outcome={}, reason={}, correlation={}",
                stage,
                operation,
                failure.getProvider(),
                failure.getModel(),
                failure.getOutcome(),
                failure.getReason(),
                failure.getCorrelationId());
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private record AttemptResult(AiProviderResponse response, AiProviderException failure) {
    }
}
