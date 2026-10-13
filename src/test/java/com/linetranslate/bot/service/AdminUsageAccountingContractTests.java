package com.linetranslate.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

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

    @Test
    void statisticsAreCountedByTheDatabaseWithoutLoadingCollections() {
        when(translationRecordRepository.summarizeByCreatedAtBetween(any(), any()))
                .thenReturn(Optional.of(new TranslationRecordRepository.TranslationSummary(3, 1, 1_500)));
        when(translationRecordRepository.countPerAiProviderByCreatedAtBetween(any(), any()))
                .thenReturn(List.of(new TranslationRecordRepository.KeyedCount("openrouter", 3)));
        when(translationRecordRepository.countPerDayByCreatedAtBetween(any(), any(), anyString()))
                .thenReturn(List.of(new TranslationRecordRepository.KeyedCount("08-11", 3)));

        assertThat(adminService.getTodayStats())
                .contains("今日總翻譯次數：3\n", "文字翻譯：2 次\n", "圖片翻譯：1 次\n", "openrouter：3 次\n");
        assertThat(adminService.getSystemStats()).endsWith("-------------------\n08-11: 3\n");
        assertThat(adminService.getRecentUsers(10)).isEmpty();

        verify(userProfileRepository).countByLastInteractionAtAfter(any());
        verify(userProfileRepository).findByLastInteractionAtNotNullOrderByLastInteractionAtDesc(any(Limit.class));
        verify(userProfileRepository).findByLastInteractionAtIsNullOrderByFirstInteractionAtDesc(any(Limit.class));
        verify(userProfileRepository, never()).findAll();
        verify(translationRecordRepository, never()).findAll();
    }
}
