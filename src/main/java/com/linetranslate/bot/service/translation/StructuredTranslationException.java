package com.linetranslate.bot.service.translation;

/**
 * Structured image mapping failure. The reason is a server-owned stable code so operators can see
 * which stage failed without any provider payload reaching the logs.
 */
public class StructuredTranslationException extends RuntimeException {

    /** Provider execution itself failed on both attempts. */
    public static final String PROVIDER_FAILED = "PROVIDER_FAILED";
    /** The provider answered, but no region survived decoding on either attempt. */
    public static final String NO_USABLE_REGION = "NO_USABLE_REGION";
    /** The response envelope did not match the contract. */
    public static final String ENVELOPE_INVALID = "ENVELOPE_INVALID";

    private final String reason;

    public StructuredTranslationException(String message) {
        this(ENVELOPE_INVALID, message);
    }

    public StructuredTranslationException(String reason, String message) {
        super(message);
        this.reason = reason == null ? ENVELOPE_INVALID : reason;
    }

    public String reason() {
        return reason;
    }
}
