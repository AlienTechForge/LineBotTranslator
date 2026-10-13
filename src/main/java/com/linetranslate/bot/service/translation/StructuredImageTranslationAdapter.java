package com.linetranslate.bot.service.translation;

import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import com.linetranslate.bot.service.ai.AiExecutionFailure;
import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderException;
import com.linetranslate.bot.service.preference.UserPreferences;

/** Strict structured translation with one bounded repair attempt. */
@Component
public class StructuredImageTranslationAdapter {
    private final CachedTranslationAdapter adapter;
    private final StructuredImageTranslationCodec codec;
    private final TargetLocalePolicy localePolicy;

    @Autowired
    public StructuredImageTranslationAdapter(
            CachedTranslationAdapter adapter,
            StructuredImageTranslationCodec codec,
            TargetLocalePolicy localePolicy) {
        this.adapter = adapter;
        this.codec = codec;
        this.localePolicy = localePolicy;
    }

    public StructuredImageTranslationAdapter(
            CachedTranslationAdapter adapter,
            StructuredImageTranslationCodec codec) {
        this(adapter, codec, new TargetLocalePolicy());
    }

    public Result translate(
            UserPreferences preferences,
            List<ImageRegionTranslationInput> regions,
            String targetLanguage,
            TranslationStylePreset style) {
        Attempt first = execute(preferences, regions, targetLanguage, style, false);
        if (first.complete()) return first.result(regions, null);
        Attempt repaired = execute(preferences, regions, targetLanguage, style, true);
        AiExecutionFailure defect = unexpectedFailure(first, repaired);
        if (repaired.complete()) return repaired.result(regions, defect);

        // Both attempts are imperfect. Keep the regions the provider answered correctly instead of
        // discarding the whole overlay; unanswered regions keep their source pixels.
        Attempt best = repaired.translations().size() > first.translations().size() ? repaired : first;
        if (best.translations().isEmpty()) {
            boolean providerFailed = first.raw() == null && repaired.raw() == null;
            throw new StructuredTranslationException(
                    providerFailed
                            ? StructuredTranslationException.PROVIDER_FAILED
                            : StructuredTranslationException.NO_USABLE_REGION,
                    "Structured response and repair are invalid",
                    defect);
        }
        return best.result(regions, defect);
    }

    /** A provider bug an attempt hit, kept because the caller may still succeed without it. */
    private static AiExecutionFailure unexpectedFailure(Attempt... attempts) {
        return Stream.of(attempts)
                .map(Attempt::failure)
                .filter(failure -> failure != null
                        && failure.outcome() == AiProviderException.Outcome.UNEXPECTED_ERROR)
                .findFirst()
                .orElse(null);
    }

    private Attempt execute(
            UserPreferences preferences,
            List<ImageRegionTranslationInput> regions,
            String targetLanguage,
            TranslationStylePreset style,
            boolean repair) {
        String wire = codec.encode(regions, targetLanguage, repair);
        AiExecutionOutcome outcome = adapter.translateValidated(
                preferences, wire, targetLanguage, style,
                StructuredImageTranslationCodec.SCHEMA_VERSION,
                result -> isValid(result.text(), regions, targetLanguage));
        if (outcome instanceof AiExecutionOutcome.Failure failed) {
            return Attempt.failed(failed.failure());
        }
        AiExecutionResult raw = ((AiExecutionOutcome.Success) outcome).result();
        List<ImageRegionTranslation> accepted = codec.decodeAvailable(raw.text(), regions).stream()
                .filter(value -> localePolicy.accepts(value.translatedText(), targetLanguage))
                .toList();
        long expected = regions.stream().filter(ImageRegionTranslationInput::translatable).count();
        return new Attempt(raw, accepted, accepted.size() == expected, null);
    }

    private boolean isValid(String response, List<ImageRegionTranslationInput> regions, String targetLanguage) {
        try {
            return codec.decode(response, regions).stream()
                    .allMatch(value -> localePolicy.accepts(value.translatedText(), targetLanguage));
        } catch (StructuredTranslationException invalid) {
            return false;
        }
    }

    /** One provider round trip and the regions it answered acceptably. */
    private record Attempt(
            AiExecutionResult raw,
            List<ImageRegionTranslation> translations,
            boolean complete,
            AiExecutionFailure failure) {

        static Attempt failed(AiExecutionFailure failure) {
            return new Attempt(null, List.of(), false, failure);
        }

        Result result(List<ImageRegionTranslationInput> regions, AiExecutionFailure unexpectedFailure) {
            String readable = regions.stream()
                    .sorted(java.util.Comparator.comparingInt(ImageRegionTranslationInput::readingOrder)
                            .thenComparing(ImageRegionTranslationInput::regionId))
                    .map(input -> translations.stream()
                            .filter(value -> value.regionId().equals(input.regionId()))
                            .findFirst()
                            .map(ImageRegionTranslation::translatedText)
                            .orElseGet(input::sourceText))
                    .reduce((left, right) -> left + "\n" + right)
                    .orElseThrow();
            AiExecutionResult normalized = new AiExecutionResult(
                    readable, raw.providerName(), raw.modelName(), raw.tokenUsage(), raw.latencyMillis(),
                    raw.fallbackUsed(), raw.attempts());
            return new Result(normalized, translations, unexpectedFailure);
        }
    }

    /**
     * @param unexpectedFailure a provider bug ({@code UNEXPECTED_ERROR}) that an attempt hit
     *        before the answer that was used, or {@code null}
     */
    public record Result(
            AiExecutionResult execution,
            List<ImageRegionTranslation> translations,
            AiExecutionFailure unexpectedFailure) {
        public Result {
            translations = List.copyOf(translations);
        }

        public Result(AiExecutionResult execution, List<ImageRegionTranslation> translations) {
            this(execution, translations, null);
        }
    }
}
