package com.linetranslate.bot.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.webhook.model.Event;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class WebhookIngestionModuleTests {

    private WebhookEventReceiptStore receiptStore;
    private WebhookEventProcessor eventProcessor;
    private WebhookReplySender replySender;
    private WebhookIngestionProperties properties;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        receiptStore = mock(WebhookEventReceiptStore.class);
        eventProcessor = mock(WebhookEventProcessor.class);
        replySender = mock(WebhookReplySender.class);
        properties = new WebhookIngestionProperties();
        properties.setReplyMaxAttempts(3);
        properties.setReplyRetryBackoff(Duration.ZERO);
        meterRegistry = new SimpleMeterRegistry();
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
                .doNothing()
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
