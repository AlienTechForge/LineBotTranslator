package com.linetranslate.bot.service.ai;

import java.math.BigDecimal;
import java.util.Set;

/** Immutable model capability and pricing facts returned by the provider catalog. */
public record AiModelDescriptor(
        String id,
        String displayName,
        Set<String> inputModalities,
        Set<String> outputModalities,
        BigDecimal promptPricePerToken,
        BigDecimal completionPricePerToken) {

    public AiModelDescriptor {
        if (id == null || !id.trim().matches("^[A-Za-z0-9~][A-Za-z0-9._:~/\\-]{0,199}$")) {
            throw new IllegalArgumentException("AI model ID is invalid");
        }
        id = id.trim();
        displayName = displayName == null || displayName.isBlank() ? id : displayName.trim();
        inputModalities = inputModalities == null ? Set.of() : Set.copyOf(inputModalities);
        outputModalities = outputModalities == null ? Set.of() : Set.copyOf(outputModalities);
    }

    public boolean supports(AiProviderOperation operation) {
        if (!inputModalities.contains("text") || !outputModalities.contains("text")) {
            return false;
        }
        return operation != AiProviderOperation.PROCESS_IMAGE || inputModalities.contains("image");
    }
}
