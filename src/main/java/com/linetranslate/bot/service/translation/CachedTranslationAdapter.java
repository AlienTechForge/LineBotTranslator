package com.linetranslate.bot.service.translation;

import org.springframework.stereotype.Service;

import com.linetranslate.bot.service.ai.AiExecutionFailure;
import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderException;
import com.linetranslate.bot.service.ai.AiProviderExecutionModule;
import com.linetranslate.bot.service.ai.AiProviderRoute;
import com.linetranslate.bot.service.preference.UserPreferences;
import java.util.function.Predicate;

/**
 * Cache Adapter around provider execution. Failures are never cached.
 */
@Service
public class CachedTranslationAdapter {

    private final AiProviderExecutionModule providerExecutionModule;
    private final TranslationCacheStore cacheStore;
    private final TranslationCacheProperties properties;

    public CachedTranslationAdapter(
            AiProviderExecutionModule providerExecutionModule,
            TranslationCacheStore cacheStore,
            TranslationCacheProperties properties) {
        this.providerExecutionModule = providerExecutionModule;
        this.cacheStore = cacheStore;
        this.properties = properties;
    }

    public AiExecutionOutcome translate(
            UserPreferences preferences,
            String text,
            String targetLanguage) {
        TranslationStylePreset preset = preferences == null
                ? TranslationStylePreset.defaultPreset()
                : preferences.translationStyle();
        return translate(preferences, text, targetLanguage, preset);
    }

    public AiExecutionOutcome translate(
            UserPreferences preferences,
            String text,
            String targetLanguage,
            TranslationStylePreset preset) {
        TranslationStylePreset effective = preset == null
                ? TranslationStylePreset.defaultPreset()
                : preset;
        return translate(preferences, text, targetLanguage,
                properties.currentVariant(effective), effective, result -> true);
    }

    AiExecutionOutcome translateValidated(
            UserPreferences preferences,
            String text,
            String targetLanguage,
            TranslationStylePreset preset,
            String contractVersion,
            Predicate<AiExecutionResult> validator) {
        TranslationStylePreset effective = preset == null ? TranslationStylePreset.defaultPreset() : preset;
        TranslationCacheVariant base = properties.currentVariant(effective);
        TranslationCacheVariant structured = new TranslationCacheVariant(
                base.style(), base.glossaryVersion(), base.promptVersion() + "+" + contractVersion);
        return translate(preferences, text, targetLanguage, structured, effective, validator);
    }

    /** Compatibility seam retained for focused cache identity tests. */
    AiExecutionOutcome translate(
            UserPreferences preferences,
            String text,
            String targetLanguage,
            TranslationCacheVariant variant) {
        TranslationStylePreset preset = TranslationStylePreset.find(variant.style())
                .orElse(TranslationStylePreset.defaultPreset());
        return translate(preferences, text, targetLanguage, variant, preset, result -> true);
    }

    private AiExecutionOutcome translate(
            UserPreferences preferences,
            String text,
            String targetLanguage,
            TranslationCacheVariant variant,
            TranslationStylePreset preset,
            Predicate<AiExecutionResult> validator) {
        AiProviderRoute route = providerExecutionModule.planText(preferences);
        TranslationCacheKey key = TranslationCacheKeyFactory.create(
                text,
                targetLanguage,
                route,
                variant);

        return cacheStore.find(key)
                .filter(value -> validator.test(value.result()))
                .<AiExecutionOutcome>map(value -> value)
                .orElseGet(() -> executeAndMaybeCache(
                        preferences,
                        text,
                        targetLanguage,
                        variant,
                        preset,
                        route,
                        key,
                        validator));
    }

    private AiExecutionOutcome executeAndMaybeCache(
            UserPreferences preferences,
            String text,
            String targetLanguage,
            TranslationCacheVariant variant,
            TranslationStylePreset preset,
            AiProviderRoute route,
            TranslationCacheKey plannedKey,
            Predicate<AiExecutionResult> validator) {
        AiExecutionOutcome outcome = providerExecutionModule.translateTextOutcome(
                preferences,
                text,
                targetLanguage,
                preset);
        if (outcome instanceof AiExecutionOutcome.Failure failure) {
            cacheStore.recordSkipped(skipReason(failure.failure()));
            return outcome;
        }

        AiExecutionOutcome.Success success = (AiExecutionOutcome.Success) outcome;
        AiExecutionResult result = success.result();
        if (!validator.test(result)) {
            cacheStore.recordSkipped(TranslationCacheSkipReason.INVALID_RESPONSE);
        } else if (result.fallbackUsed()) {
            cacheStore.recordSkipped(TranslationCacheSkipReason.FALLBACK);
        } else if (!route.providerMatches(result)) {
            cacheStore.recordSkipped(TranslationCacheSkipReason.ROUTE_MISMATCH);
        } else {
            TranslationCacheKey actualKey = TranslationCacheKeyFactory.create(
                    text,
                    targetLanguage,
                    new AiProviderRoute(result.providerName(), result.modelName()),
                    variant);
            cacheStore.put(plannedKey, actualKey, success);
        }
        return outcome;
    }

    private TranslationCacheSkipReason skipReason(AiExecutionFailure failure) {
        return failure.outcome() == AiProviderException.Outcome.SAFETY_BLOCKED
                ? TranslationCacheSkipReason.SAFETY_BLOCKED
                : TranslationCacheSkipReason.FAILURE;
    }
}
