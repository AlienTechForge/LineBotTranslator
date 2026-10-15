package com.linetranslate.bot.service.webhook;

/**
 * How a webhook reply left the sender without throwing. A sender throws when the reply failed and
 * the ingestion Module may retry or poison it; each of these is a final outcome that is never
 * retried.
 */
public enum ReplyDelivery {

    /** LINE accepted the reply API call. */
    REPLIED,

    /** The reply token had expired and LINE accepted the fallback push. */
    PUSHED,

    /**
     * The reply token had expired and the fallback push failed. The failure is handled, not
     * thrown, and never retried, because a push that timed out may have been delivered and the
     * user must not receive the answer twice.
     */
    PUSH_FAILED
}
