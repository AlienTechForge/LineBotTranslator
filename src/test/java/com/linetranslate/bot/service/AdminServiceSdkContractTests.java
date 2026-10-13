package com.linetranslate.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linetranslate.bot.config.AppConfig;
import com.linetranslate.bot.config.OpenRouterConfig;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.line.LineUserProfileService;
import com.linetranslate.bot.service.ai.AiModelCatalog;
import com.linetranslate.bot.service.preference.UserPreferencesModule;

@ExtendWith(MockitoExtension.class)
class AdminServiceSdkContractTests {

    @Mock
    private TranslationRecordRepository translationRecordRepository;
    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private MessagingApiClient messagingApiClient;
    @Mock
    private AppConfig appConfig;
    @Mock
    private OpenRouterConfig openRouterConfig;
    @Mock
    private AiModelCatalog modelCatalog;
    @Mock
    private LineUserProfileService lineUserProfileService;
    @Mock
    private UserPreferencesModule userPreferencesModule;

    @Test
    void broadcastBuildsSdkTenPushMessageRequest() throws Exception {
        String userId = "U0123456789abcdef";
        when(userProfileRepository.findAll()).thenReturn(List.of(
                UserProfile.builder().userId(userId).displayName("Jason").build()));
        when(messagingApiClient.pushMessage(any(UUID.class), any(PushMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        AdminService service = new AdminService(
                translationRecordRepository,
                userProfileRepository,
                messagingApiClient,
                appConfig,
                openRouterConfig,
                modelCatalog,
                lineUserProfileService,
                userPreferencesModule);

        assertThat(service.broadcastMessage("系統通知"))
                .isEqualTo(new BroadcastResult(1, 1, 0, false));

        ArgumentCaptor<PushMessageRequest> requestCaptor =
                ArgumentCaptor.forClass(PushMessageRequest.class);
        verify(messagingApiClient).pushMessage(any(UUID.class), requestCaptor.capture());
        PushMessageRequest request = requestCaptor.getValue();
        assertThat(request.to()).isEqualTo(userId);
        assertThat(request.notificationDisabled()).isFalse();
        assertThat(request.messages()).singleElement().isInstanceOfSatisfying(
                TextMessage.class,
                message -> assertThat(message.text()).isEqualTo("系統通知"));
    }

    @Test
    void broadcastReportsEveryUserAsSentWhenAllPushesSucceed() {
        when(userProfileRepository.findAll()).thenReturn(List.of(
                UserProfile.builder().userId("U-first").build(),
                UserProfile.builder().userId("U-second").build()));
        when(messagingApiClient.pushMessage(any(UUID.class), any(PushMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThat(newService().broadcastMessage("系統通知"))
                .isEqualTo(new BroadcastResult(2, 2, 0, false));
        verify(messagingApiClient, times(2))
                .pushMessage(any(UUID.class), any(PushMessageRequest.class));
    }

    @Test
    void broadcastCountsAFailedPushAsFailedAndKeepsSending() {
        when(userProfileRepository.findAll()).thenReturn(List.of(
                UserProfile.builder().userId("U-blocked").build(),
                UserProfile.builder().userId("U-reachable").build()));
        when(messagingApiClient.pushMessage(any(UUID.class), any(PushMessageRequest.class)))
                .thenReturn(
                        CompletableFuture.failedFuture(new IllegalStateException("blocked")),
                        CompletableFuture.completedFuture(null));

        assertThat(newService().broadcastMessage("系統通知"))
                .isEqualTo(new BroadcastResult(2, 1, 1, false));
        verify(messagingApiClient, times(2))
                .pushMessage(any(UUID.class), any(PushMessageRequest.class));
    }

    @Test
    void broadcastInTestModeCountsSimulatedSendsWithoutPushing() {
        when(userProfileRepository.findAll()).thenReturn(List.of(
                UserProfile.builder().userId("U-first").build(),
                UserProfile.builder().userId("U-second").build()));
        AdminService service = newService();
        ReflectionTestUtils.setField(service, "broadcastTestMode", true);

        assertThat(service.broadcastMessage("系統通知"))
                .isEqualTo(new BroadcastResult(2, 2, 0, true));
        verifyNoInteractions(messagingApiClient);
    }

    @Test
    void broadcastStopsAndRestoresTheInterruptFlagWhenInterruptedWhileWaitingForAPush() {
        when(userProfileRepository.findAll()).thenReturn(List.of(
                UserProfile.builder().userId("U-first").build(),
                UserProfile.builder().userId("U-second").build(),
                UserProfile.builder().userId("U-third").build()));
        when(messagingApiClient.pushMessage(any(UUID.class), any(PushMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null))
                .thenAnswer(invocation -> {
                    Thread.currentThread().interrupt();
                    return new CompletableFuture<>();
                });

        try {
            // The second push was in flight when the interrupt arrived, so its outcome
            // is unknown: it and the third user are remaining, not failed.
            assertThat(newService().broadcastMessage("系統通知"))
                    .isEqualTo(new BroadcastResult(1, 1, 0, false, 2));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(messagingApiClient, times(2))
                    .pushMessage(any(UUID.class), any(PushMessageRequest.class));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void broadcastStopsBeforeTheNextPushWhenTheFlagIsSetBetweenPushes() {
        when(userProfileRepository.findAll()).thenReturn(List.of(
                UserProfile.builder().userId("U-first").build(),
                UserProfile.builder().userId("U-second").build(),
                UserProfile.builder().userId("U-third").build()));
        // An already-completed future never checks the flag, so only the loop itself can notice.
        when(messagingApiClient.pushMessage(any(UUID.class), any(PushMessageRequest.class)))
                .thenAnswer(invocation -> {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.completedFuture(null);
                });

        try {
            assertThat(newService().broadcastMessage("系統通知"))
                    .isEqualTo(new BroadcastResult(1, 1, 0, false, 2));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(messagingApiClient, times(1))
                    .pushMessage(any(UUID.class), any(PushMessageRequest.class));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void broadcastInTestModeReachesNobodyWhenAlreadyInterrupted() {
        when(userProfileRepository.findAll()).thenReturn(List.of(
                UserProfile.builder().userId("U-first").build(),
                UserProfile.builder().userId("U-second").build()));
        AdminService service = newService();
        ReflectionTestUtils.setField(service, "broadcastTestMode", true);

        Thread.currentThread().interrupt();
        try {
            assertThat(service.broadcastMessage("系統通知"))
                    .isEqualTo(new BroadcastResult(0, 0, 0, true, 2));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verifyNoInteractions(messagingApiClient);
    }

    private AdminService newService() {
        return new AdminService(
                translationRecordRepository,
                userProfileRepository,
                messagingApiClient,
                appConfig,
                openRouterConfig,
                modelCatalog,
                lineUserProfileService,
                userPreferencesModule);
    }
}
