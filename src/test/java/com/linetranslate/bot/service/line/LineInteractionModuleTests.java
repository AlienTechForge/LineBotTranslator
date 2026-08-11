package com.linetranslate.bot.service.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linetranslate.bot.service.line.intent.AdminIntent;
import com.linetranslate.bot.service.line.intent.AdminIntentParser;
import com.linetranslate.bot.service.line.intent.LineIntent;
import com.linetranslate.bot.service.ocr.ImageTranslationService;
import com.linetranslate.bot.service.translation.TranslationService;

class LineInteractionModuleTests {

    private TranslationService translationService;
    private LineUserProfileService profileService;
    private AdminIntentParser adminIntentParser;
    private AdminInteractionModule adminInteractionModule;
    private LineMessageRenderer renderer;
    private LineInteractionModule module;

    @BeforeEach
    void setUp() {
        translationService = mock(TranslationService.class);
        profileService = mock(LineUserProfileService.class);
        adminIntentParser = mock(AdminIntentParser.class);
        adminInteractionModule = mock(AdminInteractionModule.class);
        renderer = mock(LineMessageRenderer.class);
        module = new LineInteractionModule(
                translationService,
                profileService,
                mock(ImageTranslationService.class),
                adminIntentParser,
                adminInteractionModule,
                renderer);
    }

    @Test
    void translationAndStatusResultsCrossTheCentralRendererSeam() {
        TextMessage translationMessage = new TextMessage("rendered-translation");
        TextMessage statusMessage = new TextMessage("rendered-status");
        when(translationService.processTranslationRequest("U-user", "hello"))
                .thenReturn("translated");
        when(translationService.getUserStatus("U-user")).thenReturn("status-body");
        when(renderer.translation("translated")).thenReturn(translationMessage);
        when(renderer.status("status-body")).thenReturn(statusMessage);

        assertThat(module.execute("U-user", new LineIntent.TranslateText("hello")))
                .isSameAs(translationMessage);
        assertThat(module.execute("U-user", new LineIntent.UserCommand(
                LineIntent.UserAction.STATUS, "")))
                .isSameAs(statusMessage);

        verify(renderer).translation("translated");
        verify(renderer).status("status-body");
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
