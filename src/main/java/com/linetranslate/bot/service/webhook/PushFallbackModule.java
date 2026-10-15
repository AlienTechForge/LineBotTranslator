package com.linetranslate.bot.service.webhook;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.MessageQuotaResponse;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import com.linecorp.bot.messaging.model.QuotaConsumptionResponse;
import com.linecorp.bot.messaging.model.QuotaType;
import com.linetranslate.bot.logging.SafeLog;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Delivers an answer as a push message when its reply token has expired, and
 * watches the Official Account's push quota while doing so.
 *
 * <p>Replies are free but push messages consume the monthly message quota, so
 * every fallback push is bounded by a per-day counter. The day starts at
 * midnight in Asia/Taipei (UTC+8), like the usage reports. The counter is held
 * in memory and starts again from zero after a restart. A failed push still
 * counts, because a request that timed out may have been delivered.
 */
@Service
@Slf4j
public class PushFallbackModule {

    private static final ZoneId DAY_ZONE = ZoneId.of("Asia/Taipei");
    private static final Duration QUOTA_TIMEOUT = Duration.ofSeconds(5);
    private static final int QUOTA_WARNING_PERCENT = 80;

    private final MessagingApiClient messagingApiClient;
    private final PushFallbackProperties properties;
    private final Clock clock;
    private final Counter pushSent;
    private final Counter pushSkippedLimit;
    private final Counter pushFailed;

    private LocalDate day;
    private int pushedToday;
    private boolean limitLogged;
    private boolean quotaWarned;

    @Autowired
    public PushFallbackModule(
            MessagingApiClient messagingApiClient,
            PushFallbackProperties properties,
            MeterRegistry meterRegistry) {
        this(messagingApiClient, properties, meterRegistry, Clock.systemUTC());
    }

    PushFallbackModule(
            MessagingApiClient messagingApiClient,
            PushFallbackProperties properties,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.messagingApiClient = messagingApiClient;
        this.properties = properties;
        this.clock = clock;
        this.pushSent = fallbackCounter(meterRegistry, "sent");
        this.pushSkippedLimit = fallbackCounter(meterRegistry, "skipped_limit");
        this.pushFailed = fallbackCounter(meterRegistry, "failed");
    }

    /**
     * Sends the reply's messages to its push target instead.
     *
     * @return {@link ReplyDelivery#PUSHED} when the push succeeded, or
     *         {@link ReplyDelivery#PUSH_FAILED} when it failed (a failure is logged and
     *         never thrown, and is never retried so a user cannot receive the answer
     *         twice), so the fallback took over the reply either way; empty when no push
     *         was attempted because the fallback is disabled, the reply has no push
     *         target or the daily limit is reached
     */
    public Optional<ReplyDelivery> pushInstead(WebhookReply reply) {
        if (!properties.isEnabled() || reply.pushTarget() == null || reply.messages().isEmpty()) {
            log.info("Fallback push skipped: reason={}, messages={}, kinds={}",
                    properties.isEnabled() ? "no_push_target" : "disabled",
                    reply.messages().size(), reply.messageKinds());
            return Optional.empty();
        }
        if (!reservePush()) {
            pushSkippedLimit.increment();
            log.info("Fallback push skipped: reason=daily_limit, messages={}, kinds={}",
                    reply.messages().size(), reply.messageKinds());
            return Optional.empty();
        }
        try {
            messagingApiClient.pushMessage(UUID.randomUUID(), new PushMessageRequest(
                    reply.pushTarget(), reply.messages(), false, null)).join();
        } catch (RuntimeException failure) {
            pushFailed.increment();
            log.warn("Fallback push failed, not retried: messages={}, kinds={}, status={}, failure={}",
                    reply.messages().size(), reply.messageKinds(),
                    LineApiStatus.of(failure), SafeLog.failure(failure));
            return Optional.of(ReplyDelivery.PUSH_FAILED);
        }
        pushSent.increment();
        log.info("Reply token expired, delivered the answer by push: messages={}, kinds={}, pushedToday={}/{}",
                reply.messages().size(), reply.messageKinds(), pushedToday(), properties.getDailyLimit());
        warnWhenQuotaIsNearlyUsed();
        return Optional.of(ReplyDelivery.PUSHED);
    }

    /** Reads the push quota from LINE; fails with the LINE client's exception. */
    public PushQuotaStatus quotaStatus() {
        MessageQuotaResponse quota = await(messagingApiClient.getMessageQuota());
        QuotaConsumptionResponse consumption = await(messagingApiClient.getMessageQuotaConsumption());
        Long monthlyLimit = quota.type() == QuotaType.LIMITED ? quota.value() : null;
        long consumed = consumption.totalUsage() == null ? 0L : consumption.totalUsage();
        return new PushQuotaStatus(
                quota.type(),
                monthlyLimit,
                consumed,
                properties.isEnabled(),
                pushedToday(),
                properties.getDailyLimit());
    }

    private void warnWhenQuotaIsNearlyUsed() {
        if (quotaAlreadyWarnedToday()) {
            return;
        }
        try {
            PushQuotaStatus status = quotaStatus();
            Long limit = status.monthlyLimit();
            if (limit == null || limit <= 0 || status.consumed() * 100 < limit * QUOTA_WARNING_PERCENT) {
                return;
            }
            if (markQuotaWarned()) {
                log.warn("LINE push quota is nearly used: consumed={}, limit={}, percent={}",
                        status.consumed(), limit, status.consumed() * 100 / limit);
            }
        } catch (RuntimeException failure) {
            log.warn("LINE push quota check failed: failure={}", SafeLog.failure(failure));
        }
    }

    private synchronized boolean reservePush() {
        rollDay();
        if (pushedToday >= properties.getDailyLimit()) {
            if (!limitLogged) {
                limitLogged = true;
                log.warn("Fallback push daily limit reached, expired replies are not pushed "
                        + "for the rest of the day: limit={}", properties.getDailyLimit());
            }
            return false;
        }
        pushedToday++;
        return true;
    }

    private synchronized int pushedToday() {
        rollDay();
        return pushedToday;
    }

    private synchronized boolean quotaAlreadyWarnedToday() {
        rollDay();
        return quotaWarned;
    }

    private synchronized boolean markQuotaWarned() {
        rollDay();
        boolean first = !quotaWarned;
        quotaWarned = true;
        return first;
    }

    private void rollDay() {
        LocalDate today = LocalDate.now(clock.withZone(DAY_ZONE));
        if (!today.equals(day)) {
            day = today;
            pushedToday = 0;
            limitLogged = false;
            quotaWarned = false;
        }
    }

    private static <T> T await(CompletableFuture<Result<T>> response) {
        return response.orTimeout(QUOTA_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .join().body();
    }

    private static Counter fallbackCounter(MeterRegistry registry, String result) {
        return Counter.builder("line.push.fallback")
                .description("Push messages sent in place of an expired reply token")
                .tag("result", result)
                .register(registry);
    }
}
