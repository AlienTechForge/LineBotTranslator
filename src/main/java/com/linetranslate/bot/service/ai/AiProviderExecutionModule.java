package com.linetranslate.bot.service.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.preference.UserPreferences;
import com.linetranslate.bot.service.settings.RuntimeSettings;
import com.linetranslate.bot.service.settings.RuntimeSettingsSource;
import com.linetranslate.bot.service.usage.AiUsageEventSink;

import lombok.extern.slf4j.Slf4j;

/**
 * Deep Module for provider selection, model validation, bounded fallback and
 * observable structured outcomes.
 */
@Service
@Slf4j
public class AiProviderExecutionModule {

    private final Map<String, AiProviderAdapter> adapters;
    private final RuntimeSettingsSource runtimeSettingsSource;
    private final AiUsageEventSink usageEventSink;

    @Autowired
    public AiProviderExecutionModule(
            List<AiProviderAdapter> adapters,
            RuntimeSettingsSource runtimeSettingsSource,
            AiUsageEventSink usageEventSink) {
        Map<String, AiProviderAdapter> indexedAdapters = new LinkedHashMap<>();
        for (AiProviderAdapter adapter : adapters) {
            indexedAdapters.put(normalizeProvider(adapter.providerName()), adapter);
        }
        this.adapters = Map.copyOf(indexedAdapters);
        this.runtimeSettingsSource = runtimeSettingsSource;
        this.usageEventSink = usageEventSink;

        if (indexedAdapters.isEmpty()) {
            log.warn("No AI provider Adapter is configured");
        } else {
            log.info("AI provider execution Module initialized: default={}, adapters={}",
                    defaultProvider(), indexedAdapters.keySet());
        }
    }

    /** Compatibility constructor for focused unit tests. */
    public AiProviderExecutionModule(
            List<AiProviderAdapter> adapters,
            String defaultProvider) {
        this(adapters, fixedSettings(adapters, defaultProvider), noOpUsageSink());
    }

    public AiProviderExecutionModule(
            List<AiProviderAdapter> adapters,
            RuntimeSettingsSource runtimeSettingsSource) {
        this(adapters, runtimeSettingsSource, noOpUsageSink());
    }

    public AiProviderExecutionModule(
            List<AiProviderAdapter> adapters,
            String defaultProvider,
            AiUsageEventSink usageEventSink) {
        this(adapters, fixedSettings(adapters, defaultProvider), usageEventSink);
    }

    public AiExecutionOutcome translateTextOutcome(
            UserPreferences preferences,
            String text,
            String targetLanguage) {
        AiExecutionOutcome outcome = executeWithFallback(
                AiProviderOperation.TRANSLATE_TEXT,
                preferredProvider(preferences),
                adapter -> preferredModel(adapter, preferences),
                model -> AiProviderRequest.translate(model, text, targetLanguage));
        recordUsage(AiProviderOperation.TRANSLATE_TEXT, outcome);
        return outcome;
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
        AiExecutionOutcome outcome = executeWithFallback(
                AiProviderOperation.PROCESS_IMAGE,
                preferredProvider(preferences),
                adapter -> preferredModel(adapter, preferences),
                model -> AiProviderRequest.image(model, prompt, imageData));
        recordUsage(AiProviderOperation.PROCESS_IMAGE, outcome);
        return outcome;
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
        RuntimeSettings settings = runtimeSettingsSource.current();
        String normalizedProvider = normalizeProvider(provider == null
                ? effectiveDefaultProvider(settings)
                : provider);
        String effectiveRequestedModel = requestedModel == null || requestedModel.isBlank()
                ? settings.modelFor(normalizedProvider)
                : requestedModel;
        AiExecutionOutcome outcome = executeWithFallback(
                AiProviderOperation.GENERATE_TEXT,
                normalizedProvider,
                adapter -> normalizedProvider.equals(normalizeProvider(adapter.providerName()))
                        ? effectiveRequestedModel
                        : settings.modelFor(adapter.providerName()),
                model -> AiProviderRequest.generate(model, prompt));
        recordUsage(AiProviderOperation.GENERATE_TEXT, outcome);
        return outcome;
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
        return normalizeProvider(preferred == null || preferred.isBlank()
                ? defaultProvider()
                : preferred);
    }

    private String preferredModel(AiProviderAdapter adapter, UserPreferences preferences) {
        if (preferences == null) {
            return runtimeSettingsSource.current().modelFor(adapter.providerName());
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

    private String defaultProvider() {
        return effectiveDefaultProvider(runtimeSettingsSource.current());
    }

    private String effectiveDefaultProvider(RuntimeSettings settings) {
        String configured = normalizeProvider(settings.defaultAiProvider());
        return adapters.containsKey(configured)
                ? configured
                : adapters.keySet().stream().findFirst().orElse(configured);
    }

    private static RuntimeSettingsSource fixedSettings(
            List<AiProviderAdapter> adapters,
            String defaultProvider) {
        return () -> new RuntimeSettings(
                "en",
                "zh-TW",
                normalizeProvider(defaultProvider),
                adapterDefault(adapters, "openai"),
                adapterDefault(adapters, "gemini"),
                true,
                1,
                0,
                null,
                null,
                RuntimeSettings.Source.DEPLOYMENT_DEFAULTS);
    }

    private void recordUsage(AiProviderOperation operation, AiExecutionOutcome outcome) {
        try {
            usageEventSink.record(operation, outcome);
        } catch (RuntimeException failure) {
            log.warn("AI usage accounting degraded: operation={}, failure={}",
                    operation, SafeLog.failure(failure));
        }
    }

    private static AiUsageEventSink noOpUsageSink() {
        return (operation, outcome) -> {
        };
    }

    private static String adapterDefault(List<AiProviderAdapter> adapters, String provider) {
        return adapters.stream()
                .filter(adapter -> provider.equals(normalizeProvider(adapter.providerName())))
                .map(AiProviderAdapter::defaultModel)
                .filter(model -> model != null && !model.isBlank())
                .findFirst()
                .orElse("unavailable");
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
