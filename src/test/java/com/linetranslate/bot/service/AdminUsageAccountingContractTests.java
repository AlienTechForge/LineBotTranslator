package com.linetranslate.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linetranslate.bot.config.OpenRouterConfig;
import com.linetranslate.bot.service.ai.AiModelCatalog;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.line.LineUserProfileService;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.settings.RuntimeSettingsModule;
import com.linetranslate.bot.service.usage.AiUsageAccountingModule;
import com.linetranslate.bot.service.usage.UsageQuery;
import com.linetranslate.bot.service.usage.UsageReport;
import com.linetranslate.bot.service.usage.UsageReportRenderer;

@ExtendWith(MockitoExtension.class)
class AdminUsageAccountingContractTests {

    @Mock private TranslationRecordRepository translationRecordRepository;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private MessagingApiClient messagingApiClient;
    @Mock private OpenRouterConfig openRouterConfig;
    @Mock private AiModelCatalog modelCatalog;
    @Mock private LineUserProfileService lineUserProfileService;
    @Mock private UserPreferencesModule userPreferencesModule;
    @Mock private RuntimeSettingsModule runtimeSettingsModule;
    @Mock private AiUsageAccountingModule usageAccountingModule;
    @Mock private UsageReportRenderer usageReportRenderer;

    private AdminService adminService;

    @BeforeEach
    void setUp() {
        adminService = new AdminService(
                translationRecordRepository,
                userProfileRepository,
                messagingApiClient,
                openRouterConfig,
                modelCatalog,
                lineUserProfileService,
                userPreferencesModule,
                runtimeSettingsModule,
                usageAccountingModule,
                usageReportRenderer);
    }

    @Test
    void summaryDelegatesToDatabaseAggregationWithoutLoadingTranslationRecords() {
        UsageReport report = UsageReport.empty();
        when(usageAccountingModule.report(UsageQuery.all())).thenReturn(report);
        when(usageReportRenderer.render(anyString(), any(UsageReport.class)))
                .thenReturn("rendered report");

        assertThat(adminService.getApiUsageSummary()).isEqualTo("rendered report");

        verify(usageAccountingModule).report(UsageQuery.all());
        verify(translationRecordRepository, never()).findAll();
    }
}
