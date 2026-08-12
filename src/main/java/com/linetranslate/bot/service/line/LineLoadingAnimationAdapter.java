package com.linetranslate.bot.service.line;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ShowLoadingAnimationRequest;
import com.linecorp.bot.webhook.model.Source;
import com.linecorp.bot.webhook.model.UserSource;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.line.intent.LineIntent;

import lombok.extern.slf4j.Slf4j;

/** Best-effort Adapter for LINE's one-on-one loading animation endpoint. */
@Component
@Slf4j
public class LineLoadingAnimationAdapter implements LineLoadingFeedback {

    static final int TEXT_LOADING_SECONDS = 20;
    static final int IMAGE_LOADING_SECONDS = 60;
    static final int IMAGE_RENEWAL_SECONDS = 50;

    private final MessagingApiClient messagingApiClient;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;

    @Autowired
    public LineLoadingAnimationAdapter(MessagingApiClient messagingApiClient) {
        this(messagingApiClient, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "line-loading-renewal");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    LineLoadingAnimationAdapter(
            MessagingApiClient messagingApiClient,
            ScheduledExecutorService scheduler) {
        this(messagingApiClient, scheduler, false);
    }

    private LineLoadingAnimationAdapter(
            MessagingApiClient messagingApiClient,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler) {
        this.messagingApiClient = messagingApiClient;
        this.scheduler = scheduler;
        this.ownsScheduler = ownsScheduler;
    }

    @Override
    public void beforeText(Source source, LineIntent intent) {
        if (intent instanceof LineIntent.TranslateText
                || intent instanceof LineIntent.QuickTranslate
                || intent instanceof LineIntent.StyledTranslate
                || intent instanceof LineIntent.Retranslate
                || intent instanceof LineIntent.Restyle) {
            show(source, TEXT_LOADING_SECONDS);
        }
    }

    @Override
    public LineLoadingSession startImage(Source source) {
        String userId = userId(source);
        if (userId == null) return LineLoadingSession.NONE;
        show(userId, IMAGE_LOADING_SECONDS);
        try {
            ScheduledFuture<?> renewal = scheduler.scheduleAtFixedRate(
                    () -> show(userId, IMAGE_LOADING_SECONDS),
                    IMAGE_RENEWAL_SECONDS,
                    IMAGE_RENEWAL_SECONDS,
                    TimeUnit.SECONDS);
            return () -> renewal.cancel(false);
        } catch (RuntimeException failure) {
            logFailure(userId, failure);
            return LineLoadingSession.NONE;
        }
    }

    private void show(Source source, int loadingSeconds) {
        String userId = userId(source);
        if (userId != null) show(userId, loadingSeconds);
    }

    private void show(String userId, int loadingSeconds) {
        try {
            messagingApiClient.showLoadingAnimation(new ShowLoadingAnimationRequest(
                    userId, loadingSeconds))
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            logFailure(userId, failure);
                        }
                    });
        } catch (RuntimeException failure) {
            logFailure(userId, failure);
        }
    }

    private static String userId(Source source) {
        if (!(source instanceof UserSource userSource)
                || userSource.userId() == null
                || userSource.userId().isBlank()) return null;
        return userSource.userId();
    }

    @PreDestroy
    void shutdown() {
        if (ownsScheduler) scheduler.shutdownNow();
    }

    private void logFailure(String userId, Throwable failure) {
        log.warn("LINE loading animation failed: user={}, failure={}",
                SafeLog.user(userId), SafeLog.failure(failure));
    }
}
