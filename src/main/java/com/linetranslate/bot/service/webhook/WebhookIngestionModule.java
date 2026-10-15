package com.linetranslate.bot.service.webhook;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.linecorp.bot.webhook.model.Event;
import com.linetranslate.bot.logging.SafeLog;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Reliable webhook ingestion Module. It owns claim, bounded dispatch, poison
 * handling and outbound reply retries without repeating business processing.
 */
@Service
@Slf4j
public class WebhookIngestionModule {

    private static final int MAX_UNWRAP_DEPTH = 8;

    private final WebhookEventReceiptStore receiptStore;
    private final WebhookEventProcessor eventProcessor;
    private final WebhookReplySender replySender;
    private final Executor executor;
    private final WebhookIngestionProperties properties;
    private final Map<WebhookIngestionResult, Counter> ingestionCounters;
    private final Counter processingCompleted;
    private final Counter processingPoisoned;
    private final Counter replySucceeded;
    private final Counter replyRetried;
    private final Counter replyRejected;

    public WebhookIngestionModule(
            WebhookEventReceiptStore receiptStore,
            WebhookEventProcessor eventProcessor,
            WebhookReplySender replySender,
            @Qualifier("webhookIngestionExecutor") Executor executor,
            WebhookIngestionProperties properties,
            MeterRegistry meterRegistry) {
        this.receiptStore = receiptStore;
        this.eventProcessor = eventProcessor;
        this.replySender = replySender;
        this.executor = executor;
        this.properties = properties;
        validate(properties);
        this.ingestionCounters = ingestionCounters(meterRegistry);
        this.processingCompleted = processingCounter(meterRegistry, "completed");
        this.processingPoisoned = processingCounter(meterRegistry, "poisoned");
        this.replySucceeded = replyCounter(meterRegistry, "success");
        this.replyRetried = replyCounter(meterRegistry, "retry");
        this.replyRejected = replyCounter(meterRegistry, "rejected");
    }

    public WebhookIngestionResult ingest(Event event) {
        WebhookEventEnvelope envelope = WebhookEventEnvelope.from(event);
        WebhookClaim claim = receiptStore.claim(envelope);
        if (!claim.claimed()) {
            increment(WebhookIngestionResult.DUPLICATE);
            return WebhookIngestionResult.DUPLICATE;
        }

        try {
            executor.execute(() -> process(envelope, claim));
            increment(WebhookIngestionResult.ACCEPTED);
            return WebhookIngestionResult.ACCEPTED;
        } catch (RejectedExecutionException rejected) {
            receiptStore.release(claim);
            increment(WebhookIngestionResult.REJECTED);
            log.warn("Webhook queue rejected event: event={}", SafeLog.metadata(envelope.eventId()));
            return WebhookIngestionResult.REJECTED;
        }
    }

    private void process(WebhookEventEnvelope envelope, WebhookClaim claim) {
        int replyAttempts = 0;
        try {
            receiptStore.markProcessing(claim);
            var reply = eventProcessor.process(envelope.event());
            if (reply.isPresent()) {
                replyAttempts = sendWithRetry(reply.orElseThrow());
            }
            receiptStore.markCompleted(claim, replyAttempts);
            processingCompleted.increment();
        } catch (RuntimeException failure) {
            processingPoisoned.increment();
            Throwable cause = failureCause(failure);
            receiptStore.markPoisoned(claim, cause.getClass().getSimpleName(),
                    replyAttempts(failure, replyAttempts));
            log.error("Webhook event became poison: event={}, failure={}",
                    SafeLog.metadata(envelope.eventId()),
                    SafeLog.failure(cause));
        }
    }

    /**
     * Sends the reply, retrying transient failures. Every attempt is logged with the count and
     * kinds of its messages. LINE fetches an image message's URL itself, outside this call, so an
     * accepted reply that carried an {@code ImageMessage} is the last outcome this service can
     * observe for the image.
     */
    private int sendWithRetry(WebhookReply reply) {
        RuntimeException lastFailure = null;
        int maxAttempts = properties.getReplyMaxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                replySender.send(reply);
                replySucceeded.increment();
                log.info("Webhook reply sent: attempt={}/{}, messages={}, kinds={}",
                        attempt, maxAttempts, reply.messages().size(), reply.messageKinds());
                return attempt;
            } catch (WebhookReplyRejectedException rejected) {
                replyRejected.increment();
                log.warn("LINE rejected webhook reply, not retrying: status={}, attempt={}/{}, "
                                + "messages={}, kinds={}",
                        SafeLog.httpStatus(rejected.status()), attempt, maxAttempts,
                        reply.messages().size(), reply.messageKinds());
                throw new ReplyAttemptException(attempt, rejected);
            } catch (RuntimeException failure) {
                lastFailure = new ReplyAttemptException(attempt, failure);
                boolean retrying = attempt < maxAttempts;
                log.warn("Webhook reply attempt failed: attempt={}/{}, retrying={}, messages={}, kinds={}, "
                                + "status={}, failure={}",
                        attempt, maxAttempts, retrying, reply.messages().size(), reply.messageKinds(),
                        LineApiStatus.of(failure), SafeLog.failure(failureCause(failure)));
                if (retrying) {
                    replyRetried.increment();
                    backoff(properties.getReplyRetryBackoff());
                }
            }
        }
        throw lastFailure == null
                ? new IllegalStateException("Webhook reply failed without a cause")
                : lastFailure;
    }

    private void backoff(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Webhook reply retry interrupted", interrupted);
        }
    }

    private int replyAttempts(RuntimeException failure, int completedAttempts) {
        return failure instanceof ReplyAttemptException replyFailure
                ? replyFailure.attempt()
                : completedAttempts;
    }

    /**
     * The failure worth recording: the first non-wrapper in the chain. The reply
     * future's {@code join()} reports every transient I/O failure as a
     * {@link CompletionException}, so recording the outermost type would label
     * timeouts, resets and 5xx alike.
     */
    private static Throwable failureCause(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < MAX_UNWRAP_DEPTH && isWrapper(current) && current.getCause() != null; depth++) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isWrapper(Throwable failure) {
        return failure instanceof ReplyAttemptException
                || failure instanceof CompletionException
                || failure instanceof ExecutionException
                || failure instanceof UncheckedIOException;
    }

    private void increment(WebhookIngestionResult result) {
        ingestionCounters.get(result).increment();
    }

    private Map<WebhookIngestionResult, Counter> ingestionCounters(MeterRegistry registry) {
        Map<WebhookIngestionResult, Counter> counters = new EnumMap<>(WebhookIngestionResult.class);
        for (WebhookIngestionResult result : WebhookIngestionResult.values()) {
            counters.put(result, Counter.builder("line.webhook.ingestion")
                    .description("Webhook ingestion decisions")
                    .tag("result", result.name().toLowerCase(Locale.ROOT))
                    .register(registry));
        }
        return counters;
    }

    private Counter processingCounter(MeterRegistry registry, String result) {
        return Counter.builder("line.webhook.processing")
                .description("Webhook worker outcomes")
                .tag("result", result)
                .register(registry);
    }

    private Counter replyCounter(MeterRegistry registry, String result) {
        return Counter.builder("line.webhook.reply")
                .description("Webhook reply outcomes")
                .tag("result", result)
                .register(registry);
    }

    private static void validate(WebhookIngestionProperties properties) {
        if (properties.getReplyMaxAttempts() < 1) {
            throw new IllegalArgumentException("Webhook reply attempts must be positive");
        }
        if (properties.getReplyRetryBackoff() == null || properties.getReplyRetryBackoff().isNegative()) {
            throw new IllegalArgumentException("Webhook reply retry backoff must be non-negative");
        }
    }

    private static final class ReplyAttemptException extends RuntimeException {

        private final int attempt;

        private ReplyAttemptException(int attempt, RuntimeException cause) {
            super(cause);
            this.attempt = attempt;
        }

        int attempt() {
            return attempt;
        }
    }
}
