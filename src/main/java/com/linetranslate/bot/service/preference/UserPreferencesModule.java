package com.linetranslate.bot.service.preference;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.linetranslate.bot.config.AppConfig;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiProviderAdapter;
import com.linetranslate.bot.util.LanguageUtils;

/**
 * Deep Module for effective user preferences. This is the single validation,
 * fallback and persistence path for preference data.
 */
@Service
public class UserPreferencesModule {

    private static final int MAX_RECENT_LANGUAGES = 5;

    private final UserProfileRepository repository;
    private final AppConfig appConfig;
    private final Map<String, ProviderModels> providerModels;
    private final String defaultProvider;

    public UserPreferencesModule(
            UserProfileRepository repository,
            AppConfig appConfig,
            List<AiProviderAdapter> adapters) {
        this.repository = repository;
        this.appConfig = appConfig;

        Map<String, ProviderModels> catalog = new LinkedHashMap<>();
        for (AiProviderAdapter adapter : adapters) {
            String provider = normalizeName(adapter.providerName());
            if (provider == null) {
                continue;
            }
            Set<String> models = normalizedModels(adapter.availableModels());
            String fallbackModel = normalizeModel(adapter.defaultModel());
            if (fallbackModel == null) {
                fallbackModel = models.stream().findFirst().orElse("unavailable");
            }
            if (fallbackModel != null) {
                models.add(fallbackModel);
            }
            catalog.put(provider, new ProviderModels(fallbackModel, Set.copyOf(models)));
        }
        if (catalog.isEmpty()) {
            catalog.put("openai", new ProviderModels("unavailable", Set.of("unavailable")));
        }
        this.providerModels = Map.copyOf(catalog);

        String configuredDefault = normalizeName(appConfig.getDefaultAiProvider());
        this.defaultProvider = catalog.containsKey(configuredDefault)
                ? configuredDefault
                : catalog.keySet().stream().findFirst().orElse("openai");
    }

    public UserProfile profile(String userId) {
        requireUserId(userId);
        Optional<UserProfile> existing = repository.findByUserId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        LocalDateTime now = LocalDateTime.now();
        return repository.save(UserProfile.builder()
                .userId(userId)
                .firstInteractionAt(now)
                .lastInteractionAt(now)
                .build());
    }

    public UserPreferences get(String userId) {
        return resolve(profile(userId));
    }

    public UserPreferences resolve(UserProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("User profile is required");
        }

        String provider = effectiveProvider(profile.getPreferredAiProvider());
        Map<String, String> models = new LinkedHashMap<>();
        for (Map.Entry<String, ProviderModels> entry : providerModels.entrySet()) {
            String requested = modelField(profile, entry.getKey());
            models.put(entry.getKey(), effectiveModel(entry.getKey(), requested));
        }
        if (!models.containsKey(provider)) {
            models.put(provider, effectiveModel(provider, null));
        }

        return new UserPreferences(
                effectiveLanguage(
                        profile.getPreferredLanguage(),
                        appConfig.getDefaultTargetLanguageForOthers(),
                        "en"),
                effectiveLanguage(
                        profile.getPreferredChineseTargetLanguage(),
                        appConfig.getDefaultTargetLanguageForChinese(),
                        "en"),
                effectiveLanguage(
                        null,
                        appConfig.getDefaultTargetLanguageForOthers(),
                        "en"),
                provider,
                models.get(provider),
                models,
                validRecentLanguages(profile.getRecentLanguages()));
    }

    public UserPreferenceChange updateTargetLanguage(String userId, String language) {
        String normalized = requireLanguage(language);
        UserProfile profile = profile(userId);
        UserPreferences previous = resolve(profile);
        profile.setPreferredLanguage(normalized);
        repository.save(profile);
        return new UserPreferenceChange(previous, resolve(profile));
    }

    public UserPreferenceChange updateChineseTargetLanguage(String userId, String language) {
        String normalized = requireLanguage(language);
        UserProfile profile = profile(userId);
        UserPreferences previous = resolve(profile);
        profile.setPreferredChineseTargetLanguage(normalized);
        repository.save(profile);
        return new UserPreferenceChange(previous, resolve(profile));
    }

    public UserPreferenceChange updateProvider(String userId, String providerName) {
        String provider = normalizeName(providerName);
        if (provider == null || !providerModels.containsKey(provider)) {
            throw new InvalidUserPreferenceException(
                    InvalidUserPreferenceException.Kind.PROVIDER,
                    providerName);
        }
        UserProfile profile = profile(userId);
        UserPreferences previous = resolve(profile);
        profile.setPreferredAiProvider(provider);
        repository.save(profile);
        return new UserPreferenceChange(previous, resolve(profile));
    }

    public UserPreferenceChange updateModel(String userId, String modelName) {
        String model = normalizeModel(modelName);
        if (model == null) {
            throw new InvalidUserPreferenceException(
                    InvalidUserPreferenceException.Kind.MODEL,
                    modelName);
        }
        List<String> compatibleProviders = providerModels.entrySet().stream()
                .filter(entry -> entry.getValue().available().contains(model))
                .map(Map.Entry::getKey)
                .toList();
        if (compatibleProviders.isEmpty()) {
            throw new InvalidUserPreferenceException(
                    InvalidUserPreferenceException.Kind.MODEL,
                    modelName);
        }

        UserProfile profile = profile(userId);
        UserPreferences previous = resolve(profile);
        String provider = compatibleProviders.contains(previous.provider())
                ? previous.provider()
                : compatibleProviders.get(0);
        profile.setPreferredAiProvider(provider);
        setModelField(profile, provider, model);
        repository.save(profile);
        return new UserPreferenceChange(previous, resolve(profile));
    }

    /** Persists a valid, deduplicated, bounded recent-language preference. */
    public void persistTranslationActivity(UserProfile profile, String targetLanguage) {
        if (profile == null) {
            throw new IllegalArgumentException("User profile is required");
        }
        LinkedHashSet<String> recent = new LinkedHashSet<>();
        String normalized = normalizeLanguage(targetLanguage);
        if (isSupportedLanguage(normalized)) {
            recent.add(normalized);
        }
        recent.addAll(validRecentLanguages(profile.getRecentLanguages()));
        profile.setRecentLanguages(recent.stream()
                .limit(MAX_RECENT_LANGUAGES)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll));
        repository.save(profile);
    }

    public String defaultProvider() {
        return defaultProvider;
    }

    public Map<String, Set<String>> availableModels() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        providerModels.forEach((provider, models) -> result.put(provider, models.available()));
        return Map.copyOf(result);
    }

    private String effectiveProvider(String requested) {
        String normalized = normalizeName(requested);
        return normalized != null && providerModels.containsKey(normalized)
                ? normalized
                : defaultProvider;
    }

    private String effectiveModel(String provider, String requested) {
        ProviderModels catalog = providerModels.get(provider);
        if (catalog == null) {
            return null;
        }
        String normalized = normalizeModel(requested);
        return normalized != null && catalog.available().contains(normalized)
                ? normalized
                : catalog.defaultModel();
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
            throw new InvalidUserPreferenceException(
                    InvalidUserPreferenceException.Kind.LANGUAGE,
                    language);
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

    private static String modelField(UserProfile profile, String provider) {
        return "gemini".equals(provider)
                ? profile.getGeminiPreferredModel()
                : profile.getOpenaiPreferredModel();
    }

    private static void setModelField(UserProfile profile, String provider, String model) {
        if ("gemini".equals(provider)) {
            profile.setGeminiPreferredModel(model);
        } else {
            profile.setOpenaiPreferredModel(model);
        }
    }

    private static Set<String> normalizedModels(Collection<String> models) {
        Set<String> result = new LinkedHashSet<>();
        if (models != null) {
            for (String model : models) {
                String normalized = normalizeModel(model);
                if (normalized != null) {
                    result.add(normalized);
                }
            }
        }
        return result;
    }

    private static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        return LanguageUtils.toLanguageCode(language.trim());
    }

    private static boolean isSupportedLanguage(String language) {
        return language != null
                && (LanguageUtils.isSupported(language)
                        || "zh-tw".equalsIgnoreCase(language)
                        || "zh-cn".equalsIgnoreCase(language));
    }

    private static String normalizeName(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeModel(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID is required");
        }
    }

    private record ProviderModels(String defaultModel, Set<String> available) {
    }
}
