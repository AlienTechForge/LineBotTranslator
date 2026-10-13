package com.linetranslate.bot.service.ai;

import java.util.List;

public record AiExecutionFailure(
        AiProviderException.Outcome outcome,
        String provider,
        String model,
        String reason,
        String correlationId,
        int httpStatus,
        List<AiProviderAttempt> attempts,
        AiTokenUsage tokenUsage) {

    public AiExecutionFailure {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        tokenUsage = tokenUsage == null ? AiTokenUsage.UNKNOWN : tokenUsage;
    }

    public AiExecutionFailure(
            AiProviderException.Outcome outcome,
            String provider,
            String model,
            String reason,
            String correlationId,
            int httpStatus,
            List<AiProviderAttempt> attempts) {
        this(outcome, provider, model, reason, correlationId, httpStatus, attempts, AiTokenUsage.UNKNOWN);
    }

    public static AiExecutionFailure from(
            AiProviderException failure,
            List<AiProviderAttempt> attempts) {
        return new AiExecutionFailure(
                failure.getOutcome(),
                failure.getProvider(),
                failure.getModel(),
                failure.getReason(),
                failure.getCorrelationId(),
                failure.getHttpStatus(),
                attempts,
                failure.getTokenUsage());
    }

    public AiProviderException toException() {
        return new AiProviderException(
                outcome,
                provider,
                model,
                reason,
                correlationId,
                httpStatus,
                null,
                tokenUsage);
    }
}
