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
        module.update(RuntimeSettingKey.SHORT_URL_ENABLED, "on", OPERATOR);
        module.update(RuntimeSettingKey.IMAGE_TEXT_REPLY_ENABLED, "off", OPERATOR);

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
        assertThat(settings.shortUrlEnabled()).isTrue();
        assertThat(settings.imageTextReplyEnabled()).isFalse();
        assertThat(settings.source()).isEqualTo(RuntimeSettings.Source.PERSISTED);

        Document stored = mongoTemplate.getCollection(COLLECTION).find().first();
        assertThat(stored).isNotNull();
        assertThat(stored.getInteger("schemaVersion")).isEqualTo(4);
        assertThat(stored.getBoolean("imageTextReplyEnabled")).isFalse();
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
                RuntimeSettingKey.OPENROUTER_DEFAULT_MODEL,
                "retired-model",
                OPERATOR)).isInstanceOf(InvalidRuntimeSettingException.class);
        assertThatThrownBy(() -> module.update(
                RuntimeSettingKey.OCR_ENABLED,
                "maybe",
                OPERATOR)).isInstanceOf(InvalidRuntimeSettingException.class);
        assertThatThrownBy(() -> module.update(
                RuntimeSettingKey.IMAGE_TEXT_REPLY_ENABLED,
                "maybe",
                OPERATOR)).isInstanceOf(InvalidRuntimeSettingException.class);

        assertThat(mongoTemplate.collectionExists(COLLECTION)).isFalse();
    }

    @Test
    void imageTextReplySwitchAcceptsTheSameValuesAsTheOtherSwitchesAndRecordsTheChange() {
        // Picture-only replies are the default; an administrator can turn the text reply on and off again.
        assertThat(module.current().imageTextReplyEnabled()).isFalse();

        RuntimeSettings on = module.update(RuntimeSettingKey.IMAGE_TEXT_REPLY_ENABLED, "ON", OPERATOR);
        assertThat(on.imageTextReplyEnabled()).isTrue();
        assertThat(on.shortUrlEnabled()).isEqualTo(appConfig.isShortUrlEnabled());
        RuntimeSettings off = module.update(RuntimeSettingKey.IMAGE_TEXT_REPLY_ENABLED, "關", OPERATOR);
        assertThat(off.imageTextReplyEnabled()).isFalse();
        assertThat(off.revision()).isEqualTo(2);

        Document stored = mongoTemplate.getCollection(COLLECTION).find().first();
        List<Document> changes = stored.getList("changes", Document.class);
        assertThat(changes).extracting(change -> change.getString("key"))
                .containsExactly("IMAGE_TEXT_REPLY_ENABLED", "IMAGE_TEXT_REPLY_ENABLED");
        assertThat(changes.get(0).getBoolean("previousValue")).isFalse();
        assertThat(changes.get(0).getBoolean("newValue")).isTrue();
        assertThat(changes.get(1).getBoolean("previousValue")).isTrue();
        assertThat(changes.get(1).getBoolean("newValue")).isFalse();
    }

    @Test
    void schemaThreeDocumentWithoutTheSwitchKeepsDeploymentDefaultUntilFirstUpdate() {
        mongoTemplate.getCollection(COLLECTION).insertOne(new Document("_id", RuntimeSettingsModule.DOCUMENT_ID)
                .append("schemaVersion", 3)
                .append("shortUrlEnabled", true)
                .append("revision", 2L));

        RuntimeSettings legacy = module.current();
        assertThat(legacy.source()).isEqualTo(RuntimeSettings.Source.PERSISTED);
        assertThat(legacy.shortUrlEnabled()).isTrue();
        assertThat(legacy.imageTextReplyEnabled()).isEqualTo(appConfig.isImageTextReplyEnabled());

        RuntimeSettings updated = module.update(RuntimeSettingKey.IMAGE_TEXT_REPLY_ENABLED, "off", OPERATOR);

        assertThat(updated.imageTextReplyEnabled()).isFalse();
        assertThat(updated.shortUrlEnabled()).isTrue();
        assertThat(updated.revision()).isEqualTo(3);
        Document stored = mongoTemplate.getCollection(COLLECTION).find().first();
        assertThat(stored.getInteger("schemaVersion")).isEqualTo(4);
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
        assertThat(settings.shortUrlEnabled()).isEqualTo(appConfig.isShortUrlEnabled());
        assertThat(settings.imageTextReplyEnabled()).isEqualTo(appConfig.isImageTextReplyEnabled());
        assertThat(settings.imageTextReplyEnabled()).isFalse();
        assertThat(settings.adminUserIds()).isEmpty();
        assertThat(settings.source()).isEqualTo(RuntimeSettings.Source.DEPLOYMENT_DEFAULTS);
        assertThat(mongoTemplate.collectionExists(COLLECTION)).isFalse();
    }

    @Test
    void adminUserIdsPersistWithAuditAndReloadAcrossModuleRestart() {
        RuntimeSettings first = module.updateAdminUserIds(
                List.of("U-admin-a", "U-admin-b", "U-admin-a"), OPERATOR);

        assertThat(first.adminUserIds()).containsExactly("U-admin-a", "U-admin-b");
        assertThat(first.source()).isEqualTo(RuntimeSettings.Source.PERSISTED);
        assertThat(first.updatedBy()).isEqualTo(OPERATOR);

        module.updateAdminUserIds(List.of("U-admin-b"), "U-admin-b");

        RuntimeSettings reloaded = restartedModule().current();
        assertThat(reloaded.adminUserIds()).containsExactly("U-admin-b");
        assertThat(reloaded.revision()).isEqualTo(2);
        assertThat(reloaded.updatedBy()).isEqualTo("U-admin-b");
        assertThat(reloaded.source()).isEqualTo(RuntimeSettings.Source.PERSISTED);

        Document stored = mongoTemplate.getCollection(COLLECTION).find().first();
        assertThat(stored).isNotNull();
        assertThat(stored.getInteger("schemaVersion")).isEqualTo(4);
        assertThat(stored.getList("adminUserIds", String.class)).containsExactly("U-admin-b");
        List<Document> changes = stored.getList("changes", Document.class);
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).getString("key")).isEqualTo("ADMIN_USER_IDS");
        assertThat(changes.get(0).getList("previousValue", String.class)).isEmpty();
        assertThat(changes.get(0).getList("newValue", String.class))
                .containsExactly("U-admin-a", "U-admin-b");
        assertThat(changes.get(0).getString("updatedBy")).isEqualTo(OPERATOR);
        assertThat(changes.get(1).getList("previousValue", String.class))
                .containsExactly("U-admin-a", "U-admin-b");
        assertThat(changes.get(1).getList("newValue", String.class))
                .containsExactly("U-admin-b");
        assertThat(changes.get(1).getString("updatedBy")).isEqualTo("U-admin-b");
    }

    @Test
    void adminUserIdsUpdateKeepsOtherSettingsAndUpgradesAnEarlierSchema() {
        mongoTemplate.getCollection(COLLECTION).insertOne(new Document("_id", RuntimeSettingsModule.DOCUMENT_ID)
                .append("schemaVersion", 3)
                .append("ocrEnabled", false)
                .append("shortUrlEnabled", true)
                .append("revision", 4L)
                .append("changes", List.of()));

        RuntimeSettings legacy = module.current();
        assertThat(legacy.adminUserIds()).isEmpty();
        assertThat(legacy.ocrEnabled()).isFalse();

        RuntimeSettings updated = module.updateAdminUserIds(List.of("U-admin-a"), OPERATOR);

        assertThat(updated.adminUserIds()).containsExactly("U-admin-a");
        assertThat(updated.ocrEnabled()).isFalse();
        assertThat(updated.shortUrlEnabled()).isTrue();
        assertThat(updated.revision()).isEqualTo(5);
        Document stored = mongoTemplate.getCollection(COLLECTION).find().first();
        assertThat(stored).isNotNull();
        assertThat(stored.getInteger("schemaVersion")).isEqualTo(4);
    }

    @Test
    void currentSchemaDocumentWithoutAdminUserIdsHasNoPersistedAdministrators() {
        mongoTemplate.getCollection(COLLECTION).insertOne(new Document("_id", RuntimeSettingsModule.DOCUMENT_ID)
                .append("schemaVersion", 4)
                .append("ocrEnabled", false)
                .append("revision", 1L)
                .append("changes", List.of()));

        RuntimeSettings settings = module.current();

        assertThat(settings.adminUserIds()).isEmpty();
        assertThat(settings.ocrEnabled()).isFalse();
        assertThat(settings.imageTextReplyEnabled()).isEqualTo(appConfig.isImageTextReplyEnabled());
        assertThat(settings.source()).isEqualTo(RuntimeSettings.Source.PERSISTED);
    }

    @Test
    void administratorListAndImageTextSwitchCoexistInTheCurrentSchema() {
        module.update(RuntimeSettingKey.IMAGE_TEXT_REPLY_ENABLED, "off", OPERATOR);
        module.updateAdminUserIds(List.of("U-admin-a", "U-admin-b"), OPERATOR);

        RuntimeSettings settings = restartedModule().current();

        assertThat(settings.imageTextReplyEnabled()).isFalse();
        assertThat(settings.adminUserIds()).containsExactly("U-admin-a", "U-admin-b");
        assertThat(settings.revision()).isEqualTo(2);

        module.update(RuntimeSettingKey.IMAGE_TEXT_REPLY_ENABLED, "on", OPERATOR);

        RuntimeSettings afterSwitch = restartedModule().current();
        assertThat(afterSwitch.imageTextReplyEnabled()).isTrue();
        assertThat(afterSwitch.adminUserIds()).containsExactly("U-admin-a", "U-admin-b");
    }

    @Test
    void otherSettingUpdatesLeaveThePersistedAdministratorsAlone() {
        module.updateAdminUserIds(List.of("U-admin-a", "U-admin-b"), OPERATOR);

        module.update(RuntimeSettingKey.OCR_ENABLED, "off", OPERATOR);

        assertThat(restartedModule().current().adminUserIds())
                .containsExactly("U-admin-a", "U-admin-b");
    }

    @Test
    void invalidAdministratorListsAreRejectedBeforeAnyDocumentIsWritten() {
        assertThatThrownBy(() -> module.updateAdminUserIds(List.of(), OPERATOR))
                .isInstanceOf(InvalidRuntimeSettingException.class);
        assertThatThrownBy(() -> module.updateAdminUserIds(null, OPERATOR))
                .isInstanceOf(InvalidRuntimeSettingException.class);
        assertThatThrownBy(() -> module.updateAdminUserIds(List.of("  "), OPERATOR))
                .isInstanceOf(InvalidRuntimeSettingException.class);
        assertThatThrownBy(() -> module.updateAdminUserIds(List.of("U".repeat(129)), OPERATOR))
                .isInstanceOf(InvalidRuntimeSettingException.class);
        assertThatThrownBy(() -> module.updateAdminUserIds(List.of("U-admin-a"), " "))
                .isInstanceOf(InvalidRuntimeSettingException.class);

        assertThat(mongoTemplate.collectionExists(COLLECTION)).isFalse();
    }

    private RuntimeSettingsModule restartedModule() {
        return new RuntimeSettingsModule(
                mongoTemplate,
                appConfig,
                openRouterConfig,
                modelCatalog,
                Clock.systemUTC());
    }
}
