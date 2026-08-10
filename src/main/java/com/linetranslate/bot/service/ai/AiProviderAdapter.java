package com.linetranslate.bot.service.ai;

import java.util.Set;

/**
 * Provider seam. Implementations translate a provider-neutral request into one
 * remote provider request and either return provider facts or throw a typed failure.
 */
public interface AiProviderAdapter {

    String providerName();

    String defaultModel();

    Set<String> availableModels();

    Set<AiProviderOperation> capabilities();

    AiProviderResponse execute(AiProviderRequest request);

    default boolean supports(AiProviderRequest request) {
        return capabilities().contains(request.operation())
                && availableModels().contains(request.model());
    }
}
