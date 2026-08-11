package com.linetranslate.bot.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.linetranslate.bot.service.line.AdminCardRenderer;

@ExtendWith(MockitoExtension.class)
class AdminControllerCardContractTests {

    private static final String ADMIN_ID = "U-admin";

    @Mock
    private AdminService adminService;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(adminService, new AdminCardRenderer());
    }

    @Test
    void unauthorizedInteractionReturnsCardWithoutAdminData() {
        when(adminService.isAdmin("U-intruder")).thenReturn(false);

        Message response = controller.handleCommand("U-intruder", "stats");

        assertThat(response).isInstanceOf(FlexMessage.class);
        assertThat(((FlexMessage) response).altText()).contains("沒有管理員權限");
    }

    @ParameterizedTest(name = "admin command {0} renders a card")
    @MethodSource("adminCommands")
    void everyAdminInteractionReturnsFlexCard(String command) {
        when(adminService.isAdmin(ADMIN_ID)).thenReturn(true);

        Message response = controller.handleCommand(ADMIN_ID, command);

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
                "preferredAiProvider", "openai"));

        Message response = controller.handleCommand(ADMIN_ID, "user U-target");

        assertThat(response).isInstanceOf(FlexMessage.class);
        assertThat(((FlexMessage) response).altText()).contains("用戶詳細資訊");
    }

    @Test
    void oversizedBroadcastIsRejectedBeforeSending() {
        when(adminService.isAdmin(ADMIN_ID)).thenReturn(true);

        Message response = controller.handleCommand(ADMIN_ID, "broadcast " + "a".repeat(5001));

        assertThat(response).isInstanceOf(FlexMessage.class);
        assertThat(((FlexMessage) response).altText()).contains("廣播訊息過長");
        verify(adminService, never()).broadcastMessage(anyString());
    }

    @Test
    void configUpdatePassesOperatorAndRawValueToPersistentModuleSeam() {
        when(adminService.isAdmin(ADMIN_ID)).thenReturn(true);
        when(adminService.setOcrEnabled("maybe", ADMIN_ID)).thenReturn("invalid");

        controller.handleCommand(ADMIN_ID, "config ocr maybe");

        verify(adminService).setOcrEnabled("maybe", ADMIN_ID);
    }

    private static Stream<String> adminCommands() {
        return Stream.of(
                "", "help", "isadmin",
                "broadcast 系統通知", "broadcast",
                "stats", "today", "users", "user", "user missing",
                "nickname", "nickname U-target", "nickname U-target Jason",
                "config", "config c2lang en", "config lang zh-TW",
                "config ai openai", "config openai gpt-4o",
                "config gemini gemini-1.5-pro", "config ocr on", "config unknown",
                "usage", "usage month 2026-08", "usage provider openai",
                "usage summary", "usage unknown",
                "add", "add U-target", "remove", "remove U-target", "unknown");
    }
}
