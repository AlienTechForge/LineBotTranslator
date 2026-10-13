package com.linetranslate.bot.service.webhook;

import java.util.concurrent.CompletionException;

import org.springframework.stereotype.Component;

import com.linecorp.bot.client.base.exception.AbstractLineClientException;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;

/** Outbound LINE messaging Adapter. */
@Component
public class LineMessagingReplyAdapter implements WebhookReplySender {

    private final MessagingApiClient messagingApiClient;

    public LineMessagingReplyAdapter(MessagingApiClient messagingApiClient) {
        this.messagingApiClient = messagingApiClient;
    }

    @Override
    public void send(WebhookReply reply) {
        try {
            messagingApiClient.replyMessage(new ReplyMessageRequest(
                    reply.replyToken(), reply.messages(), false)).join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof AbstractLineClientException clientFailure
                    && isRejected(clientFailure.getCode())) {
                throw new WebhookReplyRejectedException(clientFailure.getCode(), clientFailure);
            }
            if (failure.getCause() instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw failure;
        }
    }

    /**
     * A 4xx other than 429 is deterministic, for example an expired reply token or
     * an invalid message: resending the identical request cannot succeed.
     */
    private static boolean isRejected(int status) {
        return status >= 400 && status < 500 && status != 429;
    }
}
