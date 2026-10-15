package com.linetranslate.bot.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.client.MessagingApiClientException;
import com.linecorp.bot.messaging.model.ImageMessage;
import com.linecorp.bot.messaging.model.MessageQuotaResponse;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import com.linecorp.bot.messaging.model.PushMessageResponse;
import com.linecorp.bot.messaging.model.QuotaConsumptionResponse;
import com.linecorp.bot.messaging.model.QuotaType;
import com.linecorp.bot.messaging.model.TextMessage;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

class LineMessagingReplyAdapterTests {

    private final MessagingApiClient messagingApiClient = mock(MessagingApiClient.class);
    private final PushFallbackProperties pushProperties = new PushFallbackProperties();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final LineMessagingReplyAdapter adapter = new LineMessagingReplyAdapter(
            messagingApiClient,
            new PushFallbackModule(messagingApiClient, pushProperties, meterRegistry));
    private final WebhookReply reply = new WebhookReply("reply-token", List.of(new TextMessage("done")));
    private final WebhookReply pushableReply = new WebhookReply(
            "reply-token", List.of(new TextMessage("done")), "U-target");
    private final Logger adapterLogger = (Logger) org.slf4j.LoggerFactory.getLogger(LineMessagingReplyAdapter.class);
    private final ListAppender<ILoggingEvent> adapterLogs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        adapterLogs.start();
        adapterLogger.addAppender(adapterLogs);
    }

    @AfterEach
    void releaseLogs() {
        adapterLogger.detachAppender(adapterLogs);
    }

    @Test
    void handOverToThePushFallbackIsLoggedWithTheMessageKinds() {
        WebhookReply imageReply = new WebhookReply("reply-token", List.of(
                new ImageMessage(
                        java.net.URI.create("https://s3.azndev.com/a.png?X-Amz-Signature=abc"),
                        java.net.URI.create("https://s3.azndev.com/a-preview.jpg?X-Amz-Signature=def")),
                new TextMessage("done")), "U-target");
        expireReplyToken("Invalid reply token");
        when(messagingApiClient.pushMessage(any(), any())).thenReturn(pushed());
        stubQuota(QuotaType.NONE, null, 0L);

        adapter.send(imageReply);

        assertThat(adapterLogs.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .isEqualTo("LINE reply token expired, trying the push fallback: "
                        + "messages=2, kinds=ImageMessage,TextMessage"));
    }

    @Test
    void acceptedReplyIsReportedAsReplied() {
        when(messagingApiClient.replyMessage(any())).thenReturn(CompletableFuture.completedFuture(
                new Result<>("request", null, new com.linecorp.bot.messaging.model.ReplyMessageResponse(List.of()))));

        assertThat(adapter.send(pushableReply)).isEqualTo(ReplyDelivery.REPLIED);

        verify(messagingApiClient, never()).pushMessage(any(), any());
    }

    @Test
    void expiredTokenWithAcceptedPushIsReportedAsPushed() {
        expireReplyToken("Invalid reply token");
        when(messagingApiClient.pushMessage(any(), any())).thenReturn(pushed());
        stubQuota(QuotaType.NONE, null, 0L);

        assertThat(adapter.send(pushableReply)).isEqualTo(ReplyDelivery.PUSHED);
    }

    @Test
    void expiredTokenWithFailedPushIsReportedAsPushFailedNotAsReplied() {
        expireReplyToken("Invalid reply token");
        when(messagingApiClient.pushMessage(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(lineError(500)));

        assertThat(adapter.send(pushableReply)).isEqualTo(ReplyDelivery.PUSH_FAILED);

        verify(messagingApiClient, times(1)).pushMessage(any(), any());
    }

    @Test
    void ordinaryRejectionsAndRetryableFailuresAreNotLoggedHere() {
        when(messagingApiClient.replyMessage(any())).thenReturn(CompletableFuture.failedFuture(
                lineError(400, "The request body has 1 error(s)")));
        assertThatThrownBy(() -> adapter.send(pushableReply)).isInstanceOf(WebhookReplyRejectedException.class);
        when(messagingApiClient.replyMessage(any())).thenReturn(CompletableFuture.failedFuture(lineError(503)));
        assertThatThrownBy(() -> adapter.send(pushableReply)).isInstanceOf(CompletionException.class);

        assertThat(adapterLogs.list).isEmpty();
    }

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

    @ParameterizedTest
    @ValueSource(strings = {"Invalid reply token", "INVALID REPLY TOKEN"})
    void expiredReplyTokenIsPushedWithTheSameMessages(String lineMessage) {
        expireReplyToken(lineMessage);
        when(messagingApiClient.pushMessage(any(), any())).thenReturn(pushed());
        stubQuota(QuotaType.NONE, null, 0L);

        assertThatCode(() -> adapter.send(pushableReply)).doesNotThrowAnyException();

        ArgumentCaptor<UUID> retryKey = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<PushMessageRequest> request = ArgumentCaptor.forClass(PushMessageRequest.class);
        verify(messagingApiClient).pushMessage(retryKey.capture(), request.capture());
        assertThat(retryKey.getValue()).isNotNull();
        assertThat(request.getValue().to()).isEqualTo("U-target");
        assertThat(request.getValue().messages()).isEqualTo(pushableReply.messages());
        assertThat(request.getValue().notificationDisabled()).isFalse();
        assertThat(fallbackCount("sent")).isEqualTo(1);
        verify(messagingApiClient, times(1)).replyMessage(any());
    }

    @Test
    void everyFallbackPushUsesAFreshRetryKey() {
        expireReplyToken("Invalid reply token");
        when(messagingApiClient.pushMessage(any(), any())).thenReturn(pushed());
        stubQuota(QuotaType.NONE, null, 0L);

        adapter.send(pushableReply);
        adapter.send(pushableReply);

        ArgumentCaptor<UUID> retryKey = ArgumentCaptor.forClass(UUID.class);
        verify(messagingApiClient, times(2)).pushMessage(retryKey.capture(), any());
        assertThat(retryKey.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void otherBadRequestKeepsTheRejectedBehaviourWithoutPushing() {
        when(messagingApiClient.replyMessage(any())).thenReturn(CompletableFuture.failedFuture(
                lineError(400, "The request body has 1 error(s)")));

        assertThatThrownBy(() -> adapter.send(pushableReply))
                .isInstanceOf(WebhookReplyRejectedException.class);

        verify(messagingApiClient, never()).pushMessage(any(), any());
        assertThat(totalFallbackCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void onlyAnInvalidTokenBadRequestFallsBackToPush(int status) {
        when(messagingApiClient.replyMessage(any())).thenReturn(CompletableFuture.failedFuture(
                lineError(status, "Invalid reply token")));

        assertThatThrownBy(() -> adapter.send(pushableReply))
                .isInstanceOfSatisfying(WebhookReplyRejectedException.class,
                        rejected -> assertThat(rejected.status()).isEqualTo(status));

        verify(messagingApiClient, never()).pushMessage(any(), any());
    }

    @Test
    void disabledFallbackKeepsTheRejectedBehaviourWithoutPushing() {
        pushProperties.setEnabled(false);
        expireReplyToken("Invalid reply token");

        assertThatThrownBy(() -> adapter.send(pushableReply))
                .isInstanceOfSatisfying(WebhookReplyRejectedException.class,
                        rejected -> assertThat(rejected.status()).isEqualTo(400));

        verify(messagingApiClient, never()).pushMessage(any(), any());
        assertThat(totalFallbackCount()).isZero();
    }

    @Test
    void replyWithoutPushTargetIsNeverPushed() {
        expireReplyToken("Invalid reply token");

        assertThatThrownBy(() -> adapter.send(reply))
                .isInstanceOf(WebhookReplyRejectedException.class);

        verify(messagingApiClient, never()).pushMessage(any(), any());
        assertThat(totalFallbackCount()).isZero();
    }

    @Test
    void dailyLimitSkipsFurtherPushesAndCountsThem() {
        pushProperties.setDailyLimit(1);
        expireReplyToken("Invalid reply token");
        when(messagingApiClient.pushMessage(any(), any())).thenReturn(pushed());
        stubQuota(QuotaType.NONE, null, 0L);

        adapter.send(pushableReply);
        assertThatThrownBy(() -> adapter.send(pushableReply))
                .isInstanceOfSatisfying(WebhookReplyRejectedException.class,
                        rejected -> assertThat(rejected.status()).isEqualTo(400));
        assertThatThrownBy(() -> adapter.send(pushableReply))
                .isInstanceOf(WebhookReplyRejectedException.class);

        verify(messagingApiClient, times(1)).pushMessage(any(), any());
        assertThat(fallbackCount("sent")).isEqualTo(1);
        assertThat(fallbackCount("skipped_limit")).isEqualTo(2);
        assertThat(fallbackCount("failed")).isZero();
    }

    @Test
    void pushFailureIsSwallowedCountedAndNotRetried() {
        expireReplyToken("Invalid reply token");
        when(messagingApiClient.pushMessage(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(lineError(500)));

        assertThatCode(() -> adapter.send(pushableReply)).doesNotThrowAnyException();

        verify(messagingApiClient, times(1)).pushMessage(any(), any());
        verify(messagingApiClient, times(1)).replyMessage(any());
        assertThat(fallbackCount("failed")).isEqualTo(1);
        assertThat(fallbackCount("sent")).isZero();
    }

    @Test
    void pushIoFailureIsSwallowed() {
        expireReplyToken("Invalid reply token");
        when(messagingApiClient.pushMessage(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new SocketTimeoutException("timeout")));

        assertThatCode(() -> adapter.send(pushableReply)).doesNotThrowAnyException();

        assertThat(fallbackCount("failed")).isEqualTo(1);
    }

    private void expireReplyToken(String lineMessage) {
        when(messagingApiClient.replyMessage(any())).thenReturn(CompletableFuture.failedFuture(
                lineError(400, lineMessage)));
    }

    private void stubQuota(QuotaType type, Long limit, long consumed) {
        when(messagingApiClient.getMessageQuota()).thenReturn(CompletableFuture.completedFuture(
                new Result<>("request", null, new MessageQuotaResponse(type, limit))));
        when(messagingApiClient.getMessageQuotaConsumption()).thenReturn(CompletableFuture.completedFuture(
                new Result<>("request", null, new QuotaConsumptionResponse(consumed))));
    }

    private static CompletableFuture<Result<PushMessageResponse>> pushed() {
        return CompletableFuture.completedFuture(
                new Result<>("request", null, new PushMessageResponse(List.of())));
    }

    private double fallbackCount(String result) {
        return meterRegistry.get("line.push.fallback").tag("result", result).counter().count();
    }

    private double totalFallbackCount() {
        return fallbackCount("sent") + fallbackCount("skipped_limit") + fallbackCount("failed");
    }

    private static MessagingApiClientException lineError(int status) {
        return lineError(status, "error");
    }

    private static MessagingApiClientException lineError(int status, String lineMessage) {
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://api.line.me/v2/bot/message/reply").build())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("Error")
                .build();
        return new MessagingApiClientException(response, lineMessage, List.of(), List.of());
    }
}
