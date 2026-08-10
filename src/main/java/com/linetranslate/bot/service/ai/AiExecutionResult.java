package com.linetranslate.bot.service.ai;

/**
 * A successful AI execution together with the provider that actually produced it.
 */
public record AiExecutionResult(String text, String providerName, String modelName) {

    public AiExecutionResult {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("AI execution result must contain text");
        }
    }
}
