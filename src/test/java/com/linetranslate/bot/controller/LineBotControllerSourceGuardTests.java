package com.linetranslate.bot.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.webhook.model.GroupSource;
import com.linecorp.bot.webhook.model.ImageMessageContent;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.PostbackContent;
import com.linecorp.bot.webhook.model.PostbackEvent;
import com.linecorp.bot.webhook.model.RoomSource;
import com.linecorp.bot.webhook.model.TextMessageContent;
import com.linetranslate.bot.service.ai.AiModelCatalog;
import com.linetranslate.bot.service.line.LineInteractionModule;
import com.linetranslate.bot.service.line.LineLoadingFeedback;
import com.linetranslate.bot.service.line.LineMessageRenderer;
import com.linetranslate.bot.service.line.intent.LineIntent;
import com.linetranslate.bot.service.line.intent.LineIntentParser;

class LineBotControllerSourceGuardTests {

    private LineIntentParser parser;
    private LineInteractionModule interaction;
    private LineLoadingFeedback loading;
    private LineMessageRenderer renderer;
    private LineBotController controller;

    @BeforeEach
    void setUp() {
        parser = mock(LineIntentParser.class);
        interaction = mock(LineInteractionModule.class);
        loading = mock(LineLoadingFeedback.class);
        renderer = new LineMessageRenderer(mock(AiModelCatalog.class), () -> null, null, null);
        controller = new LineBotController(parser, interaction, loading, renderer);
    }

    @Test
    void groupTextWithoutUserIdRepliesWithGuidanceAndSkipsTranslation() {
        MessageEvent event = mock(MessageEvent.class);
        TextMessageContent content = mock(TextMessageContent.class);
        when(event.source()).thenReturn(new GroupSource("group-1", null));
        when(content.text()).thenReturn("hello");

        Message reply = controller.handleTextMessageEvent(event, content);

        assertUnidentifiedSenderReply(reply);
        verifyNoInteractions(parser, interaction, loading);
    }

    @Test
    void roomImageWithoutUserIdRepliesWithGuidanceAndSkipsImagePipeline() {
        MessageEvent event = mock(MessageEvent.class);
        ImageMessageContent content = mock(ImageMessageContent.class);
        when(event.source()).thenReturn(new RoomSource(null, "room-1"));
        when(content.id()).thenReturn("message-1");

        List<Message> reply = controller.handleImageMessageEvent(event, content);

        assertThat(reply).hasSize(1);
        assertUnidentifiedSenderReply(reply.get(0));
        verifyNoInteractions(parser, interaction, loading);
    }

    @Test
    void groupPostbackWithBlankUserIdRepliesWithGuidanceAndSkipsExecution() {
        PostbackEvent event = mock(PostbackEvent.class);
        PostbackContent content = mock(PostbackContent.class);
        when(event.source()).thenReturn(new GroupSource("group-1", " "));
        when(event.postback()).thenReturn(content);
        when(content.data()).thenReturn("command=%2Fhelp");

        Message reply = controller.handlePostbackEvent(event);

        assertUnidentifiedSenderReply(reply);
        verifyNoInteractions(parser, interaction, loading);
    }

    @Test
    void groupTextWithUserIdStillTranslates() {
        MessageEvent event = mock(MessageEvent.class);
        TextMessageContent content = mock(TextMessageContent.class);
        GroupSource source = new GroupSource("group-1", "user-1");
        LineIntent intent = new LineIntent.TranslateText("hello");
        TextMessage response = new TextMessage("translated");
        when(event.source()).thenReturn(source);
        when(content.text()).thenReturn("hello");
        when(parser.parseText("hello")).thenReturn(intent);
        when(interaction.execute("user-1", intent)).thenReturn(response);

        assertThat(controller.handleTextMessageEvent(event, content)).isSameAs(response);

        var order = inOrder(loading, interaction);
        order.verify(loading).beforeText(source, intent);
        order.verify(interaction).execute("user-1", intent);
    }

    private void assertUnidentifiedSenderReply(Message reply) {
        assertThat(reply).isEqualTo(renderer.unidentifiedSender());
        assertThat(((TextMessage) reply).text()).contains("無法辨識", "加為好友");
    }
}
