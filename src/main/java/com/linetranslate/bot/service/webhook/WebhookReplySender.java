package com.linetranslate.bot.service.webhook;

@FunctionalInterface
public interface WebhookReplySender {

    /**
     * Delivers the reply.
     *
     * @return how it was delivered; never {@code null}
     * @throws RuntimeException when the reply failed: a {@link WebhookReplyRejectedException} for
     *         a failure that resending cannot fix, anything else for one the caller may retry
     */
    ReplyDelivery send(WebhookReply reply);
}
