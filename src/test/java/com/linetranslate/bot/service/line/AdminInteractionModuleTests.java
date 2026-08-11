package com.linetranslate.bot.service.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linetranslate.bot.service.AdminService;
import com.linetranslate.bot.service.ai.AiModelPage;
import com.linetranslate.bot.service.line.intent.AdminIntent;

class AdminInteractionModuleTests {

    private AdminService adminService;
    private AdminCardRenderer renderer;
    private AdminInteractionModule module;

    @BeforeEach
    void setUp() {
        adminService = mock(AdminService.class);
        renderer = mock(AdminCardRenderer.class);
        module = new AdminInteractionModule(adminService, renderer);
    }

    @Test
    void authorizationPrecedesModelCatalogAccess() {
        Message denied = new TextMessage("denied");
        when(adminService.isAdmin("user")).thenReturn(false);
        when(renderer.accessDenied()).thenReturn(denied);

        assertThat(module.execute(
                "user", AdminIntent.action(AdminIntent.Action.MODELS, "", "")))
                .isSameAs(denied);

        verify(adminService).isAdmin("user");
        verify(adminService, never()).getAvailableModels("", 8);
        verifyNoMoreInteractions(adminService);
    }

    @Test
    void adminCanListAndChooseFromBoundedModels() {
        AiModelPage page = new AiModelPage(List.of(), 42, false);
        Message card = new TextMessage("models");
        when(adminService.isAdmin("admin")).thenReturn(true);
        when(adminService.getAvailableModels("anthropic", 8)).thenReturn(page);
        when(adminService.getOpenRouterDefaultModel()).thenReturn("openai/gpt-4.1-mini");
        when(renderer.modelSelection(page, "anthropic", "openai/gpt-4.1-mini"))
                .thenReturn(card);

        assertThat(module.execute(
                "admin", AdminIntent.action(AdminIntent.Action.MODELS, "anthropic", "")))
                .isSameAs(card);

        verify(adminService).getAvailableModels("anthropic", 8);
        verify(renderer).modelSelection(page, "anthropic", "openai/gpt-4.1-mini");
    }
}
