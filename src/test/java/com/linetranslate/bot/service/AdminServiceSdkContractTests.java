package com.linetranslate.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

        assertThat(service.broadcastMessage("系統通知")).isEqualTo(1);

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
}
