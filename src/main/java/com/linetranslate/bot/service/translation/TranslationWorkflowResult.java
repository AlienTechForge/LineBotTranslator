package com.linetranslate.bot.service.translation;

import com.linetranslate.bot.service.ai.AiExecutionResult;

public record TranslationWorkflowResult(
        String sourceText,
        String sourceLanguage,
        String targetLanguage,
        AiExecutionResult execution,
        long processingTimeMillis,
        TranslationRequestKind kind) {

    public String translatedText() {
        return execution.text();
    }

    public String providerName() {
        return execution.providerName();
    }

    public String modelName() {
        return execution.modelName();
    }
}
