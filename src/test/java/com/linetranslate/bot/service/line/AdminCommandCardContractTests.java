package com.linetranslate.bot.service.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.linecorp.bot.messaging.model.FlexMessage;
import com.linecorp.bot.messaging.model.Message;
import com.linetranslate.bot.service.AdminService;
import com.linetranslate.bot.service.BroadcastResult;
import com.linetranslate.bot.service.line.intent.AdminIntentParser;
import com.linetranslate.bot.service.webhook.PushFallbackModule;

@ExtendWith(MockitoExtension.class)
class AdminCommandCardContractTests {

    private static final String ADMIN_ID = "U-admin";

    @Mock
    private AdminService adminService;

    private final AdminIntentParser parser = new AdminIntentParser();
    private AdminInteractionModule module;

    @BeforeEach
    void setUp() {
        module = new AdminInteractionModule(
                adminService, new AdminCardRenderer(), mock(PushFallbackModule.class));
    }

    /** The same parse-then-execute path {@code LineInteractionModule} runs for an admin command. */
    private Message handleCommand(String userId, String command) {
        return module.execute(userId, parser.parse(command));
    }

    @Test
    void unauthorizedInteractionReturnsCardWithoutAdminData() {
        when(adminService.isAdmin("U-intruder")).thenReturn(false);

        Message response = handleCommand("U-intruder", "config ocr off");

        assertThat(response).isInstanceOf(FlexMessage.class);
        assertThat(((FlexMessage) response).altText()).contains("沒有管理員權限");
        verify(adminService).isAdmin("U-intruder");
        verifyNoMoreInteractions(adminService);
    }

    @ParameterizedTest(name = "admin command {0} renders a card")
    @MethodSource("adminCommands")
    void everyAdminInteractionReturnsFlexCard(String command) {
        when(adminService.isAdmin(ADMIN_ID)).thenReturn(true);
        lenient().when(adminService.broadcastMessage(anyString()))
                .thenReturn(new BroadcastResult(1, 1, 0, false));

        Message response = handleCommand(ADMIN_ID, command);

        assertThat(response).isInstanceOf(FlexMessage.class);
    }

    @Test
    void userDetailIsRenderedAsCard() {
        when(adminService.isAdmin(ADMIN_ID)).thenReturn(true);
        when(adminService.getUserInfo("U-target")).thenReturn(Map.of(
                "userId", "U-target",
                "displayName", "Jason",
                "registrationTime", "2026-08-01",
                "lastActiveTime", "2026-08-10",
                "translationCount", 12,
                "imageTranslationCount", 2,
                "preferredLanguage", "zh-TW",
                "preferredChineseTargetLanguage", "en",
                "aiProvider", "openrouter",
                "preferredModel", "openai/gpt-4o-mini"));

        Message response = handleCommand(ADMIN_ID, "user U-target");

        assertThat(response).isInstanceOf(FlexMessage.class);
        assertThat(((FlexMessage) response).altText()).contains("用戶詳細資訊");
    }

    @Test
    void oversizedBroadcastIsRejectedBeforeSending() {
        when(adminService.isAdmin(ADMIN_ID)).thenReturn(true);

        Message response = handleCommand(ADMIN_ID, "broadcast " + "a".repeat(5001));

        assertThat(response).isInstanceOf(FlexMessage.class);
        assertThat(((FlexMessage) response).altText()).contains("廣播訊息過長");
        verify(adminService, never()).broadcastMessage(anyString());
    }

    @Test
    void broadcastCardStatesDeliveredAndFailedCounts() {
        when(adminService.isAdmin(ADMIN_ID)).thenReturn(true);
        when(adminService.broadcastMessage("系統通知"))
                .thenReturn(new BroadcastResult(3, 2, 1, false));

        Message response = handleCommand(ADMIN_ID, "broadcast 系統通知");

        assertThat(response).isInstanceOf(FlexMessage.class);
        assertThat(((FlexMessage) response).altText())
                .contains("廣播部分完成")
                .contains("已送達 2 位，失敗 1 位（共 3 位）");
    }

    @Test
    void configUpdatePassesOperatorAndRawValueToPersistentModuleSeam() {
        when(adminService.isAdmin(ADMIN_ID)).thenReturn(true);
        when(adminService.setOcrEnabled("maybe", ADMIN_ID)).thenReturn("invalid");

        handleCommand(ADMIN_ID, "config ocr maybe");

        verify(adminService).setOcrEnabled("maybe", ADMIN_ID);
    }

    private static Stream<String> adminCommands() {
        return Stream.of(
                "", "help", "isadmin",
                "broadcast 系統通知", "broadcast",
                "stats", "today", "users", "user", "user missing",
                "nickname", "nickname U-target", "nickname U-target Jason",
                "config", "config c2lang en", "config lang zh-TW",
                "config model openai/gpt-4o-mini",
                "config openrouter anthropic/claude-sonnet-4", "config ocr on",
                "config short-url on", "config image-text off", "config unknown",
                "usage", "usage day 2026-08-11", "usage month 2026-08",
                "usage provider openrouter", "usage model openai/gpt-4o-mini", "usage type image",
                "usage summary", "usage unknown",
                "add", "add U-target", "remove", "remove U-target", "unknown");
    }
}
