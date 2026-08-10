package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

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
    private UserProfile profile;

    @BeforeEach
    void setUp() {
        TranslationWorkflowModule workflowModule = new TranslationWorkflowModule(
                languageDetectionService,
                translationAdapter,
                translationRecordRepository,
                userProfileRepository,
                appConfig);
        translationService = new TranslationService(workflowModule, userProfileRepository, appConfig);
        profile = UserProfile.builder()
                .userId(USER_ID)
                .preferredAiProvider("openai")
                .preferredLanguage("ja")
                .preferredChineseTargetLanguage("en")
                .build();
        lenient().when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        lenient().when(userProfileRepository.save(any(UserProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(translationAdapter.translate(any(UserProfile.class), anyString(), anyString()))
                .thenReturn(new AiExecutionOutcome.Success(
                        new AiExecutionResult("translated", "openai", "gpt-test")));
    }

    @Test
    void nonChineseTextUsesPreferredTargetLanguage() {
        when(languageDetectionService.detectLanguage("hello")).thenReturn("en");

        translationService.processTranslationRequest(USER_ID, "hello");

        verify(translationAdapter).translate(profile, "hello", "ja");
    }

    @Test
    void ChineseTextUsesDedicatedChineseTargetPreference() {
        when(languageDetectionService.detectLanguage("你好")).thenReturn("zh-tw");

        translationService.processTranslationRequest(USER_ID, "你好");

        verify(translationAdapter).translate(profile, "你好", "en");
    }

    @Test
    void sourceMatchingGeneralPreferenceUsesConfiguredFallbackTarget() {
        profile.setPreferredLanguage("en");
        when(languageDetectionService.detectLanguage("hello")).thenReturn("en");
        when(appConfig.getDefaultTargetLanguageForOthers()).thenReturn("zh-tw");

        translationService.processTranslationRequest(USER_ID, "hello");

        verify(translationAdapter).translate(profile, "hello", "zh-tw");
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
