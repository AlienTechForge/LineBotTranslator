package com.linetranslate.bot.service.ai;

import java.util.Optional;
import java.util.Set;

/** Model discovery Seam. Consumers do not know the provider wire format. */
public interface AiModelCatalog {

    AiModelPage list(String query, int limit);

    Optional<AiModelDescriptor> find(String modelId);

    default boolean contains(String modelId) {
        return find(modelId).isPresent();
    }

    default boolean supports(String modelId, AiProviderOperation operation) {
        return find(modelId).map(model -> model.supports(operation)).orElse(false);
    }

    default Set<String> modelIds() {
        return list("", Integer.MAX_VALUE).models().stream()
                .map(AiModelDescriptor::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
