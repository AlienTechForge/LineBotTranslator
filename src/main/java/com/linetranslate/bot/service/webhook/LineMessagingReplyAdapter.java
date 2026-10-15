package com.linetranslate.bot.service.webhook;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import org.springframework.stereotype.Component;

import com.linecorp.bot.client.base.exception.AbstractLineClientException;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.client.MessagingApiClientException;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * Outbound LINE messaging Adapter. It reports how each reply was delivered; the ingestion Module,
 * which knows the attempt number, logs that outcome. This class logs only the hand-over to the
 * push fallback.
 */
@Component
@Slf4j
public class LineMessagingReplyAdapter implements WebhookReplySender {

    private final MessagingApiClient messagingApiClient;
    private final PushFallbackModule pushFallback;

    public LineMessagingReplyAdapter(MessagingApiClient messagingApiClient, PushFallbackModule pushFallback) {
        this.messagingApiClient = messagingApiClient;
        this.pushFallback = pushFallback;
    }

    @Override
    public ReplyDelivery send(WebhookReply reply) {
        try {
            messagingApiClient.replyMessage(new ReplyMessageRequest(
                    reply.replyToken(), reply.messages(), false)).join();
            return ReplyDelivery.REPLIED;
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof AbstractLineClientException clientFailure
                    && isRejected(clientFailure.getCode())) {
                if (isExpiredReplyToken(clientFailure)) {
                    log.info("LINE reply token expired, trying the push fallback: messages={}, kinds={}",
                            reply.messages().size(), reply.messageKinds());
                    Optional<ReplyDelivery> pushed = pushFallback.pushInstead(reply);
                    if (pushed.isPresent()) {
                        return pushed.get();
                    }
                }
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

    /** LINE answers an expired or already used reply token with 400 {@code Invalid reply token}. */
    private static boolean isExpiredReplyToken(AbstractLineClientException failure) {
        return failure.getCode() == 400
                && failure instanceof MessagingApiClientException lineFailure
                && lineFailure.getError() != null
                && lineFailure.getError().toLowerCase(Locale.ROOT).contains("reply token");
    }
}
