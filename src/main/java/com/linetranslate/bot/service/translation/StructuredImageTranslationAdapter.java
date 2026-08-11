package com.linetranslate.bot.service.translation;

import java.util.List;

import org.springframework.stereotype.Component;

import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.preference.UserPreferences;

/** Strict structured translation with one bounded repair attempt. */
@Component
public class StructuredImageTranslationAdapter {
    private final CachedTranslationAdapter adapter;
    private final StructuredImageTranslationCodec codec;

    public StructuredImageTranslationAdapter(
            CachedTranslationAdapter adapter,
            StructuredImageTranslationCodec codec) {
        this.adapter = adapter;
        this.codec = codec;
    }

    public Result translate(
            UserPreferences preferences,
            List<ImageRegionTranslationInput> regions,
            String targetLanguage,
            TranslationStylePreset style) {
        try {
            return execute(preferences, regions, targetLanguage, style, false);
        } catch (StructuredTranslationException failure) {
            // One bounded repair attempt; provider content is intentionally not propagated.
        }
        try {
            return execute(preferences, regions, targetLanguage, style, true);
        } catch (StructuredTranslationException failure) {
            throw new StructuredTranslationException("Structured response and repair are invalid");
        }
    }

    private Result execute(
            UserPreferences preferences,
            List<ImageRegionTranslationInput> regions,
            String targetLanguage,
            TranslationStylePreset style,
            boolean repair) {
        String wire = codec.encode(regions, targetLanguage, repair);
        AiExecutionOutcome outcome = adapter.translateValidated(
                preferences, wire, targetLanguage, style,
                StructuredImageTranslationCodec.SCHEMA_VERSION,
                result -> isValid(result.text(), regions));
        if (outcome instanceof AiExecutionOutcome.Failure failure) {
            throw new StructuredTranslationException("Structured provider execution failed: " + failure.failure().outcome());
        }
        AiExecutionResult raw = ((AiExecutionOutcome.Success) outcome).result();
        List<ImageRegionTranslation> translations = codec.decode(raw.text(), regions);
        String readable = regions.stream()
                .sorted(java.util.Comparator.comparingInt(ImageRegionTranslationInput::readingOrder)
                        .thenComparing(ImageRegionTranslationInput::regionId))
                .map(input -> input.translatable()
                        ? translations.stream().filter(value -> value.regionId().equals(input.regionId()))
                                .findFirst().orElseThrow().translatedText()
                        : input.sourceText())
                .reduce((left, right) -> left + "\n" + right).orElseThrow();
        AiExecutionResult normalized = new AiExecutionResult(
                readable, raw.providerName(), raw.modelName(), raw.tokenUsage(), raw.latencyMillis(),
                raw.fallbackUsed(), raw.attempts());
        return new Result(normalized, translations);
    }

    private boolean isValid(String response, List<ImageRegionTranslationInput> regions) {
        try {
            codec.decode(response, regions);
            return true;
        } catch (StructuredTranslationException invalid) {
            return false;
        }
    }

    public record Result(AiExecutionResult execution, List<ImageRegionTranslation> translations) {
        public Result {
            translations = List.copyOf(translations);
        }
    }
}
