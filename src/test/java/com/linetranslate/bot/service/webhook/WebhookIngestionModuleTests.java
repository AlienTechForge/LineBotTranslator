package com.linetranslate.bot.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.client.MessagingApiClientException;
import com.linecorp.bot.messaging.model.ImageMessage;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.webhook.model.Event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

class WebhookIngestionModuleTests {

    private WebhookEventReceiptStore receiptStore;
    private WebhookEventProcessor eventProcessor;
    private WebhookReplySender replySender;
    private WebhookIngestionProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(WebhookIngestionModule.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        receiptStore = mock(WebhookEventReceiptStore.class);
        eventProcessor = mock(WebhookEventProcessor.class);
        replySender = mock(WebhookReplySender.class);
        properties = new WebhookIngestionProperties();
        properties.setReplyMaxAttempts(3);
        properties.setReplyRetryBackoff(Duration.ZERO);
        meterRegistry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void claimedEventIsProcessedAndCompletedOnce() {
        Event event = event("event-1", 2_000L, false);
        WebhookClaim claim = WebhookClaim.claimed("event-1", "claim-1");
        when(receiptStore.claim(any())).thenReturn(claim);
        when(eventProcessor.process(event)).thenReturn(Optional.empty());
        WebhookIngestionModule module = module(Runnable::run);

        assertThat(module.ingest(event)).isEqualTo(WebhookIngestionResult.ACCEPTED);

        verify(receiptStore).markProcessing(claim);
        verify(eventProcessor).process(event);
        verify(receiptStore).markCompleted(claim, 0);
        verify(receiptStore, never()).markPoisoned(any(), any(), any(Integer.class));
    }

    @Test
    void duplicateRedeliveryDoesNotProcessOrReplyAgain() {
        Event redelivery = event("event-duplicate", 2_000L, true);
        when(receiptStore.claim(any())).thenReturn(WebhookClaim.duplicate("event-duplicate"));
        WebhookIngestionModule module = module(Runnable::run);

        assertThat(module.ingest(redelivery)).isEqualTo(WebhookIngestionResult.DUPLICATE);

        verify(eventProcessor, never()).process(any());
        verify(replySender, never()).send(any());
        assertThat(meterRegistry.get("line.webhook.ingestion")
                .tag("result", "duplicate").counter().count()).isEqualTo(1);
    }

    @Test
    void uniqueOutOfOrderEventsAreBothAccepted() {
        Event newer = event("event-newer", 3_000L, false);
        Event older = event("event-older", 1_000L, true);
        when(receiptStore.claim(any()))
                .thenReturn(WebhookClaim.claimed("event-newer", "claim-newer"))
                .thenReturn(WebhookClaim.claimed("event-older", "claim-older"));
        when(eventProcessor.process(any())).thenReturn(Optional.empty());
        WebhookIngestionModule module = module(Runnable::run);

        assertThat(module.ingest(newer)).isEqualTo(WebhookIngestionResult.ACCEPTED);
        assertThat(module.ingest(older)).isEqualTo(WebhookIngestionResult.ACCEPTED);

        verify(eventProcessor).process(newer);
        verify(eventProcessor).process(older);
    }

    @Test
    void fullQueueReleasesClaimAndRequestsRedelivery() {
        Event event = event("event-full", 2_000L, false);
        WebhookClaim claim = WebhookClaim.claimed("event-full", "claim-full");
        when(receiptStore.claim(any())).thenReturn(claim);
        Executor rejectingExecutor = task -> {
            throw new RejectedExecutionException("full");
        };
        WebhookIngestionModule module = module(rejectingExecutor);

        assertThat(module.ingest(event)).isEqualTo(WebhookIngestionResult.REJECTED);

        verify(receiptStore).release(claim);
        verify(eventProcessor, never()).process(any());
        assertThat(meterRegistry.get("line.webhook.ingestion")
                .tag("result", "rejected").counter().count()).isEqualTo(1);
    }

    @Test
    void processingFailureBecomesPoisonWithoutUnsafeBusinessRetry() {
        Event event = event("event-poison", 2_000L, false);
        WebhookClaim claim = WebhookClaim.claimed("event-poison", "claim-poison");
        when(receiptStore.claim(any())).thenReturn(claim);
        when(eventProcessor.process(event)).thenThrow(new IllegalStateException("broken"));
        WebhookIngestionModule module = module(Runnable::run);

        assertThat(module.ingest(event)).isEqualTo(WebhookIngestionResult.ACCEPTED);

        verify(eventProcessor).process(event);
        verify(receiptStore).markPoisoned(claim, "IllegalStateException", 0);
        verify(receiptStore, never()).markCompleted(any(), any(Integer.class));
    }

    @Test
    void replyRetriesDoNotRepeatTranslationOrPersistenceWork() {
        Event event = event("event-reply", 2_000L, false);
        WebhookClaim claim = WebhookClaim.claimed("event-reply", "claim-reply");
        WebhookReply reply = new WebhookReply("reply-token", List.of(new TextMessage("done")));
        when(receiptStore.claim(any())).thenReturn(claim);
        when(eventProcessor.process(event)).thenReturn(Optional.of(reply));
        doThrow(new IllegalStateException("temporary-1"))
                .doThrow(new IllegalStateException("temporary-2"))
                .doReturn(ReplyDelivery.REPLIED)
                .when(replySender).send(reply);
        WebhookIngestionModule module = module(Runnable::run);

        assertThat(module.ingest(event)).isEqualTo(WebhookIngestionResult.ACCEPTED);

        verify(eventProcessor).process(event);
        verify(replySender, times(3)).send(reply);
        verify(receiptStore).markCompleted(claim, 3);
        assertThat(meterRegistry.get("line.webhook.reply")
                .tag("result", "retry").counter().count()).isEqualTo(2);
    }

    @Test
    void exhaustedReplyRetriesMarkPoisonAndNeverReprocessTheEvent() {
        Event event = event("event-reply-poison", 2_000L, false);
        WebhookClaim claim = WebhookClaim.claimed("event-reply-poison", "claim-reply-poison");
        WebhookReply reply = new WebhookReply("reply-token", List.of(new TextMessage("done")));
        when(receiptStore.claim(any())).thenReturn(claim);
        when(eventProcessor.process(event)).thenReturn(Optional.of(reply));
        doThrow(new IllegalStateException("unavailable")).when(replySender).send(reply);
        WebhookIngestionModule module = module(Runnable::run);

        assertThat(module.ingest(event)).isEqualTo(WebhookIngestionResult.ACCEPTED);

        verify(eventProcessor).process(event);
        verify(replySender, times(3)).send(reply);
        verify(receiptStore).markPoisoned(claim, "IllegalStateException", 3);
        verify(receiptStore, never()).markCompleted(any(), any(Integer.class));
    }

    @Test
    void rejectedReplyIsSentOnceAndPoisonedWithoutBackoff() {
        properties.setReplyRetryBackoff(Duration.ofMinutes(1));
        Event event = event("event-reply-rejected", 2_000L, false);
        WebhookClaim claim = WebhookClaim.claimed("event-reply-rejected", "claim-reply-rejected");
        WebhookReply reply = new WebhookReply("expired-token", List.of(new TextMessage("done")));
        when(receiptStore.claim(any())).thenReturn(claim);
        when(eventProcessor.process(event)).thenReturn(Optional.of(reply));
        doThrow(new WebhookReplyRejectedException(400, new IOException("rejected")))
                .when(replySender).send(reply);
        WebhookIngestionModule module = module(Runnable::run);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThat(module.ingest(event)).isEqualTo(WebhookIngestionResult.ACCEPTED));

        verify(eventProcessor).process(event);
        verify(replySender, times(1)).send(reply);
        verify(receiptStore).markPoisoned(claim, "WebhookReplyRejectedException", 1);
        verify(receiptStore, never()).markCompleted(any(), any(Integer.class));
        assertThat(replyCount("rejected")).isEqualTo(1);
        assertThat(replyCount("retry")).isZero();
        assertThat(logMessages(Level.WARN)).singleElement().asString().contains("status=400");
        assertThat(logMessages(Level.ERROR)).singleElement().asString()
                .contains("failure=WebhookReplyRejectedException<-IOException");
    }

    @Test
    void rejectionAfterTransientFailureRecordsEveryAttempt() {
        Event event = event("event-reply-late-reject", 2_000L, false);
        WebhookClaim claim = WebhookClaim.claimed("event-reply-late-reject", "claim-reply-late-reject");
        WebhookReply reply = new WebhookReply("reply-token", List.of(new TextMessage("done")));
        when(receiptStore.claim(any())).thenReturn(claim);
        when(eventProcessor.process(event)).thenReturn(Optional.of(reply));
        doThrow(new CompletionException(new SocketTimeoutException("timeout")))
                .doThrow(new WebhookReplyRejectedException(400, new IOException("rejected")))
                .when(replySender).send(reply);
        WebhookIngestionModule module = module(Runnable::run);

        assertThat(module.ingest(event)).isEqualTo(WebhookIngestionResult.ACCEPTED);

        verify(replySender, times(2)).send(reply);
        verify(receiptStore).markPoisoned(claim, "WebhookReplyRejectedException", 2);
        assertThat(replyCount("retry")).isEqualTo(1);
        assertThat(replyCount("rejected")).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("transientReplyFailures")
    void transientReplyFailuresAreRetriedUntilExhausted(RuntimeException failure, String failureType) {
        Event event = event("event-reply-transient", 2_000L, false);
        WebhookClaim claim = WebhookClaim.claimed("event-reply-transient", "claim-reply-transient");
        WebhookReply reply = new WebhookReply("reply-token", List.of(new TextMessage("done")));
        when(receiptStore.claim(any())).thenReturn(claim);
        when(eventProcessor.process(event)).thenReturn(Optional.of(reply));
        doThrow(failure).when(replySender).send(reply);
        WebhookIngestionModule module = module(Runnable::run);

        assertThat(module.ingest(event)).isEqualTo(WebhookIngestionResult.ACCEPTED);

        verify(replySender, times(3)).send(reply);
        verify(receiptStore).markPoisoned(claim, failureType, 3);
        assertThat(replyCount("retry")).isEqualTo(2);
        assertThat(replyCount("rejected")).isZero();
    }

    /**
     * The reply adapter lets 429, 5xx and I/O failures through as the
     * CompletionException that wraps the SDK's checked IOException; the receipt
     * has to name that cause, not the wrapper.
     */
    private static Stream<Arguments> transientReplyFailures() {
        return Stream.of(
                Arguments.of(new CompletionException(new IOException("server error")), "IOException"),
                Arguments.of(new CompletionException(new SocketTimeoutException("timeout")),
                        "SocketTimeoutException"),
                Arguments.of(new CompletionException(new CompletionException(new SocketTimeoutException("timeout"))),
                        "SocketTimeoutException"),
                Arguments.of(new CompletionException(new ExecutionException(new SocketTimeoutException("timeout"))),
                        "SocketTimeoutException"),
                Arguments.of(new UncheckedIOException(new SocketTimeoutException("timeout")),
                        "SocketTimeoutException"),
                Arguments.of(new CompletionException(new IllegalStateException("unavailable")),
                        "IllegalStateException"),
                Arguments.of(new CompletionException("no cause", null), "CompletionException"),
                Arguments.of(new IllegalStateException("unavailable"), "IllegalStateException"));
    }

    @Test
    void poisonLogNamesTheUnwrappedCauseWithoutMessageText() {
        Event event = event("event-reply-timeout", 2_000L, false);
        WebhookClaim claim = WebhookClaim.claimed("event-reply-timeout", "claim-reply-timeout");
        WebhookReply reply = new WebhookReply("reply-token", List.of(new TextMessage("done")));
        when(receiptStore.claim(any())).thenReturn(claim);
        when(eventProcessor.process(event)).thenReturn(Optional.of(reply));
        doThrow(new CompletionException(new SocketTimeoutException("secret-detail")))
                .when(replySender).send(reply);
        WebhookIngestionModule module = module(Runnable::run);

        assertThat(module.ingest(event)).isEqualTo(WebhookIngestionResult.ACCEPTED);

        verify(receiptStore).markPoisoned(claim, "SocketTimeoutException", 3);
        assertThat(logMessages(Level.ERROR)).singleElement().asString()
                .contains("event=event-reply-timeout")
                .contains("failure=SocketTimeoutException")
                .doesNotContain("CompletionException")
                .doesNotContain("ReplyAttemptException")
                .doesNotContain("secret-detail");
    }

    @Test
    void wrappedProcessingFailureIsRecordedByItsCause() {
        Event event = event("event-process-wrapped", 2_000L, false);
        WebhookClaim claim = WebhookClaim.claimed("event-process-wrapped", "claim-process-wrapped");
        when(receiptStore.claim(any())).thenReturn(claim);
        when(eventProcessor.process(event)).thenThrow(new CompletionException(new IllegalStateException("broken")));
        WebhookIngestionModule module = module(Runnable::run);

        assertThat(module.ingest(event)).isEqualTo(WebhookIngestionResult.ACCEPTED);

        verify(receiptStore).markPoisoned(claim, "IllegalStateException", 0);
        assertThat(logMessages(Level.ERROR)).singleElement().asString()
                .contains("failure=IllegalStateException");
    }

    @Test
    void acceptedImageReplyIsLoggedWithItsAttemptAndMessageKinds() {
        WebhookReply reply = imageReply();
        stubReply("event-image-ok", reply);
        doReturn(ReplyDelivery.REPLIED).when(replySender).send(reply);

        module(Runnable::run).ingest(event("event-image-ok", 2_000L, false));

        assertThat(logMessages(Level.INFO)).singleElement().asString()
                .isEqualTo("Webhook reply sent: attempt=1/3, messages=2, kinds=ImageMessage,TextMessage");
        assertThat(logMessages(Level.WARN)).isEmpty();
    }

    @Test
    void pushedReplyIsLoggedAsDeliveredByPushNotAsSent() {
        WebhookReply reply = imageReply();
        WebhookClaim claim = stubReply("event-image-pushed", reply);
        doReturn(ReplyDelivery.PUSHED).when(replySender).send(reply);

        module(Runnable::run).ingest(event("event-image-pushed", 2_000L, false));

        assertThat(logMessages(Level.INFO)).singleElement().asString().isEqualTo(
                "Webhook reply delivered by push fallback: attempt=1/3, messages=2, "
                        + "kinds=ImageMessage,TextMessage");
        assertThat(logMessages(Level.WARN)).isEmpty();
        verify(replySender, times(1)).send(reply);
        verify(receiptStore).markCompleted(claim, 1);
    }

    @Test
    void failedFallbackPushIsLoggedAsNotDeliveredYetStillCompletesWithoutRetryOrPoison() {
        WebhookReply reply = imageReply();
        WebhookClaim claim = stubReply("event-image-push-failed", reply);
        doReturn(ReplyDelivery.PUSH_FAILED).when(replySender).send(reply);

        module(Runnable::run).ingest(event("event-image-push-failed", 2_000L, false));

        assertThat(logMessages(Level.WARN)).singleElement().asString().isEqualTo(
                "Webhook reply not delivered: reason=fallback_push_failed, attempt=1/3, messages=2, "
                        + "kinds=ImageMessage,TextMessage");
        assertThat(logMessages(Level.INFO)).isEmpty();
        assertThat(logMessages(Level.ERROR)).isEmpty();
        verify(replySender, times(1)).send(reply);
        verify(receiptStore).markCompleted(claim, 1);
        verify(receiptStore, never()).markPoisoned(any(), any(), any(Integer.class));
        assertThat(replyCount("retry")).isZero();
        assertThat(replyCount("rejected")).isZero();
    }

    @Test
    void expiredTokenWithAFailingPushThroughTheRealAdapterNeverClaimsTheReplyWasSent() {
        MessagingApiClient lineClient = mock(MessagingApiClient.class);
        when(lineClient.replyMessage(any())).thenReturn(CompletableFuture.failedFuture(
                lineError(400, "Invalid reply token")));
        when(lineClient.pushMessage(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(lineError(500, "error-body")));
        LineMessagingReplyAdapter adapter = new LineMessagingReplyAdapter(lineClient,
                new PushFallbackModule(lineClient, new PushFallbackProperties(), meterRegistry));
        WebhookReply reply = new WebhookReply(
                "reply-token", imageReply().messages(), "U-private-target");
        WebhookClaim claim = stubReply("event-image-expired", reply);
        WebhookIngestionModule module = new WebhookIngestionModule(
                receiptStore, eventProcessor, adapter, Runnable::run, properties, meterRegistry);

        module.ingest(event("event-image-expired", 2_000L, false));

        assertThat(logMessages(Level.INFO)).noneMatch(line -> line.startsWith("Webhook reply sent"));
        assertThat(logMessages(Level.WARN)).singleElement().asString()
                .startsWith("Webhook reply not delivered: reason=fallback_push_failed, attempt=1/3");
        verify(lineClient, times(1)).replyMessage(any());
        verify(lineClient, times(1)).pushMessage(any(), any());
        verify(receiptStore).markCompleted(claim, 1);
        verify(receiptStore, never()).markPoisoned(any(), any(), any(Integer.class));
    }

    @Test
    void everyFailedAttemptIsLoggedWithTheLineStatusAndWhetherItWillBeRetried() {
        WebhookReply reply = imageReply();
        stubReply("event-image-retry", reply);
        doThrow(new CompletionException(lineError(503)))
                .doThrow(new CompletionException(new SocketTimeoutException("secret-detail")))
                .doReturn(ReplyDelivery.REPLIED)
                .when(replySender).send(reply);

        module(Runnable::run).ingest(event("event-image-retry", 2_000L, false));

        assertThat(logMessages(Level.WARN)).hasSize(2);
        assertThat(logMessages(Level.WARN).get(0)).isEqualTo(
                "Webhook reply attempt failed: attempt=1/3, retrying=true, messages=2, "
                        + "kinds=ImageMessage,TextMessage, status=503, failure=MessagingApiClientException");
        assertThat(logMessages(Level.WARN).get(1)).isEqualTo(
                "Webhook reply attempt failed: attempt=2/3, retrying=true, messages=2, "
                        + "kinds=ImageMessage,TextMessage, status=none, failure=SocketTimeoutException");
        assertThat(logMessages(Level.INFO)).singleElement().asString()
                .startsWith("Webhook reply sent: attempt=3/3, messages=2");
    }

    @Test
    void lastFailedAttemptSaysItWillNotBeRetried() {
        WebhookReply reply = imageReply();
        stubReply("event-image-exhausted", reply);
        doThrow(new CompletionException(lineError(500))).when(replySender).send(reply);

        module(Runnable::run).ingest(event("event-image-exhausted", 2_000L, false));

        assertThat(logMessages(Level.WARN)).hasSize(3);
        assertThat(logMessages(Level.WARN).get(2))
                .contains("attempt=3/3", "retrying=false", "status=500");
    }

    @Test
    void rejectedImageReplyLogsTheStatusAttemptAndKindsInTheExistingWarning() {
        WebhookReply reply = imageReply();
        stubReply("event-image-rejected", reply);
        doThrow(new WebhookReplyRejectedException(400, new IOException("rejected")))
                .when(replySender).send(reply);

        module(Runnable::run).ingest(event("event-image-rejected", 2_000L, false));

        assertThat(logMessages(Level.WARN)).singleElement().asString().isEqualTo(
                "LINE rejected webhook reply, not retrying: status=400, attempt=1/3, "
                        + "messages=2, kinds=ImageMessage,TextMessage");
    }

    @Test
    void replyLogsNeverCarryUrlsOrMessageContent() {
        WebhookReply reply = imageReply();
        stubReply("event-image-safe", reply);
        doThrow(new CompletionException(lineError(502)))
                .doReturn(ReplyDelivery.REPLIED).when(replySender).send(reply);

        module(Runnable::run).ingest(event("event-image-safe", 2_000L, false));

        assertThat(logs.list).isNotEmpty().allSatisfy(entry -> assertThat(entry.getFormattedMessage())
                .doesNotContain("X-Amz", "s3.azndev.com", "translated text", "reply-token", "error-body"));
    }

    private WebhookClaim stubReply(String eventId, WebhookReply reply) {
        WebhookClaim claim = WebhookClaim.claimed(eventId, "claim-" + eventId);
        when(receiptStore.claim(any())).thenReturn(claim);
        when(eventProcessor.process(any())).thenReturn(Optional.of(reply));
        return claim;
    }

    private static WebhookReply imageReply() {
        URI image = URI.create("https://s3.azndev.com/line-bot/translated-images/1/a.png?X-Amz-Signature=abc");
        URI preview = URI.create("https://s3.azndev.com/line-bot/translated-images/1/a-preview.jpg?X-Amz-Signature=def");
        return new WebhookReply("reply-token",
                List.of(new ImageMessage(image, preview), new TextMessage("translated text")));
    }

    private static MessagingApiClientException lineError(int status) {
        return lineError(status, "error-body");
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

    private List<String> logMessages(Level level) {
        return logs.list.stream()
                .filter(entry -> entry.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private double replyCount(String result) {
        return meterRegistry.get("line.webhook.reply").tag("result", result).counter().count();
    }

    private WebhookIngestionModule module(Executor executor) {
        return new WebhookIngestionModule(
                receiptStore,
                eventProcessor,
                replySender,
                executor,
                properties,
                meterRegistry);
    }

    private static Event event(String eventId, long timestamp, boolean redelivery) {
        Event event = mock(Event.class);
        when(event.webhookEventId()).thenReturn(eventId);
        when(event.timestamp()).thenReturn(timestamp);
        when(event.deliveryContext()).thenReturn(
                new com.linecorp.bot.webhook.model.DeliveryContext(redelivery));
        return event;
    }
}
