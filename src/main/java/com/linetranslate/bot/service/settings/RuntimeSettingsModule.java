package com.linetranslate.bot.service.settings;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.config.AppConfig;
import com.linetranslate.bot.config.GeminiConfig;
import com.linetranslate.bot.config.OpenAiConfig;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.util.LanguageUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Deep Module for validated, versioned and durable administrator settings.
 * Deployment properties remain the safe fallback when Mongo is unavailable.
 */
@Service
@Slf4j
public class RuntimeSettingsModule implements RuntimeSettingsSource {

    public static final String DOCUMENT_ID = "global";
    public static final int SCHEMA_VERSION = 1;

    private final MongoTemplate mongoTemplate;
    private final AppConfig appConfig;
    private final OpenAiConfig openAiConfig;
    private final GeminiConfig geminiConfig;
    private final Clock clock;

    @Autowired
    public RuntimeSettingsModule(
            MongoTemplate mongoTemplate,
            AppConfig appConfig,
            OpenAiConfig openAiConfig,
            GeminiConfig geminiConfig) {
        this(mongoTemplate, appConfig, openAiConfig, geminiConfig, Clock.systemUTC());
    }

    public RuntimeSettingsModule(
            MongoTemplate mongoTemplate,
            AppConfig appConfig,
            OpenAiConfig openAiConfig,
            GeminiConfig geminiConfig,
            Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.appConfig = appConfig;
        this.openAiConfig = openAiConfig;
        this.geminiConfig = geminiConfig;
        this.clock = clock;
    }

    @Override
    public RuntimeSettings current() {
        RuntimeSettings defaults = deploymentDefaults();
        try {
            RuntimeSettingsDocument stored = mongoTemplate.findById(
                    DOCUMENT_ID,
                    RuntimeSettingsDocument.class);
            if (stored == null) {
                return defaults;
            }
            requireSupportedSchema(stored);
            return merge(stored, defaults);
        } catch (RuntimeException failure) {
            log.warn("Runtime settings unavailable; using deployment defaults: failure={}",
                    SafeLog.failure(failure));
            return defaults;
        }
    }

    public RuntimeSettings update(RuntimeSettingKey key, String rawValue, String operatorId) {
        if (key == null) {
            throw new InvalidRuntimeSettingException("Setting key is required");
        }
        String operator = requireOperator(operatorId);
        Object value = validate(key, rawValue);
        Instant now = clock.instant();

        try {
            RuntimeSettingsDocument existing = mongoTemplate.findById(
                    DOCUMENT_ID,
                    RuntimeSettingsDocument.class);
            if (existing != null) {
                requireSupportedSchema(existing);
            }
            RuntimeSettings defaults = deploymentDefaults();
            Object previousValue = valueOf(existing, defaults, key);
            RuntimeSettingsDocument.Change change = new RuntimeSettingsDocument.Change(
                    key.name(), previousValue, value, operator, now);

            Update update = new Update()
                    .setOnInsert("schemaVersion", SCHEMA_VERSION)
                    .set(key.fieldName(), value)
                    .set("updatedAt", now)
                    .set("updatedBy", operator)
                    .inc("revision", 1)
                    .push("changes", change);
            RuntimeSettingsDocument persisted = mongoTemplate.findAndModify(
                    Query.query(Criteria.where("_id").is(DOCUMENT_ID)
                            .and("schemaVersion").is(SCHEMA_VERSION)),
                    update,
                    FindAndModifyOptions.options().upsert(true).returnNew(true),
                    RuntimeSettingsDocument.class);
            if (persisted == null) {
                throw new RuntimeSettingsPersistenceException("Runtime setting update returned no document");
            }
            RuntimeSettings result = merge(persisted, defaults);
            log.info("Runtime setting updated: key={}, operator={}, revision={}",
                    key, SafeLog.user(operator), result.revision());
            return result;
        } catch (InvalidRuntimeSettingException | RuntimeSettingsPersistenceException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new RuntimeSettingsPersistenceException("Runtime setting could not be persisted", failure);
        }
    }

    private Object validate(RuntimeSettingKey key, String rawValue) {
        return switch (key) {
            case DEFAULT_CHINESE_TARGET_LANGUAGE, DEFAULT_OTHER_TARGET_LANGUAGE ->
                    requireLanguage(rawValue);
            case DEFAULT_AI_PROVIDER -> requireProvider(rawValue);
            case OPENAI_DEFAULT_MODEL -> requireModel(rawValue, openAiConfig.getAvailableModels(), "OpenAI");
            case GEMINI_DEFAULT_MODEL -> requireModel(rawValue, geminiConfig.getAvailableModels(), "Gemini");
            case OCR_ENABLED -> requireBoolean(rawValue);
        };
    }

    private RuntimeSettings deploymentDefaults() {
        return new RuntimeSettings(
                appConfig.getDefaultTargetLanguageForChinese(),
                appConfig.getDefaultTargetLanguageForOthers(),
                appConfig.getDefaultAiProvider(),
                openAiConfig.getModelName(),
                geminiConfig.getModelName(),
                appConfig.isOcrEnabled(),
                SCHEMA_VERSION,
                0,
                null,
                null,
                RuntimeSettings.Source.DEPLOYMENT_DEFAULTS);
    }

    private static RuntimeSettings merge(
            RuntimeSettingsDocument stored,
            RuntimeSettings defaults) {
        return new RuntimeSettings(
                orDefault(stored.getDefaultTargetLanguageForChinese(),
                        defaults.defaultTargetLanguageForChinese()),
                orDefault(stored.getDefaultTargetLanguageForOthers(),
                        defaults.defaultTargetLanguageForOthers()),
                orDefault(stored.getDefaultAiProvider(), defaults.defaultAiProvider()),
                orDefault(stored.getOpenAiDefaultModel(), defaults.openAiDefaultModel()),
                orDefault(stored.getGeminiDefaultModel(), defaults.geminiDefaultModel()),
                stored.getOcrEnabled() == null ? defaults.ocrEnabled() : stored.getOcrEnabled(),
                SCHEMA_VERSION,
                stored.getRevision() == null ? 0 : stored.getRevision(),
                stored.getUpdatedAt(),
                stored.getUpdatedBy(),
                RuntimeSettings.Source.PERSISTED);
    }

    private static Object valueOf(
            RuntimeSettingsDocument existing,
            RuntimeSettings defaults,
            RuntimeSettingKey key) {
        RuntimeSettings effective = existing == null ? defaults : merge(existing, defaults);
        return switch (key) {
            case DEFAULT_CHINESE_TARGET_LANGUAGE -> effective.defaultTargetLanguageForChinese();
            case DEFAULT_OTHER_TARGET_LANGUAGE -> effective.defaultTargetLanguageForOthers();
            case DEFAULT_AI_PROVIDER -> effective.defaultAiProvider();
            case OPENAI_DEFAULT_MODEL -> effective.openAiDefaultModel();
            case GEMINI_DEFAULT_MODEL -> effective.geminiDefaultModel();
            case OCR_ENABLED -> effective.ocrEnabled();
        };
    }

    private static String requireLanguage(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new InvalidRuntimeSettingException("Language is required");
        }
        String normalized = LanguageUtils.toLanguageCode(rawValue.trim());
        if (!LanguageUtils.isSupported(normalized)
                && !"zh-tw".equalsIgnoreCase(normalized)
                && !"zh-cn".equalsIgnoreCase(normalized)) {
            throw new InvalidRuntimeSettingException("Unsupported language");
        }
        return normalized;
    }

    private static String requireProvider(String rawValue) {
        String provider = normalize(rawValue);
        if (!"openai".equals(provider) && !"gemini".equals(provider)) {
            throw new InvalidRuntimeSettingException("Unsupported AI provider");
        }
        return provider;
    }

    private static String requireModel(String rawValue, List<String> available, String provider) {
        String model = rawValue == null ? null : rawValue.trim();
        boolean supported = model != null && !model.isBlank() && available != null
                && available.stream().map(String::trim).anyMatch(model::equals);
        if (!supported) {
            throw new InvalidRuntimeSettingException("Unsupported " + provider + " model");
        }
        return model;
    }

    private static boolean requireBoolean(String rawValue) {
        String normalized = normalize(rawValue);
        if (List.of("on", "true", "enabled", "1", "開", "啟用").contains(normalized)) {
            return true;
        }
        if (List.of("off", "false", "disabled", "0", "關", "禁用").contains(normalized)) {
            return false;
        }
        throw new InvalidRuntimeSettingException("OCR value must be on or off");
    }

    private static String requireOperator(String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            throw new InvalidRuntimeSettingException("Operator ID is required");
        }
        return operatorId.trim();
    }

    private static void requireSupportedSchema(RuntimeSettingsDocument document) {
        if (document.getSchemaVersion() == null
                || document.getSchemaVersion() != SCHEMA_VERSION) {
            throw new RuntimeSettingsPersistenceException("Unsupported runtime settings schema");
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
