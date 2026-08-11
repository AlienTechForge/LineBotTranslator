package com.linetranslate.bot.service.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.linetranslate.bot.config.AppConfig;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiProviderAdapter;
import com.linetranslate.bot.service.ai.AiProviderOperation;
import com.linetranslate.bot.service.ai.AiProviderRequest;
import com.linetranslate.bot.service.ai.AiProviderResponse;

@ExtendWith(MockitoExtension.class)
class UserPreferencesModuleTests {

    private static final String USER_ID = "U-preferences";

    @Mock
    private UserProfileRepository repository;
    @Mock
    private AppConfig appConfig;

    private UserPreferencesModule module;
    private UserProfile profile;

    @BeforeEach
    void setUp() {
        lenient().when(appConfig.getDefaultAiProvider()).thenReturn("openai");
        lenient().when(appConfig.getDefaultTargetLanguageForOthers()).thenReturn("en");
        lenient().when(appConfig.getDefaultTargetLanguageForChinese()).thenReturn("ja");
        module = new UserPreferencesModule(
                repository,
                appConfig,
                List.of(
                        adapter("openai", "gpt-default", "gpt-default", "gpt-selected"),
                        adapter("gemini", "gemini-default", "gemini-default", "gemini-fast")));
        profile = UserProfile.builder().userId(USER_ID).build();
        lenient().when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        lenient().when(repository.save(any(UserProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void resolvesMissingAndInvalidLegacyValuesWithoutMutatingDocument() {
        profile.setPreferredLanguage("legacy-language");
        profile.setPreferredChineseTargetLanguage(null);
        profile.setPreferredAiProvider("removed-provider");
        profile.setOpenaiPreferredModel("retired-model");
        profile.setRecentLanguages(null);

        UserPreferences preferences = module.resolve(profile);

        assertThat(preferences.targetLanguage()).isEqualTo("en");
        assertThat(preferences.chineseTargetLanguage()).isEqualTo("ja");
        assertThat(preferences.provider()).isEqualTo("openai");
        assertThat(preferences.model()).isEqualTo("gpt-default");
        assertThat(preferences.recentLanguages()).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void languageUpdatesShareNormalizationAndSinglePersistencePath() {
        UserPreferenceChange general = module.updateTargetLanguage(USER_ID, "日文");
        UserPreferenceChange chinese = module.updateChineseTargetLanguage(USER_ID, "ko");

        assertThat(general.current().targetLanguage()).isEqualTo("ja");
        assertThat(chinese.current().chineseTargetLanguage()).isEqualTo("ko");
        assertThat(profile.getPreferredLanguage()).isEqualTo("ja");
        assertThat(profile.getPreferredChineseTargetLanguage()).isEqualTo("ko");
        verify(repository, org.mockito.Mockito.times(2)).save(profile);
    }

    @Test
    void unavailableModelIsRejectedWithoutMutationOrPersistence() {
        profile.setPreferredAiProvider("openai");
        profile.setOpenaiPreferredModel("gpt-selected");

        assertThatThrownBy(() -> module.updateModel(USER_ID, "invented-model"))
                .isInstanceOf(InvalidUserPreferenceException.class)
                .hasMessageContaining("invented-model");

        assertThat(profile.getOpenaiPreferredModel()).isEqualTo("gpt-selected");
        verify(repository, never()).save(any());
    }

    @Test
    void nullProviderIsRejectedWithoutCreatingOrSavingAProfile() {
        assertThatThrownBy(() -> module.updateProvider(USER_ID, null))
                .isInstanceOf(InvalidUserPreferenceException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void modelFromAnotherProviderSwitchesCompatiblePairAtomically() {
        profile.setPreferredAiProvider("openai");
        profile.setOpenaiPreferredModel("gpt-selected");

        UserPreferenceChange change = module.updateModel(USER_ID, "gemini-fast");

        assertThat(change.current().provider()).isEqualTo("gemini");
        assertThat(change.current().model()).isEqualTo("gemini-fast");
        assertThat(profile.getPreferredAiProvider()).isEqualTo("gemini");
        assertThat(profile.getGeminiPreferredModel()).isEqualTo("gemini-fast");
        verify(repository).save(profile);
    }

    @Test
    void recentLanguagesAreValidatedDeduplicatedAndBoundedByModule() {
        profile.setRecentLanguages(new java.util.LinkedHashSet<>(
                List.of("invalid", "ja", "en", "ja", "ko", "fr", "de", "es")));

        module.persistTranslationActivity(profile, "pt");

        assertThat(module.resolve(profile).recentLanguages())
                .containsExactly("pt", "ja", "en", "ko", "fr");
        verify(repository).save(profile);
    }

    private static AiProviderAdapter adapter(
            String provider,
            String defaultModel,
            String... models) {
        return new AiProviderAdapter() {
            @Override
            public String providerName() {
                return provider;
            }

            @Override
            public String defaultModel() {
                return defaultModel;
            }

            @Override
            public Set<String> availableModels() {
                return Set.of(models);
            }

            @Override
            public Set<AiProviderOperation> capabilities() {
                return Set.of(AiProviderOperation.TRANSLATE_TEXT);
            }

            @Override
            public AiProviderResponse execute(AiProviderRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
