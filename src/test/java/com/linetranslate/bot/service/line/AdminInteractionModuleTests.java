package com.linetranslate.bot.service.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.MessageQuotaResponse;
import com.linecorp.bot.messaging.model.QuotaConsumptionResponse;
import com.linecorp.bot.messaging.model.QuotaType;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linetranslate.bot.service.AdminService;
import com.linetranslate.bot.service.ai.AiModelPage;
import com.linetranslate.bot.service.line.intent.AdminIntent;
import com.linetranslate.bot.service.webhook.PushFallbackModule;
import com.linetranslate.bot.service.webhook.PushFallbackProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

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
    void adminSeesTheMonthlyPushQuotaAndTodaysFallbackUsage() {
        MessagingApiClient client = quotaClient(QuotaType.LIMITED, 1000L, 250L);
        PushFallbackProperties properties = new PushFallbackProperties();
        properties.setDailyLimit(50);
        String body = quotaBody(client, properties);

        assertThat(body).contains(
                "額度類型：有限額",
                "每月上限：1,000 則",
                "本月已用：250 則",
                "本月剩餘：750 則",
                "reply token 過期備援推播：已啟用",
                "今日備援推播：0 / 50");
    }

    @Test
    void quotaWithoutMonthlyLimitIsShownAsUnlimited() {
        MessagingApiClient client = quotaClient(QuotaType.NONE, null, 1234L);
        PushFallbackProperties properties = new PushFallbackProperties();
        properties.setEnabled(false);
        String body = quotaBody(client, properties);

        assertThat(body).contains(
                "額度類型：無上限",
                "每月上限：無上限",
                "本月已用：1,234 則",
                "本月剩餘：無上限",
                "reply token 過期備援推播：已停用",
                "今日備援推播：0 / 100");
    }

    @Test
    void overspentQuotaNeverShowsANegativeRemainder() {
        MessagingApiClient client = quotaClient(QuotaType.LIMITED, 200L, 230L);

        assertThat(quotaBody(client, new PushFallbackProperties())).contains("本月剩餘：0 則");
    }

    @Test
    void quotaCommandIsAuthorizedBeforeLineIsQueried() {
        MessagingApiClient client = quotaClient(QuotaType.LIMITED, 1000L, 250L);
        module = new AdminInteractionModule(
                adminService, renderer, pushFallbackModule(client, new PushFallbackProperties()));
        Message denied = new TextMessage("denied");
        when(adminService.isAdmin("user")).thenReturn(false);
        when(renderer.accessDenied()).thenReturn(denied);

        assertThat(module.execute("user", AdminIntent.action(AdminIntent.Action.QUOTA, "", "")))
                .isSameAs(denied);

        verifyNoInteractions(client);
    }

    @Test
    void lineFailureIsReportedWithoutLeakingDetails() {
        MessagingApiClient client = mock(MessagingApiClient.class);
        when(client.getMessageQuota()).thenReturn(CompletableFuture.failedFuture(new IOException("secret")));
        module = new AdminInteractionModule(
                adminService, renderer, pushFallbackModule(client, new PushFallbackProperties()));
        Message error = new TextMessage("error");
        when(adminService.isAdmin("admin")).thenReturn(true);
        when(renderer.error("推播額度失敗", "無法取得 LINE 推播額度，請稍後再試。")).thenReturn(error);

        assertThat(module.execute("admin", AdminIntent.action(AdminIntent.Action.QUOTA, "", "")))
                .isSameAs(error);
    }

    private String quotaBody(MessagingApiClient client, PushFallbackProperties properties) {
        module = new AdminInteractionModule(
                adminService, renderer, pushFallbackModule(client, properties));
        Message card = new TextMessage("quota");
        when(adminService.isAdmin("admin")).thenReturn(true);
        when(renderer.info(eq("推播額度"), anyString())).thenReturn(card);

        assertThat(module.execute("admin", AdminIntent.action(AdminIntent.Action.QUOTA, "", "")))
                .isSameAs(card);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(renderer).info(eq("推播額度"), body.capture());
        return body.getValue();
    }

    private static PushFallbackModule pushFallbackModule(
            MessagingApiClient client, PushFallbackProperties properties) {
        return new PushFallbackModule(client, properties, new SimpleMeterRegistry());
    }

    private static MessagingApiClient quotaClient(QuotaType type, Long limit, long consumed) {
        MessagingApiClient client = mock(MessagingApiClient.class);
        when(client.getMessageQuota()).thenReturn(CompletableFuture.completedFuture(
                new Result<>("request", null, new MessageQuotaResponse(type, limit))));
        when(client.getMessageQuotaConsumption()).thenReturn(CompletableFuture.completedFuture(
                new Result<>("request", null, new QuotaConsumptionResponse(consumed))));
        return client;
    }
}
