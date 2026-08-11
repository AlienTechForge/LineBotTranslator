package com.linetranslate.bot.service.line;

import org.springframework.stereotype.Component;

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

    static final int LOADING_SECONDS = 20;

    private final MessagingApiClient messagingApiClient;

    public LineLoadingAnimationAdapter(MessagingApiClient messagingApiClient) {
        this.messagingApiClient = messagingApiClient;
    }

    @Override
    public void beforeText(Source source, LineIntent intent) {
        if (intent instanceof LineIntent.TranslateText
                || intent instanceof LineIntent.QuickTranslate
                || intent instanceof LineIntent.Retranslate) {
            show(source);
        }
    }

    @Override
    public void beforeImage(Source source) {
        show(source);
    }

    private void show(Source source) {
        if (!(source instanceof UserSource userSource)
                || userSource.userId() == null
                || userSource.userId().isBlank()) {
            return;
        }

        try {
            messagingApiClient.showLoadingAnimation(new ShowLoadingAnimationRequest(
                    userSource.userId(), LOADING_SECONDS))
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            logFailure(userSource.userId(), failure);
                        }
                    });
        } catch (RuntimeException failure) {
            logFailure(userSource.userId(), failure);
        }
    }

    private void logFailure(String userId, Throwable failure) {
        log.warn("LINE loading animation failed: user={}, failure={}",
                SafeLog.user(userId), SafeLog.failure(failure));
    }
}
