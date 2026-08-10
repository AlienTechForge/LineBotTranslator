package com.linetranslate.bot.service.ai;

public record AiTokenUsage(long inputTokens, long outputTokens, long totalTokens) {

    public static final AiTokenUsage UNKNOWN = new AiTokenUsage(-1, -1, -1);

    public AiTokenUsage {
        if (inputTokens < -1 || outputTokens < -1 || totalTokens < -1) {
            throw new IllegalArgumentException("Token counts must be non-negative or -1 when unavailable");
        }
    }
}
