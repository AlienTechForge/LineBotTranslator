package com.linetranslate.bot.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.client.MessagingApiClientException;
import com.linecorp.bot.messaging.model.TextMessage;

import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

class LineMessagingReplyAdapterTests {

    private final MessagingApiClient messagingApiClient = mock(MessagingApiClient.class);
    private final LineMessagingReplyAdapter adapter = new LineMessagingReplyAdapter(messagingApiClient);
    private final WebhookReply reply = new WebhookReply("reply-token", List.of(new TextMessage("done")));

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404})
    void clientErrorIsReportedAsRejected(int status) {
        MessagingApiClientException lineError = lineError(status);
        when(messagingApiClient.replyMessage(any())).thenReturn(CompletableFuture.failedFuture(lineError));

        assertThatThrownBy(() -> adapter.send(reply))
                .isInstanceOfSatisfying(WebhookReplyRejectedException.class,
                        rejected -> assertThat(rejected.status()).isEqualTo(status))
                .hasCause(lineError);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void rateLimitAndServerErrorsStayRetryable(int status) {
        MessagingApiClientException lineError = lineError(status);
        when(messagingApiClient.replyMessage(any())).thenReturn(CompletableFuture.failedFuture(lineError));

        assertThatThrownBy(() -> adapter.send(reply))
                .isExactlyInstanceOf(CompletionException.class)
                .hasCause(lineError);
    }

    @Test
    void ioFailureStaysRetryable() {
        SocketTimeoutException timeout = new SocketTimeoutException("timeout");
        when(messagingApiClient.replyMessage(any())).thenReturn(CompletableFuture.failedFuture(timeout));

        assertThatThrownBy(() -> adapter.send(reply))
                .isExactlyInstanceOf(CompletionException.class)
                .hasCause(timeout);
    }

    private static MessagingApiClientException lineError(int status) {
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://api.line.me/v2/bot/message/reply").build())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("Error")
                .build();
        return new MessagingApiClientException(response, "error", List.of(), List.of());
    }
}
