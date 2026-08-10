package com.linetranslate.bot.service.ai;

import java.util.List;

/**
 * A successful execution with facts reported by the provider execution Module.
 */
public record AiExecutionResult(
        String text,
        String providerName,
        String modelName,
        AiTokenUsage tokenUsage,
        long latencyMillis,
        boolean fallbackUsed,
        List<AiProviderAttempt> attempts) {

    public AiExecutionResult {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("AI execution result must contain text");
        }
        if (providerName == null || providerName.isBlank()
                || modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("AI execution result requires provider and model");
        }
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("AI execution latency must be non-negative");
        }
        tokenUsage = tokenUsage == null ? AiTokenUsage.UNKNOWN : tokenUsage;
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    public AiExecutionResult(String text, String providerName, String modelName) {
        this(text, providerName, modelName, AiTokenUsage.UNKNOWN, 0, false, List.of());
    }
}
