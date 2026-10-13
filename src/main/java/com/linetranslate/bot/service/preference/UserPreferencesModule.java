package com.linetranslate.bot.service.preference;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.config.AppConfig;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.UserProfileAtomicUpdates;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiProviderAdapter;
import com.linetranslate.bot.service.ai.AiProviderRequest;
import com.linetranslate.bot.service.settings.RuntimeSettings;
import com.linetranslate.bot.service.settings.RuntimeSettingsSource;
import com.linetranslate.bot.service.translation.TranslationStylePreset;
import com.linetranslate.bot.util.LanguageUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Deep Module for effective user preferences. Model discovery stays dynamic at
 * the OpenRouter catalog Seam; only a validated model slug is persisted.
 */
@Service
@Slf4j
public class UserPreferencesModule {

    private static final int MAX_RECENT_LANGUAGES = UserProfileAtomicUpdates.RECENT_LIMIT;

    private final UserProfileRepository repository;
    private final RuntimeSettingsSource runtimeSettingsSource;
    private final AiProviderAdapter adapter;

    @Autowired
    public UserPreferencesModule(
            UserProfileRepository repository,
            RuntimeSettingsSource runtimeSettingsSource,
            List<AiProviderAdapter> adapters) {
        this.repository = repository;
        this.runtimeSettingsSource = runtimeSettingsSource;
        if (adapters == null || adapters.size() != 1) {
            throw new IllegalStateException("OpenRouter-only preferences require exactly one AI Adapter");
        }
        this.adapter = adapters.get(0);
    }

    /** Compatibility constructor for focused unit tests. */
    public UserPreferencesModule(
            UserProfileRepository repository,
            AppConfig appConfig,
            List<AiProviderAdapter> adapters) {
        this(repository, deploymentSettings(appConfig, adapters), adapters);
    }

    /**
     * Returns the user's profile, creating it on first contact. The unique userId
     * index ({@link UserProfileIndexInitializer}) rejects a concurrent second
     * insert; the losing worker re-reads the profile the winner stored.
     */
    public UserProfile profile(String userId) {
        requireUserId(userId);
        Optional<UserProfile> existing = repository.findByUserId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            return repository.insert(UserProfile.builder()
                    .userId(userId)
                    .firstInteractionAt(now)
                    .lastInteractionAt(now)
                    .build());
        } catch (DuplicateKeyException concurrentCreation) {
            return repository.findByUserId(userId).orElseThrow(() -> concurrentCreation);
        }
    }

    public UserPreferences get(String userId) {
        return resolve(profile(userId));
    }

    public UserPreferences resolve(UserProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("User profile is required");
        }
        RuntimeSettings settings = runtimeSettingsSource.current();
        return new UserPreferences(
                effectiveLanguage(profile.getPreferredLanguage(),
                        settings.defaultTargetLanguageForOthers(), "en"),
                effectiveLanguage(profile.getPreferredChineseTargetLanguage(),
                        settings.defaultTargetLanguageForChinese(), "en"),
                effectiveLanguage(null, settings.defaultTargetLanguageForOthers(), "en"),
                effectiveModel(profile.getPreferredModel(), settings),
                validRecentLanguages(profile.getRecentLanguages()),
                TranslationStylePreset.resolve(profile.getPreferredTranslationStyle()));
    }

    public UserPreferenceChange updateTargetLanguage(String userId, String language) {
        String normalized = requireLanguage(language);
        return change(userId, id -> repository.setPreferredLanguage(id, normalized));
    }

    public UserPreferenceChange updateChineseTargetLanguage(String userId, String language) {
        String normalized = requireLanguage(language);
        return change(userId, id -> repository.setPreferredChineseTargetLanguage(id, normalized));
    }

    public UserPreferenceChange updateModel(String userId, String modelName) {
        String model = normalizeModel(modelName);
        if (model == null || !adapter.supports(AiProviderRequest.translate(model, "validation", "en"))) {
            throw new InvalidUserPreferenceException(InvalidUserPreferenceException.Kind.MODEL, modelName);
        }
        return change(userId, id -> repository.setPreferredModel(id, model));
    }

    public UserPreferenceChange updateTranslationStyle(String userId, String presetId) {
        TranslationStylePreset preset = TranslationStylePreset.find(presetId)
                .orElseThrow(() -> new InvalidUserPreferenceException(
                        InvalidUserPreferenceException.Kind.STYLE, presetId));
        return change(userId, id -> repository.setPreferredTranslationStyle(id, preset.id()));
    }

    /**
     * Records one successful translation for the user: activity time, counters, recent
     * translated texts and recent target languages, as one atomic update that cannot overwrite
     * a concurrent change of the same profile.
     */
    public void recordTranslationActivity(
            String userId, String targetLanguage, String translatedText, boolean imageTranslation) {
        requireUserId(userId);
        String normalized = normalizeLanguage(targetLanguage);
        boolean recorded = repository.recordTranslation(
                userId,
                LocalDateTime.now(),
                translatedText,
                isSupportedLanguage(normalized) ? normalized : null,
                imageTranslation);
        if (!recorded) {
            log.warn("Translation activity not recorded, profile is missing: user={}", SafeLog.user(userId));
        }
    }

    public java.util.Set<String> availableModels() {
        return adapter.availableModels();
    }

    /** Creates the profile when missing, applies one atomic field update and resolves both states. */
    private UserPreferenceChange change(String userId, Function<String, Optional<UserProfile>> update) {
        UserPreferences previous = resolve(profile(userId));
        UserProfile updated = update.apply(userId)
                .orElseThrow(() -> new IllegalStateException("User profile disappeared during update"));
        return new UserPreferenceChange(previous, resolve(updated));
    }

    private String effectiveModel(String requested, RuntimeSettings settings) {
        String model = normalizeModel(requested);
        if (model != null && adapter.availableModels().contains(model)) {
            return model;
        }
        String runtimeDefault = normalizeModel(settings.openRouterDefaultModel());
        if (runtimeDefault != null && adapter.availableModels().contains(runtimeDefault)) {
            return runtimeDefault;
        }
        return adapter.defaultModel();
    }

    private String effectiveLanguage(String requested, String configuredDefault, String hardDefault) {
        String normalized = normalizeLanguage(requested);
        if (isSupportedLanguage(normalized)) {
            return normalized;
        }
        normalized = normalizeLanguage(configuredDefault);
        return isSupportedLanguage(normalized) ? normalized : hardDefault;
    }

    private String requireLanguage(String language) {
        String normalized = normalizeLanguage(language);
        if (!isSupportedLanguage(normalized)) {
            throw new InvalidUserPreferenceException(InvalidUserPreferenceException.Kind.LANGUAGE, language);
        }
        return normalized;
    }

    private List<String> validRecentLanguages(Collection<String> recentLanguages) {
        if (recentLanguages == null) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String language : recentLanguages) {
            String normalized = normalizeLanguage(language);
            if (isSupportedLanguage(normalized)) {
                result.add(normalized);
            }
            if (result.size() == MAX_RECENT_LANGUAGES) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private static String normalizeLanguage(String language) {
        return language == null || language.isBlank() ? null : LanguageUtils.toLanguageCode(language.trim());
    }

    private static boolean isSupportedLanguage(String language) {
        return language != null
                && (LanguageUtils.isSupported(language)
                        || "zh-tw".equalsIgnoreCase(language)
                        || "zh-cn".equalsIgnoreCase(language));
    }

    private static String normalizeModel(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID is required");
        }
    }

    private static RuntimeSettingsSource deploymentSettings(
            AppConfig appConfig,
            List<AiProviderAdapter> adapters) {
        String model = adapters == null || adapters.isEmpty()
                ? "unavailable"
                : adapters.get(0).defaultModel();
        return () -> new RuntimeSettings(
                appConfig.getDefaultTargetLanguageForChinese(),
                appConfig.getDefaultTargetLanguageForOthers(),
                model,
                appConfig.isOcrEnabled(),
                2,
                0,
                null,
                null,
                RuntimeSettings.Source.DEPLOYMENT_DEFAULTS);
    }
}
