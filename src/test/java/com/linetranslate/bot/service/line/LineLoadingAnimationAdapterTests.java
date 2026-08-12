package com.linetranslate.bot.service.line;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doReturn;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import org.mockito.ArgumentCaptor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ShowLoadingAnimationRequest;
import com.linecorp.bot.webhook.model.GroupSource;
import com.linecorp.bot.webhook.model.UserSource;
import com.linetranslate.bot.service.line.intent.LineIntent;

@ExtendWith(MockitoExtension.class)
class LineLoadingAnimationAdapterTests {

    @Mock
    private MessagingApiClient messagingApiClient;

    @Test
    void privateTextTranslationDisplaysBoundedLoadingAnimation() {
        var request = new ShowLoadingAnimationRequest("user-1", 20);
        when(messagingApiClient.showLoadingAnimation(request))
                .thenReturn(CompletableFuture.completedFuture(null));
        var adapter = new LineLoadingAnimationAdapter(messagingApiClient);

        adapter.beforeText(
                new UserSource("user-1"),
                new LineIntent.TranslateText("hello"));

        verify(messagingApiClient).showLoadingAnimation(request);
    }

    @Test
    void privateQuickTranslationDisplaysLoadingAnimation() {
        var request = new ShowLoadingAnimationRequest("user-1", 20);
        when(messagingApiClient.showLoadingAnimation(request))
                .thenReturn(CompletableFuture.completedFuture(null));
        var adapter = new LineLoadingAnimationAdapter(messagingApiClient);

        adapter.beforeText(
                new UserSource("user-1"),
                new LineIntent.QuickTranslate("日文", "hello"));

        verify(messagingApiClient).showLoadingAnimation(request);
    }

    @Test
    void privateRetranslationDisplaysLoadingAnimation() {
        var request = new ShowLoadingAnimationRequest("user-1", 20);
        when(messagingApiClient.showLoadingAnimation(request))
                .thenReturn(CompletableFuture.completedFuture(null));
        var adapter = new LineLoadingAnimationAdapter(messagingApiClient);

        adapter.beforeText(
                new UserSource("user-1"),
                new LineIntent.Retranslate("507f1f77bcf86cd799439011", "ja"));

        verify(messagingApiClient).showLoadingAnimation(request);
    }

    @Test
    void privateStyleTranslationDisplaysLoadingAnimation() {
        var request = new ShowLoadingAnimationRequest("user-1", 20);
        when(messagingApiClient.showLoadingAnimation(request))
                .thenReturn(CompletableFuture.completedFuture(null));
        var adapter = new LineLoadingAnimationAdapter(messagingApiClient);

        adapter.beforeText(
                new UserSource("user-1"),
                new LineIntent.StyledTranslate("formal", "hello"));

        verify(messagingApiClient).showLoadingAnimation(request);
    }

    @Test
    void privateImageDisplaysLoadingAnimation() {
        var request = new ShowLoadingAnimationRequest("user-1", 60);
        when(messagingApiClient.showLoadingAnimation(request))
                .thenReturn(CompletableFuture.completedFuture(null));
        ScheduledExecutorService scheduler = org.mockito.Mockito.mock(ScheduledExecutorService.class);
        ScheduledFuture<?> renewal = org.mockito.Mockito.mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        doReturn(renewal).when(scheduler).scheduleAtFixedRate(task.capture(),
                org.mockito.ArgumentMatchers.eq(50L),
                org.mockito.ArgumentMatchers.eq(50L),
                org.mockito.ArgumentMatchers.eq(TimeUnit.SECONDS));
        var adapter = new LineLoadingAnimationAdapter(messagingApiClient, scheduler);

        LineLoadingSession session = adapter.startImage(new UserSource("user-1"));
        task.getValue().run();
        session.close();

        verify(messagingApiClient, times(2)).showLoadingAnimation(request);
        verify(renewal).cancel(false);
    }

    @Test
    void commandsAndGroupMessagesDoNotCallUnsupportedEndpoint() {
        var adapter = new LineLoadingAnimationAdapter(messagingApiClient);

        adapter.beforeText(
                new UserSource("user-1"),
                new LineIntent.UserCommand(LineIntent.UserAction.HELP, ""));
        adapter.beforeText(
                new GroupSource("group-1", "user-1"),
                new LineIntent.TranslateText("hello"));
        adapter.startImage(new GroupSource("group-1", "user-1"));

        verify(messagingApiClient, never()).showLoadingAnimation(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void lineFailureDoesNotEscapeIntoTranslationFlow() {
        var request = new ShowLoadingAnimationRequest("user-1", 20);
        when(messagingApiClient.showLoadingAnimation(request))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("LINE unavailable")));
        var adapter = new LineLoadingAnimationAdapter(messagingApiClient);

        adapter.beforeText(
                new UserSource("user-1"),
                new LineIntent.TranslateText("hello"));

        verify(messagingApiClient).showLoadingAnimation(request);
    }

    @Test
    void slowLineResponseDoesNotBlockTranslationFlow() {
        var request = new ShowLoadingAnimationRequest("user-1", 20);
        var pendingResponse = new CompletableFuture<Result<Object>>();
        when(messagingApiClient.showLoadingAnimation(request)).thenReturn(pendingResponse);
        var adapter = new LineLoadingAnimationAdapter(messagingApiClient);
        var executor = Executors.newSingleThreadExecutor();

        try {
            var invocation = executor.submit(() -> adapter.beforeText(
                    new UserSource("user-1"),
                    new LineIntent.TranslateText("hello")));

            assertThatCode(() -> invocation.get(250, TimeUnit.MILLISECONDS))
                    .doesNotThrowAnyException();
        } finally {
            pendingResponse.complete(null);
            executor.shutdownNow();
        }
    }
}
