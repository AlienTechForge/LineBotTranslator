package com.linetranslate.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linetranslate.bot.config.OpenRouterConfig;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiModelCatalog;
import com.linetranslate.bot.service.ai.AiModelPage;
import com.linetranslate.bot.service.line.LineUserProfileService;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.settings.InvalidRuntimeSettingException;
import com.linetranslate.bot.service.settings.RuntimeSettingKey;
import com.linetranslate.bot.service.settings.RuntimeSettings;
import com.linetranslate.bot.service.settings.RuntimeSettingsModule;
import com.linetranslate.bot.service.settings.RuntimeSettingsPersistenceException;
import com.linetranslate.bot.service.usage.AiUsageAccountingModule;
import com.linetranslate.bot.service.usage.UsageReportRenderer;

class AdminImageTextReplySettingTests {

    private RuntimeSettingsModule runtimeSettingsModule;
    private AiModelCatalog modelCatalog;
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        runtimeSettingsModule = mock(RuntimeSettingsModule.class);
        modelCatalog = mock(AiModelCatalog.class);
        adminService = new AdminService(
                mock(TranslationRecordRepository.class),
                mock(UserProfileRepository.class),
                mock(MessagingApiClient.class),
                mock(OpenRouterConfig.class),
                modelCatalog,
                mock(LineUserProfileService.class),
                mock(UserPreferencesModule.class),
                runtimeSettingsModule,
                mock(AiUsageAccountingModule.class),
                mock(UsageReportRenderer.class));
        ReflectionTestUtils.setField(adminService, "adminUsers", List.of());
    }

    @Test
    void switchingOffPersistsThroughRuntimeSettingsAndConfirmsInTraditionalChinese() {
        when(runtimeSettingsModule.update(
                RuntimeSettingKey.IMAGE_TEXT_REPLY_ENABLED, "off", "admin"))
                .thenReturn(settings(false));

        assertThat(adminService.setImageTextReplyEnabled("off", "admin"))
                .isEqualTo("✅ 已禁用圖片翻譯附帶文字譯文");

        verify(runtimeSettingsModule).update(
                RuntimeSettingKey.IMAGE_TEXT_REPLY_ENABLED, "off", "admin");
    }

    @Test
    void switchingOnConfirmsTheEffectiveValue() {
        when(runtimeSettingsModule.update(
                RuntimeSettingKey.IMAGE_TEXT_REPLY_ENABLED, "on", "admin"))
                .thenReturn(settings(true));

        assertThat(adminService.setImageTextReplyEnabled("on", "admin"))
                .isEqualTo("✅ 已啟用圖片翻譯附帶文字譯文");
    }

    @Test
    void invalidValueAndPersistenceFailureAreReportedWithoutLeakingDetails() {
        when(runtimeSettingsModule.update(
                RuntimeSettingKey.IMAGE_TEXT_REPLY_ENABLED, "maybe", "admin"))
                .thenThrow(new InvalidRuntimeSettingException("OCR value must be on or off"));
        when(runtimeSettingsModule.update(
                RuntimeSettingKey.IMAGE_TEXT_REPLY_ENABLED, "off", "admin"))
                .thenThrow(new RuntimeSettingsPersistenceException("mongo down"));

        assertThat(adminService.setImageTextReplyEnabled("maybe", "admin"))
                .isEqualTo("❌ 無效的設定值，請使用 on 或 off");
        assertThat(adminService.setImageTextReplyEnabled("off", "admin"))
                .isEqualTo("❌ 設置未能保存，請稍後再試");
    }

    @Test
    void systemConfigListingShowsTheCurrentSwitchState() {
        when(modelCatalog.list("", 0)).thenReturn(new AiModelPage(List.of(), 0, false));
        when(runtimeSettingsModule.current()).thenReturn(settings(false));

        assertThat(adminService.getSystemConfig()).contains("• 圖片翻譯附帶文字譯文: 已禁用");

        when(runtimeSettingsModule.current()).thenReturn(settings(true));

        assertThat(adminService.getSystemConfig()).contains("• 圖片翻譯附帶文字譯文: 已啟用");
    }

    private static RuntimeSettings settings(boolean imageTextReplyEnabled) {
        return new RuntimeSettings(
                "en", "zh-TW", "openai/gpt-4o-mini", true, false, imageTextReplyEnabled,
                4, 1, null, null, RuntimeSettings.Source.PERSISTED);
    }
}
