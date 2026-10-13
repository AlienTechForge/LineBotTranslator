package com.linetranslate.bot.service.translation;

import com.linetranslate.bot.service.ai.AiExecutionFailure;

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
    private final AiExecutionFailure unexpectedFailure;

    public StructuredTranslationException(String message) {
        this(ENVELOPE_INVALID, message);
    }

    public StructuredTranslationException(String reason, String message) {
        this(reason, message, null);
    }

    /**
     * @param unexpectedFailure a provider bug ({@code UNEXPECTED_ERROR}) that one of the attempts
     *        hit, or {@code null}; the workflow degrades to plain text, so this is how the bug
     *        stays visible
     */
    public StructuredTranslationException(String reason, String message, AiExecutionFailure unexpectedFailure) {
        super(message);
        this.reason = reason == null ? ENVELOPE_INVALID : reason;
        this.unexpectedFailure = unexpectedFailure;
    }

    public String reason() {
        return reason;
    }

    public AiExecutionFailure unexpectedFailure() {
        return unexpectedFailure;
    }
}
