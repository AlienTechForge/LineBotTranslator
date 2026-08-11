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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.linetranslate.bot.config.AppConfig;
import com.linetranslate.bot.config.OpenRouterConfig;
import com.linetranslate.bot.service.ai.AiModelCatalog;

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
    @Autowired private OpenRouterConfig openRouterConfig;
    @MockitoBean private AiModelCatalog modelCatalog;

    @BeforeEach
    void cleanSettings() {
        if (mongoTemplate.collectionExists(COLLECTION)) {
            mongoTemplate.dropCollection(COLLECTION);
        }
        org.mockito.Mockito.when(modelCatalog.contains("openai/gpt-4o-mini")).thenReturn(true);
    }

    @Test
    void validUpdatesPersistSchemaAuditAndReloadAcrossModuleRestart() {
        module.update(RuntimeSettingKey.DEFAULT_CHINESE_TARGET_LANGUAGE, "ja", OPERATOR);
        module.update(RuntimeSettingKey.DEFAULT_OTHER_TARGET_LANGUAGE, "zh-TW", OPERATOR);
        module.update(RuntimeSettingKey.OPENROUTER_DEFAULT_MODEL, "openai/gpt-4o-mini", OPERATOR);
        module.update(RuntimeSettingKey.OCR_ENABLED, "off", OPERATOR);

        RuntimeSettingsModule restarted = new RuntimeSettingsModule(
                mongoTemplate,
                appConfig,
                openRouterConfig,
                modelCatalog,
                Clock.systemUTC());
        RuntimeSettings settings = restarted.current();

        assertThat(settings.defaultTargetLanguageForChinese()).isEqualTo("ja");
        assertThat(settings.defaultTargetLanguageForOthers()).isEqualTo("zh-TW");
        assertThat(settings.openRouterDefaultModel()).isEqualTo("openai/gpt-4o-mini");
        assertThat(settings.ocrEnabled()).isFalse();
        assertThat(settings.source()).isEqualTo(RuntimeSettings.Source.PERSISTED);

        Document stored = mongoTemplate.getCollection(COLLECTION).find().first();
        assertThat(stored).isNotNull();
        assertThat(stored.getInteger("schemaVersion")).isEqualTo(2);
        assertThat(((Number) stored.get("revision")).longValue()).isEqualTo(4);
        List<Document> changes = stored.getList("changes", Document.class);
        assertThat(changes).hasSize(4).allSatisfy(change -> {
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
                RuntimeSettingKey.OPENROUTER_DEFAULT_MODEL,
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
        assertThat(settings.openRouterDefaultModel()).isEqualTo(openRouterConfig.getModelName());
        assertThat(settings.ocrEnabled()).isEqualTo(appConfig.isOcrEnabled());
        assertThat(settings.source()).isEqualTo(RuntimeSettings.Source.DEPLOYMENT_DEFAULTS);
        assertThat(mongoTemplate.collectionExists(COLLECTION)).isFalse();
    }
}
