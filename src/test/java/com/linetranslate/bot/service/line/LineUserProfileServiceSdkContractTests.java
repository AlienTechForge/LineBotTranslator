package com.linetranslate.bot.service.line;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.UserProfileResponse;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.preference.UserPreferencesModule;

@ExtendWith(MockitoExtension.class)
class LineUserProfileServiceSdkContractTests {

    @Mock
    private MessagingApiClient messagingApiClient;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private UserPreferencesModule userPreferencesModule;

    @Test
    void sdkTenProfileResponseIsPersisted() throws Exception {
        String userId = "U0123456789abcdef";
        UserProfileResponse profile = new UserProfileResponse(
                "Jason", userId, URI.create("https://example.com/avatar.jpg"), "Available", "zh-TW");
        when(messagingApiClient.getProfile(userId)).thenReturn(
                CompletableFuture.completedFuture(new Result<>("request-id", null, profile)));
        when(userPreferencesModule.profile(userId)).thenReturn(UserProfile.builder().userId(userId).build());

        new LineUserProfileService(
                messagingApiClient, userProfileRepository, userPreferencesModule)
                .syncUserProfile(userId);

        verify(userPreferencesModule).profile(userId);
        verify(userProfileRepository).setLineProfile(
                userId, "Jason", "https://example.com/avatar.jpg", "Available");
        // Only the LINE fields are written, so a concurrent preference change is never overwritten.
        verify(userProfileRepository, never()).save(any(UserProfile.class));
    }
}
