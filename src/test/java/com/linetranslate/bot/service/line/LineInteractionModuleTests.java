package com.linetranslate.bot.service.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linetranslate.bot.service.line.intent.AdminIntent;
import com.linetranslate.bot.service.line.intent.AdminIntentParser;
import com.linetranslate.bot.service.line.intent.LineIntent;
import com.linetranslate.bot.service.ocr.ImageTranslationReply;
import com.linetranslate.bot.service.ocr.ImageTranslationService;
import com.linetranslate.bot.service.translation.TranslationActionModule;
import com.linetranslate.bot.service.translation.TranslationResponse;
import com.linetranslate.bot.service.translation.TranslationService;

class LineInteractionModuleTests {

    private TranslationService translationService;
    private TranslationActionModule translationActionModule;
    private LineUserProfileService profileService;
    private AdminIntentParser adminIntentParser;
    private AdminInteractionModule adminInteractionModule;
    private ImageTranslationService imageTranslationService;
    private LineMessageRenderer renderer;
    private LineInteractionModule module;

    @BeforeEach
    void setUp() {
        translationService = mock(TranslationService.class);
        translationActionModule = mock(TranslationActionModule.class);
        profileService = mock(LineUserProfileService.class);
        adminIntentParser = mock(AdminIntentParser.class);
        adminInteractionModule = mock(AdminInteractionModule.class);
        imageTranslationService = mock(ImageTranslationService.class);
        renderer = mock(LineMessageRenderer.class);
        module = new LineInteractionModule(
                translationService,
                translationActionModule,
                profileService,
                imageTranslationService,
                adminIntentParser,
                adminInteractionModule,
                renderer);
    }

    @Test
    void imageResultCarriesEveryRenderedMessageInOrder() {
        ImageTranslationReply reply = ImageTranslationReply.text(TranslationResponse.plain("done"));
        TextMessage image = new TextMessage("image");
        TextMessage text = new TextMessage("text");
        when(imageTranslationService.processImageTranslationReply("U-user", "message-1"))
                .thenReturn(reply);
        when(renderer.imageResultMessages(reply)).thenReturn(List.of(image, text));

        assertThat(module.executeImage("U-user", "message-1")).containsExactly(image, text);
    }

    @Test
    void imageProcessingFailureIsOneFailureMessage() {
        TextMessage failure = new TextMessage("failed");
        when(imageTranslationService.processImageTranslationReply("U-user", "message-1"))
                .thenThrow(new IllegalStateException("boom"));
        when(renderer.imageFailure()).thenReturn(failure);

        assertThat(module.executeImage("U-user", "message-1")).containsExactly(failure);
    }

    @Test
    void translationAndStatusResultsCrossTheCentralRendererSeam() {
        TextMessage translationMessage = new TextMessage("rendered-translation");
        TextMessage statusMessage = new TextMessage("rendered-status");
        TranslationResponse translation = new TranslationResponse(
                "translated", "translated", "record-1", "en", "zh-TW");
        when(translationService.processTranslationResponse("U-user", "hello"))
                .thenReturn(translation);
        when(translationService.getUserStatus("U-user")).thenReturn("status-body");
        when(renderer.translation(translation)).thenReturn(translationMessage);
        when(renderer.status("status-body")).thenReturn(statusMessage);

        assertThat(module.execute("U-user", new LineIntent.TranslateText("hello")))
                .isSameAs(translationMessage);
        assertThat(module.execute("U-user", new LineIntent.UserCommand(
                LineIntent.UserAction.STATUS, "")))
                .isSameAs(statusMessage);

        verify(renderer).translation(translation);
        verify(renderer).status("status-body");
    }

    @Test
    void retranslationUsesAuthorizedIdempotentActionModule() {
        LineIntent.Retranslate intent = new LineIntent.Retranslate("record-1", "ja");
        TranslationResponse translated = new TranslationResponse(
                "こんにちは", "こんにちは", "record-2", "en", "ja");
        TextMessage rendered = new TextMessage("rendered");
        when(translationActionModule.execute("U-user", "record-1", "ja"))
                .thenReturn(translated);
        when(renderer.translation(translated)).thenReturn(rendered);

        assertThat(module.execute("U-user", intent)).isSameAs(rendered);

        verify(translationActionModule).execute("U-user", "record-1", "ja");
        verify(renderer).translation(translated);
    }

    @Test
    void oneTimeStyleAndResultRestyleUseTypedInteractionPaths() {
        TranslationResponse styled = new TranslationResponse(
                "您好", "您好", "record-2", "en", "zh-TW", "formal", "formal-v1");
        TextMessage rendered = new TextMessage("rendered");
        when(translationService.processStyledTranslationResponse(
                "U-user", "hello", "formal"))
                .thenReturn(styled);
        when(translationActionModule.executeStyle("U-user", "record-1", "formal"))
                .thenReturn(styled);
        when(renderer.translation(styled)).thenReturn(rendered);

        assertThat(module.execute("U-user", new LineIntent.StyledTranslate("formal", "hello")))
                .isSameAs(rendered);
        assertThat(module.execute("U-user", new LineIntent.Restyle("record-1", "formal")))
                .isSameAs(rendered);

        verify(translationService).processStyledTranslationResponse(
                "U-user", "hello", "formal");
        verify(translationActionModule).executeStyle("U-user", "record-1", "formal");
    }

    @Test
    void adminCommandUsesTypedAdminParserAndAuthorizedExecutionModule() {
        AdminIntent intent = AdminIntent.action(AdminIntent.Action.USAGE_SUMMARY, "", "");
        Message rendered = new TextMessage("usage");
        when(adminIntentParser.parse("usage summary")).thenReturn(intent);
        when(adminInteractionModule.execute("U-admin", intent)).thenReturn(rendered);

        assertThat(module.execute("U-admin", new LineIntent.AdminCommand("usage summary")))
                .isSameAs(rendered);

        verify(adminIntentParser).parse("usage summary");
        verify(adminInteractionModule).execute("U-admin", intent);
    }
}
