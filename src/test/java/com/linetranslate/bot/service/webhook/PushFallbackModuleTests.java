package com.linetranslate.bot.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.client.MessagingApiClientException;
import com.linecorp.bot.messaging.model.ImageMessage;
import com.linecorp.bot.messaging.model.MessageQuotaResponse;
import com.linecorp.bot.messaging.model.PushMessageResponse;
import com.linecorp.bot.messaging.model.QuotaConsumptionResponse;
import com.linecorp.bot.messaging.model.QuotaType;
import com.linecorp.bot.messaging.model.TextMessage;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

class PushFallbackModuleTests {

    /** 2026-10-13 23:59:59 in UTC+8, the last second of the reporting day. */
    private static final Instant END_OF_DAY = Instant.parse("2026-10-13T15:59:59Z");

    private final MessagingApiClient messagingApiClient = mock(MessagingApiClient.class);
    private final PushFallbackProperties properties = new PushFallbackProperties();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(END_OF_DAY);
    private final PushFallbackModule module =
            new PushFallbackModule(messagingApiClient, properties, meterRegistry, clock);
    private final WebhookReply reply = new WebhookReply(
            "reply-token", List.of(new TextMessage("private answer")), "U-private-target");
    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(PushFallbackModule.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        when(messagingApiClient.pushMessage(any(), any())).thenReturn(
                CompletableFuture.completedFuture(new Result<>("request", null, new PushMessageResponse(List.of()))));
        stubQuota(QuotaType.NONE, null, 0L);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void dailyCounterRestartsAtMidnightInUtcPlusEight() {
        properties.setDailyLimit(1);

        assertThat(module.pushInstead(reply)).isTrue();
        assertThat(module.pushInstead(reply)).isFalse();

        clock.set(Instant.parse("2026-10-13T16:00:00Z"));

        assertThat(module.pushInstead(reply)).isTrue();
        assertThat(module.pushInstead(reply)).isFalse();
        verify(messagingApiClient, times(2)).pushMessage(any(), any());
        assertThat(count("sent")).isEqualTo(2);
        assertThat(count("skipped_limit")).isEqualTo(2);
    }

    @Test
    void reachingTheDailyLimitIsLoggedOncePerDay() {
        properties.setDailyLimit(1);

        module.pushInstead(reply);
        module.pushInstead(reply);
        module.pushInstead(reply);
        assertThat(limitWarnings()).hasSize(1);

        clock.set(Instant.parse("2026-10-13T16:00:00Z"));
        module.pushInstead(reply);
        module.pushInstead(reply);
        module.pushInstead(reply);

        assertThat(limitWarnings()).hasSize(2);
        assertThat(limitWarnings().get(0).getFormattedMessage()).contains("limit=1");
    }

    @Test
    void zeroDailyLimitBlocksEveryPush() {
        properties.setDailyLimit(0);

        assertThat(module.pushInstead(reply)).isFalse();

        verify(messagingApiClient, never()).pushMessage(any(), any());
        assertThat(count("skipped_limit")).isEqualTo(1);
    }

    @Test
    void failedPushStillCountsTowardsTheDailyLimit() {
        properties.setDailyLimit(1);
        when(messagingApiClient.pushMessage(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IOException("timeout")));

        assertThat(module.pushInstead(reply)).isTrue();
        assertThat(module.pushInstead(reply)).isFalse();

        verify(messagingApiClient, times(1)).pushMessage(any(), any());
        assertThat(count("failed")).isEqualTo(1);
        assertThat(count("skipped_limit")).isEqualTo(1);
    }

    @Test
    void quotaNearTheMonthlyLimitIsWarnedAboutOncePerDay() {
        stubQuota(QuotaType.LIMITED, 1000L, 800L);

        module.pushInstead(reply);
        module.pushInstead(reply);
        module.pushInstead(reply);

        assertThat(quotaWarnings()).hasSize(1);
        assertThat(quotaWarnings().get(0).getFormattedMessage())
                .contains("consumed=800", "limit=1000", "percent=80");
        verify(messagingApiClient, times(1)).getMessageQuota();

        clock.set(Instant.parse("2026-10-13T16:00:00Z"));
        module.pushInstead(reply);

        assertThat(quotaWarnings()).hasSize(2);
    }

    @Test
    void quotaBelowEightyPercentIsNotWarnedAbout() {
        stubQuota(QuotaType.LIMITED, 1000L, 799L);

        module.pushInstead(reply);
        module.pushInstead(reply);

        assertThat(quotaWarnings()).isEmpty();
        verify(messagingApiClient, times(2)).getMessageQuota();
    }

    @Test
    void unlimitedQuotaIsNeverWarnedAbout() {
        stubQuota(QuotaType.NONE, null, 999_999L);

        module.pushInstead(reply);

        assertThat(quotaWarnings()).isEmpty();
    }

    @Test
    void quotaCheckFailureNeverAffectsTheDeliveredAnswer() {
        when(messagingApiClient.getMessageQuota())
                .thenReturn(CompletableFuture.failedFuture(new IOException("quota down")));

        assertThat(module.pushInstead(reply)).isTrue();

        assertThat(count("sent")).isEqualTo(1);
        assertThat(count("failed")).isZero();
    }

    @Test
    void deliveredPushNamesTheMessageKinds() {
        assertThat(module.pushInstead(imageReply())).isTrue();

        assertThat(infoLines()).singleElement().asString().isEqualTo(
                "Reply token expired, delivered the answer by push: messages=2, "
                        + "kinds=ImageMessage,TextMessage, pushedToday=1/" + properties.getDailyLimit());
    }

    @Test
    void failedPushLogsKindsAndLineStatusAndSaysItIsNotRetried() {
        when(messagingApiClient.pushMessage(any(), any())).thenReturn(CompletableFuture.failedFuture(lineError(500)));

        assertThat(module.pushInstead(imageReply())).isTrue();

        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN).singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).isEqualTo(
                        "Fallback push failed, not retried: messages=2, kinds=ImageMessage,TextMessage, "
                                + "status=500, failure=CompletionException<-MessagingApiClientException"));
    }

    @Test
    void failedPushWithoutALineAnswerReportsNoStatus() {
        when(messagingApiClient.pushMessage(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IOException("timeout")));

        module.pushInstead(reply);

        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN).singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("status=none", "kinds=TextMessage"));
    }

    @Test
    void skippedPushesSayWhy() {
        module.pushInstead(new WebhookReply("reply-token", List.of(new TextMessage("private answer"))));
        properties.setDailyLimit(0);
        module.pushInstead(imageReply());
        properties.setDailyLimit(10);
        properties.setEnabled(false);
        module.pushInstead(imageReply());

        assertThat(infoLines()).containsExactly(
                "Fallback push skipped: reason=no_push_target, messages=1, kinds=TextMessage",
                "Fallback push skipped: reason=daily_limit, messages=2, kinds=ImageMessage,TextMessage",
                "Fallback push skipped: reason=disabled, messages=2, kinds=ImageMessage,TextMessage");
    }

    @Test
    void logsCarryMetadataOnly() {
        stubQuota(QuotaType.LIMITED, 1000L, 900L);
        module.pushInstead(reply);
        when(messagingApiClient.pushMessage(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IOException("U-private-target private answer")));
        module.pushInstead(reply);

        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain("U-private-target", "private answer", "reply-token"));
    }

    @Test
    void quotaStatusCombinesLineQuotaWithTodaysFallbackUsage() {
        stubQuota(QuotaType.LIMITED, 1000L, 250L);
        properties.setDailyLimit(7);
        module.pushInstead(reply);

        PushQuotaStatus status = module.quotaStatus();

        assertThat(status.quotaType()).isEqualTo(QuotaType.LIMITED);
        assertThat(status.monthlyLimit()).isEqualTo(1000L);
        assertThat(status.consumed()).isEqualTo(250L);
        assertThat(status.remaining()).isEqualTo(750L);
        assertThat(status.fallbackEnabled()).isTrue();
        assertThat(status.fallbackToday()).isEqualTo(1);
        assertThat(status.fallbackDailyLimit()).isEqualTo(7);
    }

    @Test
    void quotaWithoutALimitHasNoMonthlyLimitOrRemainder() {
        when(messagingApiClient.getMessageQuotaConsumption()).thenReturn(CompletableFuture.completedFuture(
                new Result<>("request", null, new QuotaConsumptionResponse(null))));

        PushQuotaStatus status = module.quotaStatus();

        assertThat(status.quotaType()).isEqualTo(QuotaType.NONE);
        assertThat(status.monthlyLimit()).isNull();
        assertThat(status.remaining()).isNull();
        assertThat(status.consumed()).isZero();
    }

    private void stubQuota(QuotaType type, Long limit, long consumed) {
        when(messagingApiClient.getMessageQuota()).thenReturn(CompletableFuture.completedFuture(
                new Result<>("request", null, new MessageQuotaResponse(type, limit))));
        when(messagingApiClient.getMessageQuotaConsumption()).thenReturn(CompletableFuture.completedFuture(
                new Result<>("request", null, new QuotaConsumptionResponse(consumed))));
    }

    private double count(String result) {
        return meterRegistry.get("line.push.fallback").tag("result", result).counter().count();
    }

    private List<String> infoLines() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static WebhookReply imageReply() {
        return new WebhookReply("reply-token", List.of(
                new ImageMessage(
                        java.net.URI.create("https://s3.azndev.com/a.png?X-Amz-Signature=abc"),
                        java.net.URI.create("https://s3.azndev.com/a-preview.jpg?X-Amz-Signature=def")),
                new TextMessage("private answer")), "U-private-target");
    }

    private static MessagingApiClientException lineError(int status) {
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://api.line.me/v2/bot/message/push").build())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("Error")
                .build();
        return new MessagingApiClientException(response, "error-body", List.of(), List.of());
    }

    private List<ILoggingEvent> limitWarnings() {
        return warnings("daily limit");
    }

    private List<ILoggingEvent> quotaWarnings() {
        return warnings("push quota is nearly used");
    }

    private List<ILoggingEvent> warnings(String text) {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains(text))
                .toList();
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        MutableClock(Instant start) {
            this(new AtomicReference<>(start), ZoneOffset.UTC);
        }

        private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(now, zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
