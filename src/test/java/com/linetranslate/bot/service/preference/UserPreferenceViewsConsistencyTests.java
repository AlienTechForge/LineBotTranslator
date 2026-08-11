package com.linetranslate.bot.service.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linetranslate.bot.config.AppConfig;
import com.linetranslate.bot.config.OpenRouterConfig;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.AdminService;
import com.linetranslate.bot.service.ai.AiModelCatalog;
import com.linetranslate.bot.service.line.LineUserProfileService;
import com.linetranslate.bot.service.translation.TranslationService;
import com.linetranslate.bot.service.translation.TranslationWorkflowModule;

class UserPreferenceViewsConsistencyTests {

    @Test
    void statusProfileAndAdminExposeTheSameEffectivePreferences() {
        String userId = "U0123456789abcdef";
        UserProfile profile = UserProfile.builder()
                .userId(userId)
                .displayName("Jason")
                .firstInteractionAt(LocalDateTime.now())
                .lastInteractionAt(LocalDateTime.now())
                .build();
        UserPreferences preferences = new UserPreferences(
                "ja",
                "en",
                "zh-TW",
                "anthropic/claude-sonnet-4",
                List.of("ja", "en"));

        UserProfileRepository profiles = mock(UserProfileRepository.class);
        TranslationRecordRepository records = mock(TranslationRecordRepository.class);
        UserPreferencesModule preferencesModule = mock(UserPreferencesModule.class);
        MessagingApiClient messaging = mock(MessagingApiClient.class);
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(preferencesModule.profile(userId)).thenReturn(profile);
        when(preferencesModule.resolve(profile)).thenReturn(preferences);

        TranslationService translation = new TranslationService(
                mock(TranslationWorkflowModule.class), preferencesModule);
        LineUserProfileService lineProfile = new LineUserProfileService(
                messaging, profiles, records, preferencesModule);
        AdminService admin = new AdminService(
                records,
                profiles,
                messaging,
                mock(AppConfig.class),
                mock(OpenRouterConfig.class),
                mock(AiModelCatalog.class),
                lineProfile,
                preferencesModule);

        assertThat(translation.getUserStatus(userId))
                .contains("OpenRouter", "anthropic/claude-sonnet-4", "日文", "英文");
        assertThat(lineProfile.getUserProfileInfo(userId))
                .contains("OpenRouter", "anthropic/claude-sonnet-4", "日文", "英文");
        assertThat(admin.getUserInfo(userId))
                .containsEntry("aiProvider", "openrouter")
                .containsEntry("preferredModel", "anthropic/claude-sonnet-4")
                .containsEntry("preferredLanguage", "ja")
                .containsEntry("preferredChineseTargetLanguage", "en");
    }
}
