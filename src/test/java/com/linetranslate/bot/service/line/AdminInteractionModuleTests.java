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
import com.linetranslate.bot.service.BroadcastResult;
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

    @Test
    void authorizedAdminCanToggleImageProxyOutput() {
        Message card = new TextMessage("updated");
        when(adminService.isAdmin("admin")).thenReturn(true);
        when(adminService.setShortUrlEnabled("on", "admin"))
                .thenReturn("✅ 已啟用圖片短網址直接輸出");
        when(renderer.info("設定結果", "✅ 已啟用圖片短網址直接輸出"))
                .thenReturn(card);

        assertThat(module.execute("admin", AdminIntent.action(
                AdminIntent.Action.CONFIG_SHORT_URL, "on", "")))
                .isSameAs(card);

        verify(adminService).setShortUrlEnabled("on", "admin");
    }

    @Test
    void administratorMembershipChangesCarryTheActingAdministrator() {
        Message added = new TextMessage("added");
        Message removed = new TextMessage("removed");
        when(adminService.isAdmin("admin")).thenReturn(true);
        when(adminService.addAdmin("U-new", "admin")).thenReturn("成功添加管理員");
        when(adminService.removeAdmin("U-old", "admin")).thenReturn("成功移除管理員");
        when(renderer.info("新增管理員", "成功添加管理員")).thenReturn(added);
        when(renderer.info("移除管理員", "成功移除管理員")).thenReturn(removed);

        assertThat(module.execute("admin", AdminIntent.action(
                AdminIntent.Action.ADD_ADMIN, "U-new", ""))).isSameAs(added);
        assertThat(module.execute("admin", AdminIntent.action(
                AdminIntent.Action.REMOVE_ADMIN, "U-old", ""))).isSameAs(removed);

        verify(adminService).addAdmin("U-new", "admin");
        verify(adminService).removeAdmin("U-old", "admin");
    }

    @Test
    void authorizedAdminCanToggleImageTextReply() {
        Message card = new TextMessage("updated");
        when(adminService.isAdmin("admin")).thenReturn(true);
        when(adminService.setImageTextReplyEnabled("off", "admin"))
                .thenReturn("✅ 已禁用圖片翻譯附帶文字譯文");
        when(renderer.info("設定結果", "✅ 已禁用圖片翻譯附帶文字譯文"))
                .thenReturn(card);

        assertThat(module.execute("admin", AdminIntent.action(
                AdminIntent.Action.CONFIG_IMAGE_TEXT_REPLY, "off", "")))
                .isSameAs(card);

        verify(adminService).setImageTextReplyEnabled("off", "admin");
    }

    @Test
    void nonAdminCannotToggleImageTextReply() {
        Message denied = new TextMessage("denied");
        when(adminService.isAdmin("user")).thenReturn(false);
        when(renderer.accessDenied()).thenReturn(denied);

        assertThat(module.execute("user", AdminIntent.action(
                AdminIntent.Action.CONFIG_IMAGE_TEXT_REPLY, "off", "")))
                .isSameAs(denied);

        verify(adminService, never()).setImageTextReplyEnabled("off", "user");
    }

    @Test
    void configListingAdvertisesTheImageTextSwitch() {
        Message card = new TextMessage("config");
        when(adminService.isAdmin("admin")).thenReturn(true);
        when(adminService.getSystemConfig()).thenReturn("config");
        when(renderer.card(
                org.mockito.ArgumentMatchers.eq("系統設定"),
                org.mockito.ArgumentMatchers.contains("/admin config image-text [on|off]"),
                org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(card);

        assertThat(module.execute("admin", AdminIntent.action(
                AdminIntent.Action.CONFIG_SHOW, "", "")))
                .isSameAs(card);
    }

    @Test
    void broadcastWithEveryPushAcceptedReportsSuccess() {
        Message card = new TextMessage("sent");
        when(adminService.isAdmin("admin")).thenReturn(true);
        when(adminService.broadcastMessage("系統通知"))
                .thenReturn(new BroadcastResult(3, 3, 0, false));
        when(renderer.success("廣播完成", "已送達 3 位，失敗 0 位（共 3 位）。\n\n系統通知"))
                .thenReturn(card);

        assertThat(module.execute("admin", broadcast("系統通知"))).isSameAs(card);
    }

    @Test
    void broadcastWithAFailedPushReportsDeliveredAndFailedCounts() {
        Message card = new TextMessage("partial");
        when(adminService.isAdmin("admin")).thenReturn(true);
        when(adminService.broadcastMessage("系統通知"))
                .thenReturn(new BroadcastResult(3, 2, 1, false));
        when(renderer.info("廣播部分完成", "已送達 2 位，失敗 1 位（共 3 位）。\n\n系統通知"))
                .thenReturn(card);

        assertThat(module.execute("admin", broadcast("系統通知"))).isSameAs(card);
    }

    @Test
    void broadcastWithEveryPushFailedReportsFailure() {
        Message card = new TextMessage("failed");
        when(adminService.isAdmin("admin")).thenReturn(true);
        when(adminService.broadcastMessage("系統通知"))
                .thenReturn(new BroadcastResult(2, 0, 2, false));
        when(renderer.error("廣播失敗", "已送達 0 位，失敗 2 位（共 2 位）。\n\n系統通知"))
                .thenReturn(card);

        assertThat(module.execute("admin", broadcast("系統通知"))).isSameAs(card);
    }

    @Test
    void testModeBroadcastSaysNothingWasActuallySent() {
        Message card = new TextMessage("simulated");
        when(adminService.isAdmin("admin")).thenReturn(true);
        when(adminService.broadcastMessage("系統通知"))
                .thenReturn(new BroadcastResult(2, 2, 0, true));
        when(renderer.success(
                "廣播完成", "測試模式，未實際發送：模擬送達 2 位，失敗 0 位（共 2 位）。\n\n系統通知"))
                .thenReturn(card);

        assertThat(module.execute("admin", broadcast("系統通知"))).isSameAs(card);
    }

    private static AdminIntent broadcast(String message) {
        return AdminIntent.action(AdminIntent.Action.BROADCAST, message, "");
    }
}
