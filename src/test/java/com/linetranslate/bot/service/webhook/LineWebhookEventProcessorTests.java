package com.linetranslate.bot.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.linecorp.bot.messaging.model.ImageMessage;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.webhook.model.ImageMessageContent;
import com.linecorp.bot.webhook.model.MessageEvent;
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

    @Test
    void imageReplyKeepsEveryMessageInOrderUnderOneReplyToken() {
        LineBotController controller = mock(LineBotController.class);
        MessageEvent event = mock(MessageEvent.class);
        ImageMessageContent content = mock(ImageMessageContent.class);
        when(event.message()).thenReturn(content);
        when(event.replyToken()).thenReturn("reply-token");
        ImageMessage image = new ImageMessage(
                URI.create("https://img.example/original"), URI.create("https://img.example/preview"));
        TextMessage text = new TextMessage("recognised and translated");
        when(controller.handleImageMessageEvent(event, content)).thenReturn(List.of(image, text));

        WebhookReply reply = new LineWebhookEventProcessor(controller).process(event).orElseThrow();

        assertThat(reply.replyToken()).isEqualTo("reply-token");
        assertThat(reply.messages()).containsExactly(image, text);
    }

    @Test
    void emptyImageReplyProducesNoWebhookReply() {
        LineBotController controller = mock(LineBotController.class);
        MessageEvent event = mock(MessageEvent.class);
        ImageMessageContent content = mock(ImageMessageContent.class);
        when(event.message()).thenReturn(content);
        when(event.replyToken()).thenReturn("reply-token");
        when(controller.handleImageMessageEvent(event, content)).thenReturn(List.of());

        assertThat(new LineWebhookEventProcessor(controller).process(event)).isEmpty();
    }
}
