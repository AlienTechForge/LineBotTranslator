package com.linetranslate.bot.service.ai;

import java.util.Locale;

/**
 * Provider and model selected before an execution attempt begins.
 */
public record AiProviderRoute(String providerName, String modelName) {

    public AiProviderRoute {
        if (providerName == null || providerName.isBlank()
                || modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("AI provider route requires provider and model");
        }
        providerName = providerName.trim().toLowerCase(Locale.ROOT);
        modelName = modelName.trim();
    }

    public boolean matches(AiExecutionResult result) {
        return result != null
                && providerName.equals(result.providerName().trim().toLowerCase(Locale.ROOT))
                && modelName.equals(result.modelName().trim());
    }

    public boolean providerMatches(AiExecutionResult result) {
        return result != null
                && providerName.equals(result.providerName().trim().toLowerCase(Locale.ROOT));
    }
}
