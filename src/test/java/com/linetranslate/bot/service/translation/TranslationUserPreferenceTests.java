package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.linetranslate.bot.config.AppConfig;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderAdapter;
import com.linetranslate.bot.service.preference.UserPreferences;
import com.linetranslate.bot.service.preference.UserPreferencesModule;

@ExtendWith(MockitoExtension.class)
class TranslationUserPreferenceTests {

    private static final String USER_ID = "U-preference-test";

    @Mock
    private LanguageDetectionService languageDetectionService;
    @Mock
    private CachedTranslationAdapter translationAdapter;
    @Mock
    private TranslationRecordRepository translationRecordRepository;
    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private AppConfig appConfig;
    private TranslationService translationService;
    private UserPreferencesModule userPreferencesModule;
    private UserProfile profile;

    @BeforeEach
    void setUp() {
        AiProviderAdapter openAi = org.mockito.Mockito.mock(AiProviderAdapter.class);
        lenient().when(openAi.providerName()).thenReturn("openrouter");
        lenient().when(openAi.defaultModel()).thenReturn("gpt-test");
        lenient().when(openAi.availableModels()).thenReturn(Set.of("gpt-test"));
        lenient().when(appConfig.getDefaultTargetLanguageForOthers()).thenReturn("en");
        lenient().when(appConfig.getDefaultTargetLanguageForChinese()).thenReturn("en");
        userPreferencesModule = new UserPreferencesModule(
                userProfileRepository, appConfig, List.of(openAi));
        TranslationWorkflowModule workflowModule = new TranslationWorkflowModule(
                languageDetectionService,
                translationAdapter,
                translationRecordRepository,
                userPreferencesModule);
        translationService = new TranslationService(workflowModule, userPreferencesModule);
        profile = UserProfile.builder()
                .userId(USER_ID)
                .preferredModel("gpt-test")
                .preferredLanguage("ja")
                .preferredChineseTargetLanguage("en")
                .build();
        lenient().when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        lenient().when(userProfileRepository.save(any(UserProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(translationAdapter.translate(any(UserPreferences.class), anyString(), anyString()))
                .thenReturn(new AiExecutionOutcome.Success(
                        new AiExecutionResult("translated", "openai", "gpt-test")));
    }

    @Test
    void nonChineseTextUsesPreferredTargetLanguage() {
        when(languageDetectionService.detectLanguage("hello")).thenReturn("en");

        translationService.processTranslationRequest(USER_ID, "hello");

        verify(translationAdapter).translate(
                argThat(preferences -> "ja".equals(preferences.targetLanguage())),
                eq("hello"),
                eq("ja"));
    }

    @Test
    void ChineseTextUsesDedicatedChineseTargetPreference() {
        when(languageDetectionService.detectLanguage("你好")).thenReturn("zh-tw");

        translationService.processTranslationRequest(USER_ID, "你好");

        verify(translationAdapter).translate(
                argThat(preferences -> "en".equals(preferences.chineseTargetLanguage())),
                eq("你好"),
                eq("en"));
    }

    @Test
    void sourceMatchingGeneralPreferenceUsesConfiguredFallbackTarget() {
        profile.setPreferredLanguage("en");
        when(languageDetectionService.detectLanguage("hello")).thenReturn("en");
        when(appConfig.getDefaultTargetLanguageForOthers()).thenReturn("zh-tw");

        translationService.processTranslationRequest(USER_ID, "hello");

        verify(translationAdapter).translate(
                any(UserPreferences.class), eq("hello"), eq("zh-TW"));
    }

    @Test
    void settingLanguageNormalizesAndPersistsSupportedPreference() {
        profile.setPreferredLanguage(null);

        String result = translationService.setPreferredLanguage(USER_ID, "日文");

        assertThat(profile.getPreferredLanguage()).isEqualTo("ja");
        assertThat(result).contains("ja");
        verify(userProfileRepository, atLeastOnce()).save(profile);
    }

    @Test
    void invalidLanguageDoesNotMutateOrPersistPreference() {
        String result = translationService.setPreferredLanguage(USER_ID, "not-a-language");

        assertThat(result).contains("不支持的語言");
        assertThat(profile.getPreferredLanguage()).isEqualTo("ja");
        verify(userProfileRepository, never()).save(any(UserProfile.class));
    }
}
