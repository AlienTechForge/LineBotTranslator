package com.linetranslate.bot.service.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.preference.UserPreferences;
import com.linetranslate.bot.service.settings.RuntimeSettingsSource;
import com.linetranslate.bot.service.translation.TranslationStylePreset;
import com.linetranslate.bot.service.usage.AiUsageEventSink;

import lombok.extern.slf4j.Slf4j;

/**
 * Deep Module for the single OpenRouter execution path. It validates the chosen
 * model, normalizes provider failures, records attempts and isolates accounting.
 */
@Service
@Slf4j
public class AiProviderExecutionModule {

    private final AiProviderAdapter adapter;
    private final RuntimeSettingsSource runtimeSettingsSource;
    private final AiUsageEventSink usageEventSink;

    @Autowired
    public AiProviderExecutionModule(
            List<AiProviderAdapter> adapters,
            RuntimeSettingsSource runtimeSettingsSource,
            AiUsageEventSink usageEventSink) {
        this.adapter = adapters == null || adapters.isEmpty() ? null : adapters.get(0);
        if (adapters != null && adapters.size() > 1) {
            throw new IllegalStateException("OpenRouter-only execution requires exactly one AI Adapter");
        }
        this.runtimeSettingsSource = runtimeSettingsSource;
        this.usageEventSink = usageEventSink;
        if (adapter == null) {
            log.warn("No AI provider Adapter is configured");
        } else {
            log.info("AI provider execution Module initialized: adapter={}", adapter.providerName());
        }
    }

    /** Compatibility constructor for focused unit tests. */
    public AiProviderExecutionModule(
            List<AiProviderAdapter> adapters,
            RuntimeSettingsSource runtimeSettingsSource) {
        this(adapters, runtimeSettingsSource, noOpUsageSink());
    }

    public AiExecutionOutcome translateTextOutcome(
            UserPreferences preferences,
            String text,
            String targetLanguage) {
        TranslationStylePreset style = preferences == null
                ? TranslationStylePreset.defaultPreset()
                : preferences.translationStyle();
        return translateTextOutcome(preferences, text, targetLanguage, style);
    }

    public AiExecutionOutcome translateTextOutcome(
            UserPreferences preferences,
            String text,
            String targetLanguage,
            TranslationStylePreset style) {
        TranslationStylePreset effectiveStyle = style == null
                ? TranslationStylePreset.defaultPreset()
                : style;
        AiExecutionOutcome outcome = execute(
                AiProviderOperation.TRANSLATE_TEXT,
                preferredModel(preferences),
                model -> AiProviderRequest.translate(
                        model,
                        text,
                        targetLanguage,
                        effectiveStyle.id(),
                        effectiveStyle.promptVersion(),
                        effectiveStyle.promptRule()));
        recordUsage(AiProviderOperation.TRANSLATE_TEXT, outcome);
        return outcome;
    }

    public AiProviderRoute planText(UserPreferences preferences) {
        if (adapter == null) {
            return new AiProviderRoute("none", "none");
        }
        return new AiProviderRoute(adapter.providerName(), effectiveModel(preferredModel(preferences)));
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
        AiExecutionOutcome outcome = execute(
                AiProviderOperation.PROCESS_IMAGE,
                preferredModel(preferences),
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

    public AiExecutionOutcome generateTextOutcome(String requestedModel, String prompt) {
        AiExecutionOutcome outcome = execute(
                AiProviderOperation.GENERATE_TEXT,
                requestedModel,
                model -> AiProviderRequest.generate(model, prompt));
        recordUsage(AiProviderOperation.GENERATE_TEXT, outcome);
        return outcome;
    }

    public AiExecutionResult generateText(String requestedModel, String prompt) {
        return generateTextOutcome(requestedModel, prompt).resultOrThrow();
    }

    private AiExecutionOutcome execute(
            AiProviderOperation operation,
            String requestedModel,
            java.util.function.Function<String, AiProviderRequest> requestFactory) {
        List<AiProviderAttempt> attempts = new ArrayList<>();
        if (adapter == null) {
            AiProviderException unavailable = failure(
                    AiProviderException.Outcome.CONFIGURATION_ERROR,
                    "none",
                    "none",
                    "NO_CONFIGURED_PROVIDER");
            return new AiExecutionOutcome.Failure(AiExecutionFailure.from(unavailable, attempts));
        }

        String model = effectiveModel(requestedModel);
        AiProviderRequest request = requestFactory.apply(model);
        if (!adapter.supports(request)) {
            AiProviderException unsupported = failure(
                    AiProviderException.Outcome.CONFIGURATION_ERROR,
                    adapter.providerName(),
                    model,
                    adapter.availableModels().contains(model)
                            ? "UNSUPPORTED_OPERATION"
                            : "UNSUPPORTED_MODEL");
            attempts.add(AiProviderAttempt.failure(unsupported, 0));
            return new AiExecutionOutcome.Failure(AiExecutionFailure.from(unsupported, attempts));
        }

        long startedAt = System.nanoTime();
        try {
            AiProviderResponse response = adapter.execute(request);
            long latencyMillis = elapsedMillis(startedAt);
            attempts.add(AiProviderAttempt.success(adapter.providerName(), response.model(), latencyMillis));
            AiExecutionResult result = new AiExecutionResult(
                    response.text(),
                    adapter.providerName(),
                    response.model(),
                    response.tokenUsage(),
                    latencyMillis,
                    false,
                    attempts);
            log.info("AI provider execution success: provider={}, model={}, latencyMs={}, tokens={}",
                    SafeLog.metadata(result.providerName()),
                    SafeLog.metadata(result.modelName()),
                    latencyMillis,
                    result.tokenUsage().totalTokens());
            return new AiExecutionOutcome.Success(result);
        } catch (AiProviderException providerFailure) {
            long latencyMillis = elapsedMillis(startedAt);
            attempts.add(AiProviderAttempt.failure(providerFailure, latencyMillis));
            logFailure(operation, providerFailure);
            return new AiExecutionOutcome.Failure(AiExecutionFailure.from(providerFailure, attempts));
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
            attempts.add(AiProviderAttempt.failure(normalized, latencyMillis));
            logFailure(operation, normalized);
            return new AiExecutionOutcome.Failure(AiExecutionFailure.from(normalized, attempts));
        }
    }

    private String preferredModel(UserPreferences preferences) {
        return preferences == null ? null : preferences.model();
    }

    private String effectiveModel(String requestedModel) {
        String model = requestedModel;
        if (model == null || model.isBlank()) {
            model = runtimeSettingsSource.current().openRouterDefaultModel();
        }
        if (model == null || model.isBlank() || !adapter.availableModels().contains(model.trim())) {
            return adapter.defaultModel();
        }
        return model.trim();
    }

    private void recordUsage(AiProviderOperation operation, AiExecutionOutcome outcome) {
        try {
            usageEventSink.record(operation, outcome);
        } catch (RuntimeException failure) {
            log.warn("AI usage accounting degraded: operation={}, failure={}",
                    operation, SafeLog.failure(failure));
        }
    }

    private void logFailure(AiProviderOperation operation, AiProviderException failure) {
        log.warn("AI provider execution failure: operation={}, provider={}, model={}, outcome={}, reason={}, correlation={}",
                operation,
                failure.getProvider(),
                failure.getModel(),
                failure.getOutcome(),
                failure.getReason(),
                failure.getCorrelationId());
    }

    private AiProviderException failure(
            AiProviderException.Outcome outcome,
            String provider,
            String model,
            String reason) {
        return new AiProviderException(
                outcome, provider, model, reason, UUID.randomUUID().toString(), -1, null);
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private static AiUsageEventSink noOpUsageSink() {
        return (operation, outcome) -> {
        };
    }
}
