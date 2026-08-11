package com.linetranslate.bot.service.webhook;

import java.util.concurrent.CompletionException;

import org.springframework.stereotype.Component;

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
            if (failure.getCause() instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw failure;
        }
    }
}
