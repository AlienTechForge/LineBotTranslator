package com.linetranslate.bot.service.ai;

public record AiProviderAttempt(
        String provider,
        String model,
        Status status,
        AiProviderException.Outcome outcome,
        String reason,
        String correlationId,
        long latencyMillis) {

    public enum Status {
        SUCCESS,
        FAILURE
    }

    public AiProviderAttempt {
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            throw new IllegalArgumentException("Provider attempt requires provider and model");
        }
        if (status == null || latencyMillis < 0) {
            throw new IllegalArgumentException("Provider attempt requires status and non-negative latency");
        }
    }

    public static AiProviderAttempt success(String provider, String model, long latencyMillis) {
        return new AiProviderAttempt(provider, model, Status.SUCCESS, null, null, null, latencyMillis);
    }

    public static AiProviderAttempt failure(AiProviderException failure, long latencyMillis) {
        return new AiProviderAttempt(
                failure.getProvider(),
                failure.getModel(),
                Status.FAILURE,
                failure.getOutcome(),
                failure.getReason(),
                failure.getCorrelationId(),
                latencyMillis);
    }
}
