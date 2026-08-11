package com.linetranslate.bot.service.translation;

import com.linetranslate.bot.service.ai.AiExecutionResult;
import java.util.List;

public record TranslationWorkflowResult(
        String sourceText,
        String sourceLanguage,
        String targetLanguage,
        AiExecutionResult execution,
        long processingTimeMillis,
        TranslationRequestKind kind,
        String recordId,
        String stylePresetId,
        String stylePromptVersion,
        List<ImageRegionTranslation> imageRegionTranslations) {

    public TranslationWorkflowResult(
            String sourceText,
            String sourceLanguage,
            String targetLanguage,
            AiExecutionResult execution,
            long processingTimeMillis,
            TranslationRequestKind kind,
            String recordId) {
        this(sourceText, sourceLanguage, targetLanguage, execution, processingTimeMillis,
                kind, recordId, TranslationStylePreset.defaultPreset().id(),
                TranslationStylePreset.defaultPreset().promptVersion(), List.of());
    }

    public TranslationWorkflowResult(
            String sourceText,
            String sourceLanguage,
            String targetLanguage,
            AiExecutionResult execution,
            long processingTimeMillis,
            TranslationRequestKind kind) {
        this(sourceText, sourceLanguage, targetLanguage, execution,
                processingTimeMillis, kind, null);
    }

    public String translatedText() {
        return execution.text();
    }

    public String providerName() {
        return execution.providerName();
    }

    public String modelName() {
        return execution.modelName();
    }

    public TranslationWorkflowResult {
        imageRegionTranslations = imageRegionTranslations == null ? List.of() : List.copyOf(imageRegionTranslations);
    }
}
