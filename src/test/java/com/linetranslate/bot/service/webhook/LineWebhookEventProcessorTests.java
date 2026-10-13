package com.linetranslate.bot.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.bot.messaging.model.ImageMessage;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.webhook.model.GroupSource;
import com.linecorp.bot.webhook.model.ImageMessageContent;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.PostbackContent;
import com.linecorp.bot.webhook.model.PostbackEvent;
import com.linecorp.bot.webhook.model.RoomSource;
import com.linecorp.bot.webhook.model.Source;
import com.linecorp.bot.webhook.model.TextMessageContent;
import com.linecorp.bot.webhook.model.UserSource;
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
        assertThat(reply.pushTarget()).isNull();
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

    @ParameterizedTest
    @MethodSource("sources")
    void textReplyCarriesTheChatItCanBePushedTo(Source source, String expectedTarget) {
        LineBotController controller = mock(LineBotController.class);
        TextMessageContent text = new TextMessageContent("m-1", "hello", null, null, null, null, null);
        MessageEvent event = new MessageEvent(source, 1L, null, "event-1", null, "reply-token", text);
        TextMessage response = new TextMessage("hola");
        when(controller.handleTextMessageEvent(event, text)).thenReturn(response);

        WebhookReply reply = new LineWebhookEventProcessor(controller).process(event).orElseThrow();

        assertThat(reply.replyToken()).isEqualTo("reply-token");
        assertThat(reply.messages()).containsExactly(response);
        assertThat(reply.pushTarget()).isEqualTo(expectedTarget);
    }

    @ParameterizedTest
    @MethodSource("sources")
    void postbackReplyCarriesTheChatItCanBePushedTo(Source source, String expectedTarget) {
        LineBotController controller = mock(LineBotController.class);
        PostbackEvent event = new PostbackEvent(
                source, 1L, null, "event-1", null, "reply-token",
                new PostbackContent("command=%2Fstatus", Map.of()));
        TextMessage response = new TextMessage("status");
        when(controller.handlePostbackEvent(event)).thenReturn(response);

        WebhookReply reply = new LineWebhookEventProcessor(controller).process(event).orElseThrow();

        assertThat(reply.pushTarget()).isEqualTo(expectedTarget);
    }

    @ParameterizedTest
    @MethodSource("sources")
    void imageReplyCarriesTheChatItCanBePushedTo(Source source, String expectedTarget) {
        LineBotController controller = mock(LineBotController.class);
        ImageMessageContent image = new ImageMessageContent("m-2", null, null, null, null);
        MessageEvent event = new MessageEvent(source, 1L, null, "event-2", null, "reply-token", image);
        TextMessage response = new TextMessage("translated");
        when(controller.handleImageMessageEvent(event, image)).thenReturn(List.of(response));

        WebhookReply reply = new LineWebhookEventProcessor(controller).process(event).orElseThrow();

        assertThat(reply.messages()).containsExactly(response);
        assertThat(reply.pushTarget()).isEqualTo(expectedTarget);
    }

    private static Stream<Arguments> sources() {
        return Stream.of(
                Arguments.of(new UserSource("U-user"), "U-user"),
                Arguments.of(new GroupSource("C-group", "U-member"), "C-group"),
                Arguments.of(new RoomSource("U-member", "R-room"), "R-room"));
    }
}
