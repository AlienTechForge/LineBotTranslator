package com.linetranslate.bot.service.webhook;

/**
 * LINE answered the reply with a client error that resending the same request
 * cannot fix, so the ingestion Module must not retry it.
 */
public class WebhookReplyRejectedException extends RuntimeException {

    private final int status;

    public WebhookReplyRejectedException(int status, Throwable cause) {
        super("LINE rejected the webhook reply with HTTP " + status, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
