package com.linetranslate.bot.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.linetranslate.bot.config.AppConfig;
import com.linetranslate.bot.config.GeminiConfig;
import com.linetranslate.bot.config.OpenAiConfig;

@SpringBootTest
@ActiveProfiles("test")
class RuntimeSettingsModuleIntegrationTests {

    private static final String COLLECTION = "runtime_settings";
    private static final String OPERATOR = "U-admin-settings";

    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private RuntimeSettingsModule module;
    @Autowired
    private AppConfig appConfig;
    @Autowired
    private OpenAiConfig openAiConfig;
    @Autowired
    private GeminiConfig geminiConfig;

    @BeforeEach
    void cleanSettings() {
        if (mongoTemplate.collectionExists(COLLECTION)) {
            mongoTemplate.dropCollection(COLLECTION);
        }
    }

    @Test
    void validUpdatesPersistSchemaAuditAndReloadAcrossModuleRestart() {
        module.update(RuntimeSettingKey.DEFAULT_CHINESE_TARGET_LANGUAGE, "ja", OPERATOR);
        module.update(RuntimeSettingKey.DEFAULT_OTHER_TARGET_LANGUAGE, "zh-TW", OPERATOR);
        module.update(RuntimeSettingKey.DEFAULT_AI_PROVIDER, "gemini", OPERATOR);
        module.update(RuntimeSettingKey.OPENAI_DEFAULT_MODEL, "gpt-4o", OPERATOR);
        module.update(RuntimeSettingKey.GEMINI_DEFAULT_MODEL, "gemini-1.5-pro", OPERATOR);
        module.update(RuntimeSettingKey.OCR_ENABLED, "off", OPERATOR);

        RuntimeSettingsModule restarted = new RuntimeSettingsModule(
                mongoTemplate,
                appConfig,
                openAiConfig,
                geminiConfig,
                Clock.systemUTC());
        RuntimeSettings settings = restarted.current();

        assertThat(settings.defaultTargetLanguageForChinese()).isEqualTo("ja");
        assertThat(settings.defaultTargetLanguageForOthers()).isEqualTo("zh-TW");
        assertThat(settings.defaultAiProvider()).isEqualTo("gemini");
        assertThat(settings.openAiDefaultModel()).isEqualTo("gpt-4o");
        assertThat(settings.geminiDefaultModel()).isEqualTo("gemini-1.5-pro");
        assertThat(settings.ocrEnabled()).isFalse();
        assertThat(settings.source()).isEqualTo(RuntimeSettings.Source.PERSISTED);

        Document stored = mongoTemplate.getCollection(COLLECTION).find().first();
        assertThat(stored).isNotNull();
        assertThat(stored.getInteger("schemaVersion")).isEqualTo(1);
        assertThat(((Number) stored.get("revision")).longValue()).isEqualTo(6);
        List<Document> changes = stored.getList("changes", Document.class);
        assertThat(changes).hasSize(6).allSatisfy(change -> {
            assertThat(change.getString("updatedBy")).isEqualTo(OPERATOR);
            assertThat(change.get("updatedAt")).isNotNull();
            assertThat(change.keySet()).doesNotContain("secret", "token", "apiKey");
        });
    }

    @Test
    void invalidValuesAreRejectedBeforeAnyDocumentIsWritten() {
        assertThatThrownBy(() -> module.update(
                RuntimeSettingKey.DEFAULT_CHINESE_TARGET_LANGUAGE,
                "retired-language",
                OPERATOR)).isInstanceOf(InvalidRuntimeSettingException.class);
        assertThatThrownBy(() -> module.update(
                RuntimeSettingKey.DEFAULT_AI_PROVIDER,
                "retired-provider",
                OPERATOR)).isInstanceOf(InvalidRuntimeSettingException.class);
        assertThatThrownBy(() -> module.update(
                RuntimeSettingKey.OPENAI_DEFAULT_MODEL,
                "retired-model",
                OPERATOR)).isInstanceOf(InvalidRuntimeSettingException.class);
        assertThatThrownBy(() -> module.update(
                RuntimeSettingKey.OCR_ENABLED,
                "maybe",
                OPERATOR)).isInstanceOf(InvalidRuntimeSettingException.class);

        assertThat(mongoTemplate.collectionExists(COLLECTION)).isFalse();
    }

    @Test
    void missingDocumentUsesDeploymentDefaultsWithoutPersistingThem() {
        RuntimeSettings settings = module.current();

        assertThat(settings.defaultTargetLanguageForChinese())
                .isEqualTo(appConfig.getDefaultTargetLanguageForChinese());
        assertThat(settings.defaultTargetLanguageForOthers())
                .isEqualTo(appConfig.getDefaultTargetLanguageForOthers());
        assertThat(settings.defaultAiProvider()).isEqualTo(appConfig.getDefaultAiProvider());
        assertThat(settings.openAiDefaultModel()).isEqualTo(openAiConfig.getModelName());
        assertThat(settings.geminiDefaultModel()).isEqualTo(geminiConfig.getModelName());
        assertThat(settings.ocrEnabled()).isEqualTo(appConfig.isOcrEnabled());
        assertThat(settings.source()).isEqualTo(RuntimeSettings.Source.DEPLOYMENT_DEFAULTS);
        assertThat(mongoTemplate.collectionExists(COLLECTION)).isFalse();
    }
}
