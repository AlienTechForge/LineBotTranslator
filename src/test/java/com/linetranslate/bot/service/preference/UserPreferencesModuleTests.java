package com.linetranslate.bot.service.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiProviderAdapter;
import com.linetranslate.bot.service.ai.AiProviderOperation;
import com.linetranslate.bot.service.ai.AiProviderRequest;
import com.linetranslate.bot.service.ai.AiProviderResponse;
import com.linetranslate.bot.service.settings.RuntimeSettings;

@ExtendWith(MockitoExtension.class)
class UserPreferencesModuleTests {

    private static final String USER_ID = "U-preferences";
    private static final AiProviderAdapter ADAPTER = adapter();

    @Mock private UserProfileRepository repository;
    private UserProfile profile;

    @BeforeEach
    void setUp() {
        profile = UserProfile.builder().userId(USER_ID).build();
        lenient().when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        lenient().when(repository.save(any(UserProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void resolvesMissingAndInvalidValuesWithoutMutatingDocument() {
        profile.setPreferredLanguage("legacy-language");
        profile.setPreferredModel("retired-model");
        profile.setRecentLanguages(null);
        profile.setPreferredTranslationStyle("retired-style");
        UserPreferencesModule module = module(settings("en", "ja", "openai/gpt-4o-mini"));

        UserPreferences preferences = module.resolve(profile);

        assertThat(preferences.targetLanguage()).isEqualTo("en");
        assertThat(preferences.chineseTargetLanguage()).isEqualTo("ja");
        assertThat(preferences.model()).isEqualTo("openai/gpt-4o-mini");
        assertThat(preferences.recentLanguages()).isEmpty();
        assertThat(preferences.translationStyle())
                .isEqualTo(com.linetranslate.bot.service.translation.TranslationStylePreset.defaultPreset());
        verify(repository, never()).save(any());
    }

    @Test
    void styleUpdatePersistsOnlyValidatedStableId() {
        UserPreferencesModule module = module(settings("en", "ja", "openai/gpt-4o-mini"));

        UserPreferenceChange change = module.updateTranslationStyle(USER_ID, "business");

        assertThat(profile.getPreferredTranslationStyle()).isEqualTo("business");
        assertThat(change.current().translationStyle().id()).isEqualTo("business");
        verify(repository).save(profile);
        assertThatThrownBy(() -> module.updateTranslationStyle(USER_ID, "retired-style"))
                .isInstanceOf(InvalidUserPreferenceException.class);
    }

    @Test
    void languageAndModelUpdatesUseSingleValidatedPersistencePath() {
        UserPreferencesModule module = module(settings("en", "ja", "openai/gpt-4o-mini"));

        module.updateTargetLanguage(USER_ID, "日文");
        UserPreferenceChange model = module.updateModel(USER_ID, "anthropic/claude-sonnet-4");

        assertThat(profile.getPreferredLanguage()).isEqualTo("ja");
        assertThat(profile.getPreferredModel()).isEqualTo("anthropic/claude-sonnet-4");
        assertThat(model.current().model()).isEqualTo("anthropic/claude-sonnet-4");
        verify(repository, org.mockito.Mockito.times(2)).save(profile);
    }

    @Test
    void unavailableModelIsRejectedWithoutMutationOrPersistence() {
        UserPreferencesModule module = module(settings("en", "ja", "openai/gpt-4o-mini"));
        profile.setPreferredModel("anthropic/claude-sonnet-4");

        assertThatThrownBy(() -> module.updateModel(USER_ID, "invented-model"))
                .isInstanceOf(InvalidUserPreferenceException.class)
                .hasMessageContaining("invented-model");

        assertThat(profile.getPreferredModel()).isEqualTo("anthropic/claude-sonnet-4");
        verify(repository, never()).save(any());
    }

    @Test
    void recentLanguagesAreValidatedDeduplicatedAndBoundedByModule() {
        UserPreferencesModule module = module(settings("en", "ja", "openai/gpt-4o-mini"));
        profile.setRecentLanguages(new java.util.LinkedHashSet<>(
                List.of("invalid", "ja", "en", "ko", "fr", "de", "es")));

        module.persistTranslationActivity(profile, "pt");

        assertThat(module.resolve(profile).recentLanguages())
                .containsExactly("pt", "ja", "en", "ko", "fr");
    }

    @Test
    void runtimeDefaultsAreReadDynamicallyWithoutRebuildingModule() {
        AtomicReference<RuntimeSettings> runtime = new AtomicReference<>(
                settings("en", "ja", "openai/gpt-4o-mini"));
        UserPreferencesModule module = new UserPreferencesModule(repository, runtime::get, List.of(ADAPTER));

        assertThat(module.resolve(profile))
                .extracting(UserPreferences::targetLanguage,
                        UserPreferences::chineseTargetLanguage,
                        UserPreferences::model)
                .containsExactly("en", "ja", "openai/gpt-4o-mini");

        runtime.set(settings("ko", "fr", "anthropic/claude-sonnet-4"));
        assertThat(module.resolve(profile))
                .extracting(UserPreferences::targetLanguage,
                        UserPreferences::chineseTargetLanguage,
                        UserPreferences::model)
                .containsExactly("ko", "fr", "anthropic/claude-sonnet-4");
    }

    private UserPreferencesModule module(RuntimeSettings settings) {
        return new UserPreferencesModule(repository, () -> settings, List.of(ADAPTER));
    }

    private static RuntimeSettings settings(String other, String chinese, String model) {
        return new RuntimeSettings(chinese, other, model, true,
                2, 1, null, "U-admin", RuntimeSettings.Source.PERSISTED);
    }

    private static AiProviderAdapter adapter() {
        return new AiProviderAdapter() {
            public String providerName() { return "openrouter"; }
            public String defaultModel() { return "openai/gpt-4o-mini"; }
            public Set<String> availableModels() {
                return Set.of("openai/gpt-4o-mini", "anthropic/claude-sonnet-4");
            }
            public Set<AiProviderOperation> capabilities() { return Set.of(AiProviderOperation.values()); }
            public AiProviderResponse execute(AiProviderRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
