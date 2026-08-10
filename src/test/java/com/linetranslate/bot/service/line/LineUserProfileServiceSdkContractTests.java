package com.linetranslate.bot.service.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.UserProfileResponse;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;

@ExtendWith(MockitoExtension.class)
class LineUserProfileServiceSdkContractTests {

    @Mock
    private MessagingApiClient messagingApiClient;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private TranslationRecordRepository translationRecordRepository;

    @Test
    void sdkTenProfileResponseIsPersisted() throws Exception {
        String userId = "U0123456789abcdef";
        UserProfileResponse profile = new UserProfileResponse(
                "Jason", userId, URI.create("https://example.com/avatar.jpg"), "Available", "zh-TW");
        when(messagingApiClient.getProfile(userId)).thenReturn(
                CompletableFuture.completedFuture(new Result<>("request-id", null, profile)));
        when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());

        new LineUserProfileService(
                messagingApiClient, userProfileRepository, translationRecordRepository)
                .syncUserProfile(userId);

        ArgumentCaptor<UserProfile> captor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileRepository).save(captor.capture());
        assertThat(captor.getValue())
                .extracting(UserProfile::getUserId, UserProfile::getDisplayName,
                        UserProfile::getPictureUrl, UserProfile::getStatusMessage)
                .containsExactly(userId, "Jason", "https://example.com/avatar.jpg", "Available");
    }
}
