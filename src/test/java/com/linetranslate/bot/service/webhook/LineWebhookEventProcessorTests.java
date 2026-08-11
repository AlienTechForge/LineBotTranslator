package com.linetranslate.bot.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.webhook.model.PostbackContent;
import com.linecorp.bot.webhook.model.PostbackEvent;
import com.linetranslate.bot.controller.LineBotController;

class LineWebhookEventProcessorTests {

    @Test
    void replyablePostbackIsRoutedThroughTheIntentAdapter() {
        LineBotController controller = mock(LineBotController.class);
        PostbackEvent event = new PostbackEvent(
                null, 1L, null, "event-1", null, "reply-token",
                new PostbackContent("command=%2Fstatus", Map.of()));
        TextMessage response = new TextMessage("status");
        when(controller.handlePostbackEvent(event)).thenReturn(response);

        WebhookReply reply = new LineWebhookEventProcessor(controller).process(event).orElseThrow();

        assertThat(reply.replyToken()).isEqualTo("reply-token");
        assertThat(reply.messages()).containsExactly(response);
        verify(controller).handlePostbackEvent(event);
    }
}
