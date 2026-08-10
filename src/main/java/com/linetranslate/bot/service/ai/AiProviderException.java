package com.linetranslate.bot.service.ai;

/**
 * A normalized provider outcome that callers can handle without parsing error text.
 */
public final class AiProviderException extends RuntimeException {

    private static final int MAX_METADATA_LENGTH = 100;

    public enum Outcome {
        SAFETY_BLOCKED,
        EMPTY_RESPONSE,
        MALFORMED_RESPONSE,
        HTTP_ERROR,
        TRANSPORT_ERROR
    }

    private final Outcome outcome;
    private final String provider;
    private final String model;
    private final String reason;
    private final String correlationId;
    private final int httpStatus;

    public AiProviderException(
            Outcome outcome,
            String provider,
            String model,
            String reason,
            String correlationId,
            int httpStatus,
            Throwable cause) {
        super("AI provider request failed: " + outcome, cause);
        this.outcome = outcome;
        this.provider = normalizeMetadata(provider);
        this.model = normalizeMetadata(model);
        this.reason = normalizeMetadata(reason);
        this.correlationId = normalizeMetadata(correlationId);
        this.httpStatus = httpStatus;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getReason() {
        return reason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    private static String normalizeMetadata(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = value.replaceAll("[^a-zA-Z0-9._:-]", "_");
        return normalized.length() <= MAX_METADATA_LENGTH
                ? normalized
                : normalized.substring(0, MAX_METADATA_LENGTH);
    }
}
