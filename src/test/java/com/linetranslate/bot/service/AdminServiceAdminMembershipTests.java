package com.linetranslate.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linetranslate.bot.config.OpenRouterConfig;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiModelCatalog;
import com.linetranslate.bot.service.line.LineUserProfileService;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.settings.RuntimeSettings;
import com.linetranslate.bot.service.settings.RuntimeSettingsModule;
import com.linetranslate.bot.service.settings.RuntimeSettingsPersistenceException;
import com.linetranslate.bot.service.usage.AiUsageAccountingModule;
import com.linetranslate.bot.service.usage.UsageReportRenderer;

/**
 * Administrator membership is persisted through Runtime Settings; the deployment
 * environment list is only the bootstrap seed.
 */
@ExtendWith(MockitoExtension.class)
class AdminServiceAdminMembershipTests {

    private static final String ALICE = "U-alice";
    private static final String BOB = "U-bob";
    private static final String CAROL = "U-carol";

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

    /** What the stubbed settings source currently has persisted. */
    private final AtomicReference<List<String>> persisted = new AtomicReference<>(List.of());

    @BeforeEach
    void stubSettingsSource() {
        lenient().when(runtimeSettingsModule.current())
                .thenAnswer(invocation -> settings(persisted.get()));
        lenient().when(runtimeSettingsModule.updateAdminUserIds(anyList(), anyString()))
                .thenAnswer(invocation -> {
                    List<String> ids = invocation.getArgument(0);
                    persisted.set(List.copyOf(ids));
                    return settings(persisted.get());
                });
        for (String userId : List.of(ALICE, BOB, CAROL)) {
            lenient().when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.of(
                    UserProfile.builder().userId(userId).displayName("name-" + userId).build()));
        }
    }

    @Test
    void environmentSeedIsUsedWhileNothingIsPersisted() {
        AdminService service = serviceSeededWith(ALICE, BOB);

        assertThat(service.isAdmin(ALICE)).isTrue();
        assertThat(service.isAdmin(BOB)).isTrue();
        assertThat(service.isAdmin(CAROL)).isFalse();
        assertThat(service.isAdmin(null)).isFalse();
        assertThat(service.getAdminUsers()).containsExactly(ALICE, BOB);
    }

    @Test
    void blankAndDuplicateSeedEntriesAreIgnored() {
        AdminService service = serviceSeededWith(" " + ALICE + " ", "", ALICE, BOB);

        assertThat(service.getAdminUsers()).containsExactly(ALICE, BOB);
    }

    @Test
    void addingAnAdministratorCopiesTheSeedInAndTakesEffectImmediately() {
        AdminService service = serviceSeededWith(ALICE);

        String result = service.addAdmin(BOB, ALICE);

        assertThat(result).contains("成功添加管理員");
        verify(runtimeSettingsModule).updateAdminUserIds(List.of(ALICE, BOB), ALICE);
        assertThat(persisted.get()).containsExactly(ALICE, BOB);
        assertThat(service.isAdmin(BOB)).isTrue();
        assertThat(service.isAdmin(ALICE)).isTrue();
    }

    @Test
    void laterAdditionsExtendThePersistedListInsteadOfTheSeed() {
        AdminService service = serviceSeededWith(ALICE);
        service.addAdmin(BOB, ALICE);

        service.addAdmin(CAROL, BOB);

        verify(runtimeSettingsModule).updateAdminUserIds(List.of(ALICE, BOB, CAROL), BOB);
        assertThat(service.getAdminUsers()).containsExactly(ALICE, BOB, CAROL);
    }

    @Test
    void removingAnAdministratorPersistsAndTakesEffectImmediately() {
        AdminService service = serviceSeededWith(ALICE, BOB);

        String result = service.removeAdmin(BOB, ALICE);

        assertThat(result).contains("成功移除管理員");
        verify(runtimeSettingsModule).updateAdminUserIds(List.of(ALICE), ALICE);
        assertThat(persisted.get()).containsExactly(ALICE);
        assertThat(service.isAdmin(BOB)).isFalse();
        assertThat(service.isAdmin(ALICE)).isTrue();
    }

    @Test
    void lastAdministratorCannotBeRemovedFromTheSeed() {
        AdminService service = serviceSeededWith(ALICE);

        String result = service.removeAdmin(ALICE, ALICE);

        assertThat(result).contains("無法移除最後一個管理員");
        verify(runtimeSettingsModule, never()).updateAdminUserIds(anyList(), anyString());
        assertThat(service.isAdmin(ALICE)).isTrue();
    }

    @Test
    void lastAdministratorCannotBeRemovedFromThePersistedList() {
        persisted.set(List.of(BOB));
        AdminService service = serviceSeededWith(ALICE, BOB);

        String result = service.removeAdmin(BOB, BOB);

        assertThat(result).contains("無法移除最後一個管理員");
        verify(runtimeSettingsModule, never()).updateAdminUserIds(anyList(), anyString());
        assertThat(service.getAdminUsers()).containsExactly(BOB);
    }

    @Test
    void changingTheEnvironmentAfterPersistenceDoesNotOverrideTheStoredList() {
        persisted.set(List.of(ALICE, BOB));
        AdminService service = serviceSeededWith(CAROL);

        assertThat(service.isAdmin(CAROL)).isFalse();
        assertThat(service.isAdmin(ALICE)).isTrue();
        assertThat(service.isAdmin(BOB)).isTrue();
        assertThat(service.getAdminUsers()).containsExactly(ALICE, BOB);
    }

    @Test
    void removalSurvivesARestartThatStillCarriesTheOriginalSeed() {
        serviceSeededWith(ALICE, BOB).removeAdmin(BOB, ALICE);

        AdminService restarted = serviceSeededWith(ALICE, BOB);

        assertThat(restarted.isAdmin(BOB)).isFalse();
        assertThat(restarted.getAdminUsers()).containsExactly(ALICE);
    }

    @Test
    void additionSurvivesARestart() {
        serviceSeededWith(ALICE).addAdmin(BOB, ALICE);

        AdminService restarted = serviceSeededWith(ALICE);

        assertThat(restarted.isAdmin(BOB)).isTrue();
    }

    @Test
    void failedPersistenceLeavesMembershipUnchanged() {
        doThrow(new RuntimeSettingsPersistenceException("mongo unavailable"))
                .when(runtimeSettingsModule).updateAdminUserIds(anyList(), anyString());
        AdminService service = serviceSeededWith(ALICE);

        String result = service.addAdmin(BOB, ALICE);

        assertThat(result).contains("添加管理員失敗").contains("未能保存");
        assertThat(service.isAdmin(BOB)).isFalse();
        assertThat(service.getAdminUsers()).containsExactly(ALICE);
    }

    @Test
    void unknownUsersAreRejectedBeforeAnythingIsPersisted() {
        when(userProfileRepository.findByUserId("U-ghost")).thenReturn(Optional.empty());
        AdminService service = serviceSeededWith(ALICE);

        assertThat(service.addAdmin("U-ghost", ALICE)).contains("找不到用戶");
        assertThat(service.removeAdmin("U-ghost", ALICE)).contains("找不到用戶");

        verify(runtimeSettingsModule, never()).updateAdminUserIds(anyList(), anyString());
    }

    @Test
    void repeatedOrIrrelevantChangesDoNotWrite() {
        AdminService service = serviceSeededWith(ALICE, BOB);

        assertThat(service.addAdmin(BOB, ALICE)).contains("已經是管理員");
        assertThat(service.removeAdmin(CAROL, ALICE)).contains("不是管理員");

        verify(runtimeSettingsModule, never()).updateAdminUserIds(anyList(), anyString());
    }

    private AdminService serviceSeededWith(String... seed) {
        AdminService service = new AdminService(
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
        ReflectionTestUtils.setField(service, "adminUsers", List.of(seed));
        return service;
    }

    private static RuntimeSettings settings(List<String> adminUserIds) {
        return new RuntimeSettings(
                "en", "zh-TW", "openai/gpt-4o-mini", true, false, true, adminUserIds,
                RuntimeSettingsModule.SCHEMA_VERSION, 0, null, null,
                adminUserIds.isEmpty()
                        ? RuntimeSettings.Source.DEPLOYMENT_DEFAULTS
                        : RuntimeSettings.Source.PERSISTED);
    }
}
