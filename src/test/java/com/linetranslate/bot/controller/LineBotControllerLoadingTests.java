package com.linetranslate.bot.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.webhook.model.ImageMessageContent;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import com.linecorp.bot.webhook.model.UserSource;
import com.linetranslate.bot.service.line.LineInteractionModule;
import com.linetranslate.bot.service.line.LineLoadingFeedback;
import com.linetranslate.bot.service.line.intent.LineIntent;
import com.linetranslate.bot.service.line.intent.LineIntentParser;

class LineBotControllerLoadingTests {

    @Test
    void textLoadingStartsBeforeTranslationExecution() {
        LineIntentParser parser = mock(LineIntentParser.class);
        LineInteractionModule interaction = mock(LineInteractionModule.class);
        LineLoadingFeedback loading = mock(LineLoadingFeedback.class);
        MessageEvent event = mock(MessageEvent.class);
        TextMessageContent content = mock(TextMessageContent.class);
        UserSource source = new UserSource("user-1");
        LineIntent intent = new LineIntent.TranslateText("hello");
        TextMessage response = new TextMessage("translated");
        when(event.source()).thenReturn(source);
        when(content.text()).thenReturn("hello");
        when(parser.parseText("hello")).thenReturn(intent);
        when(interaction.execute("user-1", intent)).thenReturn(response);
        var controller = new LineBotController(parser, interaction, loading);

        assertThat(controller.handleTextMessageEvent(event, content)).isSameAs(response);

        var order = inOrder(loading, interaction);
        order.verify(loading).beforeText(source, intent);
        order.verify(interaction).execute("user-1", intent);
    }

    @Test
    void imageLoadingStartsBeforeOcrExecution() {
        LineIntentParser parser = mock(LineIntentParser.class);
        LineInteractionModule interaction = mock(LineInteractionModule.class);
        LineLoadingFeedback loading = mock(LineLoadingFeedback.class);
        MessageEvent event = mock(MessageEvent.class);
        ImageMessageContent content = mock(ImageMessageContent.class);
        UserSource source = new UserSource("user-1");
        TextMessage response = new TextMessage("translated");
        when(event.source()).thenReturn(source);
        when(content.id()).thenReturn("message-1");
        when(interaction.executeImage("user-1", "message-1")).thenReturn(response);
        var controller = new LineBotController(parser, interaction, loading);

        assertThat(controller.handleImageMessageEvent(event, content)).isSameAs(response);

        var order = inOrder(loading, interaction);
        order.verify(loading).beforeImage(source);
        order.verify(interaction).executeImage("user-1", "message-1");
    }
}
