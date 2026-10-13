package com.linetranslate.bot.service.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DuplicateKeyException;

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
        lenient().when(repository.setPreferredLanguage(eq(USER_ID), anyString())).thenAnswer(stored(
                (value) -> profile.setPreferredLanguage(value)));
        lenient().when(repository.setPreferredChineseTargetLanguage(eq(USER_ID), anyString())).thenAnswer(stored(
                (value) -> profile.setPreferredChineseTargetLanguage(value)));
        lenient().when(repository.setPreferredModel(eq(USER_ID), anyString())).thenAnswer(stored(
                (value) -> profile.setPreferredModel(value)));
        lenient().when(repository.setPreferredTranslationStyle(eq(USER_ID), anyString())).thenAnswer(stored(
                (value) -> profile.setPreferredTranslationStyle(value)));
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
        verify(repository).setPreferredTranslationStyle(USER_ID, "business");
        assertThatThrownBy(() -> module.updateTranslationStyle(USER_ID, "retired-style"))
                .isInstanceOf(InvalidUserPreferenceException.class);
        verify(repository, never()).setPreferredTranslationStyle(USER_ID, "retired-style");
        verify(repository, never()).save(any());
    }

    @Test
    void languageAndModelUpdatesUseSingleValidatedPersistencePath() {
        UserPreferencesModule module = module(settings("en", "ja", "openai/gpt-4o-mini"));

        module.updateTargetLanguage(USER_ID, "日文");
        UserPreferenceChange model = module.updateModel(USER_ID, "anthropic/claude-sonnet-4");

        assertThat(profile.getPreferredLanguage()).isEqualTo("ja");
        assertThat(profile.getPreferredModel()).isEqualTo("anthropic/claude-sonnet-4");
        assertThat(model.current().model()).isEqualTo("anthropic/claude-sonnet-4");
        verify(repository).setPreferredLanguage(USER_ID, "ja");
        verify(repository).setPreferredModel(USER_ID, "anthropic/claude-sonnet-4");
        verify(repository, never()).save(any());
    }

    @Test
    void chineseTargetUpdateIsOneAtomicFieldWriteReportingBothStates() {
        profile.setPreferredChineseTargetLanguage("en");
        UserPreferencesModule module = module(settings("en", "ja", "openai/gpt-4o-mini"));

        UserPreferenceChange change = module.updateChineseTargetLanguage(USER_ID, "韓文");

        assertThat(change.previous().chineseTargetLanguage()).isEqualTo("en");
        assertThat(change.current().chineseTargetLanguage()).isEqualTo("ko");
        verify(repository).setPreferredChineseTargetLanguage(USER_ID, "ko");
        verify(repository, never()).save(any());
    }

    @Test
    void updateCreatesAMissingProfileOnceAndThenOnlyWritesTheFieldAtomically() {
        UserProfile created = UserProfile.builder().userId("U-first-update").build();
        when(repository.findByUserId("U-first-update")).thenReturn(Optional.empty());
        when(repository.insert(any(UserProfile.class))).thenReturn(created);
        when(repository.setPreferredLanguage("U-first-update", "ja")).thenAnswer(invocation -> {
            created.setPreferredLanguage("ja");
            return Optional.of(created);
        });
        UserPreferencesModule module = module(settings("en", "ko", "openai/gpt-4o-mini"));

        UserPreferenceChange change = module.updateTargetLanguage("U-first-update", "ja");

        assertThat(change.previous().targetLanguage()).isEqualTo("en");
        assertThat(change.current().targetLanguage()).isEqualTo("ja");
        verify(repository).insert(any(UserProfile.class));
        verify(repository).setPreferredLanguage("U-first-update", "ja");
        verify(repository, never()).save(any());
    }

    @Test
    void updateOfAProfileThatDisappearedFailsInsteadOfRewritingAWholeDocument() {
        when(repository.setPreferredLanguage(eq(USER_ID), anyString())).thenReturn(Optional.empty());
        UserPreferencesModule module = module(settings("en", "ja", "openai/gpt-4o-mini"));

        assertThatThrownBy(() -> module.updateTargetLanguage(USER_ID, "ja"))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
        verify(repository, never()).insert(any(UserProfile.class));
    }

    @Test
    void unavailableModelIsRejectedWithoutMutationOrPersistence() {
        UserPreferencesModule module = module(settings("en", "ja", "openai/gpt-4o-mini"));
        profile.setPreferredModel("anthropic/claude-sonnet-4");

        assertThatThrownBy(() -> module.updateModel(USER_ID, "invented-model"))
                .isInstanceOf(InvalidUserPreferenceException.class)
                .hasMessageContaining("invented-model");

        assertThat(profile.getPreferredModel()).isEqualTo("anthropic/claude-sonnet-4");
        verify(repository, never()).setPreferredModel(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void recentLanguagesAreValidatedDeduplicatedAndBoundedWhenRead() {
        UserPreferencesModule module = module(settings("en", "ja", "openai/gpt-4o-mini"));
        profile.setRecentLanguages(new java.util.LinkedHashSet<>(
                List.of("invalid", "ja", "en", "ko", "fr", "de", "es")));

        assertThat(module.resolve(profile).recentLanguages())
                .containsExactly("ja", "en", "ko", "fr", "de");
    }

    @Test
    void translationActivityIsOneAtomicRepositoryUpdateNotAWholeProfileSave() {
        when(repository.recordTranslation(eq(USER_ID), any(LocalDateTime.class), anyString(), any(), anyBoolean()))
                .thenReturn(true);
        UserPreferencesModule module = module(settings("en", "ja", "openai/gpt-4o-mini"));

        module.recordTranslationActivity(USER_ID, "葡萄牙文", "olá", false);
        module.recordTranslationActivity(USER_ID, "ja", "こんにちは", true);

        verify(repository).recordTranslation(eq(USER_ID), any(LocalDateTime.class), eq("olá"), eq("pt"), eq(false));
        verify(repository).recordTranslation(
                eq(USER_ID), any(LocalDateTime.class), eq("こんにちは"), eq("ja"), eq(true));
        verify(repository, never()).save(any());
        verify(repository, never()).findByUserId(anyString());
    }

    @Test
    void translationActivityWithAnUnsupportedTargetStillCountsButAddsNoRecentLanguage() {
        when(repository.recordTranslation(eq(USER_ID), any(LocalDateTime.class), anyString(), any(), anyBoolean()))
                .thenReturn(true);
        UserPreferencesModule module = module(settings("en", "ja", "openai/gpt-4o-mini"));

        module.recordTranslationActivity(USER_ID, "not-a-language", "text", false);
        module.recordTranslationActivity(USER_ID, null, "text", false);

        verify(repository, org.mockito.Mockito.times(2)).recordTranslation(
                eq(USER_ID), any(LocalDateTime.class), eq("text"), isNull(), eq(false));
    }

    @Test
    void translationActivityForAMissingProfileIsSkippedWithoutFailingTheTranslation() {
        when(repository.recordTranslation(eq("U-gone"), any(LocalDateTime.class), anyString(), any(), anyBoolean()))
                .thenReturn(false);
        UserPreferencesModule module = module(settings("en", "ja", "openai/gpt-4o-mini"));

        org.assertj.core.api.Assertions.assertThatCode(
                () -> module.recordTranslationActivity("U-gone", "ja", "text", false))
                .doesNotThrowAnyException();
        verify(repository, never()).save(any());
        verify(repository, never()).insert(any(UserProfile.class));
    }

    @Test
    void translationActivityRequiresAUserId() {
        UserPreferencesModule module = module(settings("en", "ja", "openai/gpt-4o-mini"));

        assertThatThrownBy(() -> module.recordTranslationActivity(" ", "ja", "text", false))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).recordTranslation(any(), any(), any(), any(), anyBoolean());
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

    @Test
    void missingProfileIsInsertedWithFirstInteractionTimestamps() {
        when(repository.findByUserId("U-new")).thenReturn(Optional.empty());
        when(repository.insert(any(UserProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UserPreferencesModule module = module(settings("en", "ja", "openai/gpt-4o-mini"));

        UserProfile created = module.profile("U-new");

        assertThat(created.getUserId()).isEqualTo("U-new");
        assertThat(created.getFirstInteractionAt()).isNotNull().isEqualTo(created.getLastInteractionAt());
        verify(repository).insert(created);
        verify(repository, never()).save(any());
    }

    @Test
    void creationThatLosesTheUniqueIndexRaceReturnsTheStoredProfile() {
        UserProfile winner = UserProfile.builder().id("winner").userId("U-race").build();
        when(repository.findByUserId("U-race")).thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.insert(any(UserProfile.class))).thenThrow(new DuplicateKeyException("E11000"));
        UserPreferencesModule module = module(settings("en", "ja", "openai/gpt-4o-mini"));

        UserProfile resolved = module.profile("U-race");

        assertThat(resolved).isSameAs(winner);
        verify(repository, times(2)).findByUserId("U-race");
        verify(repository, never()).save(any());
    }

    @Test
    void duplicateKeyWithoutAStoredProfileIsNotSwallowed() {
        DuplicateKeyException duplicate = new DuplicateKeyException("E11000");
        when(repository.findByUserId("U-vanished")).thenReturn(Optional.empty());
        when(repository.insert(any(UserProfile.class))).thenThrow(duplicate);
        UserPreferencesModule module = module(settings("en", "ja", "openai/gpt-4o-mini"));

        assertThatThrownBy(() -> module.profile("U-vanished")).isSameAs(duplicate);
    }

    /** Answers an atomic setter the way the stored document changes: apply the value, return the profile. */
    private Answer<Optional<UserProfile>> stored(Consumer<String> change) {
        return invocation -> {
            change.accept(invocation.getArgument(1));
            return Optional.of(profile);
        };
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
