package com.linetranslate.bot.service.ai;

import java.util.List;

/**
 * A normalized provider failure. {@code cause} is the exception behind it, kept only so that a
 * bug the execution module caught ({@link AiProviderException.Outcome#UNEXPECTED_ERROR}) can still
 * be reported with its real stack; it is never part of {@link #toString()} because exception
 * messages can carry user content.
 */
public record AiExecutionFailure(
        AiProviderException.Outcome outcome,
        String provider,
        String model,
        String reason,
        String correlationId,
        int httpStatus,
        List<AiProviderAttempt> attempts,
        AiTokenUsage tokenUsage,
        Throwable cause) {

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
        this(outcome, provider, model, reason, correlationId, httpStatus, attempts, AiTokenUsage.UNKNOWN, null);
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
                failure.getTokenUsage(),
                failure.getOutcome() == AiProviderException.Outcome.UNEXPECTED_ERROR
                        ? failure.getCause() : null);
    }

    public AiProviderException toException() {
        return new AiProviderException(
                outcome,
                provider,
                model,
                reason,
                correlationId,
                httpStatus,
                cause,
                tokenUsage);
    }

    @Override
    public String toString() {
        return "AiExecutionFailure[outcome=" + outcome + ", provider=" + provider + ", model=" + model
                + ", reason=" + reason + ", correlationId=" + correlationId + ", httpStatus=" + httpStatus
                + ", attempts=" + attempts + ", tokenUsage=" + tokenUsage + "]";
    }
}
