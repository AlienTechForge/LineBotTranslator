package com.linetranslate.bot.service.ai;

public record AiProviderResponse(String text, String model, AiTokenUsage tokenUsage) {

    public AiProviderResponse {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("AI provider response must contain text");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("AI provider response must contain the actual model");
        }
        tokenUsage = tokenUsage == null ? AiTokenUsage.UNKNOWN : tokenUsage;
    }
}
