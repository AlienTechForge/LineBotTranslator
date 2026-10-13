package com.linetranslate.bot.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationValidatorTests {

    @Test
    void acceptsCompleteCoreConfiguration() {
        MockEnvironment environment = completeEnvironment();

        assertThatCode(() -> new ProductionConfigurationValidator(environment).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void reportsOnlyTheMissingSettingName() {
        MockEnvironment environment = completeEnvironment()
                .withProperty("line.bot.channel-secret", "");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LINE_BOT_CHANNEL_SECRET")
                .hasMessageNotContaining("seeded-channel-token")
                .hasMessageNotContaining("seeded-mongo-password");
    }

    @Test
    void rejectsFactoryMinioCredentialsByNameOnly() {
        MockEnvironment environment = completeEnvironment()
                .withProperty("minio.enabled", "true")
                .withProperty("minio.access-key", "minioadmin")
                .withProperty("minio.secret-key", "minioadmin");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Insecure production configuration: MINIO_ACCESS_KEY, MINIO_SECRET_KEY")
                .hasMessageNotContaining("minioadmin");
    }

    @Test
    void rejectsFactoryMinioSecretEvenWithACustomAccessKey() {
        MockEnvironment environment = completeEnvironment()
                .withProperty("minio.secret-key", "minioadmin");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Insecure production configuration: MINIO_SECRET_KEY")
                .hasMessageNotContaining("seeded-minio-access")
                .hasMessageNotContaining("minioadmin");
    }

    @Test
    void rejectsBlankMinioCredentialsWhenStorageIsEnabledByDefault() {
        MockEnvironment environment = completeEnvironment()
                .withProperty("minio.access-key", "")
                .withProperty("minio.secret-key", "  ");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing required production configuration: MINIO_ACCESS_KEY, MINIO_SECRET_KEY");
    }

    @Test
    void reportsMissingAndInsecureSettingsTogether() {
        MockEnvironment environment = completeEnvironment()
                .withProperty("openrouter.api.key", "")
                .withProperty("minio.access-key", "minioadmin")
                .withProperty("minio.secret-key", "minioadmin");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing required production configuration: OPEN_ROUTE_API_KEY; "
                        + "Insecure production configuration: MINIO_ACCESS_KEY, MINIO_SECRET_KEY");
    }

    @Test
    void ignoresMinioCredentialsWhenStorageIsDisabled() {
        MockEnvironment factoryCredentials = completeEnvironment()
                .withProperty("minio.enabled", "false")
                .withProperty("minio.access-key", "minioadmin")
                .withProperty("minio.secret-key", "minioadmin");
        MockEnvironment blankCredentials = completeEnvironment()
                .withProperty("minio.enabled", "false")
                .withProperty("minio.access-key", "")
                .withProperty("minio.secret-key", "");
        MockEnvironment disabledThroughEnvironmentVariable = completeEnvironment()
                .withProperty("MINIO_ENABLED", "false")
                .withProperty("minio.access-key", "minioadmin")
                .withProperty("minio.secret-key", "minioadmin");

        assertThatCode(() -> new ProductionConfigurationValidator(factoryCredentials).validate())
                .doesNotThrowAnyException();
        assertThatCode(() -> new ProductionConfigurationValidator(blankCredentials).validate())
                .doesNotThrowAnyException();
        assertThatCode(() -> new ProductionConfigurationValidator(disabledThroughEnvironmentVariable).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsOperatorChosenMinioCredentials() {
        MockEnvironment environment = completeEnvironment()
                .withProperty("minio.enabled", "true")
                .withProperty("minio.access-key", "linebot-app")
                .withProperty("minio.secret-key", "k3J9-operator-chosen-secret");
        MockEnvironment defaultUserNameWithChosenSecret = completeEnvironment()
                .withProperty("minio.access-key", "minioadmin");

        assertThatCode(() -> new ProductionConfigurationValidator(environment).validate())
                .doesNotThrowAnyException();
        assertThatCode(() -> new ProductionConfigurationValidator(defaultUserNameWithChosenSecret).validate())
                .doesNotThrowAnyException();
    }

    private static MockEnvironment completeEnvironment() {
        return new MockEnvironment()
                .withProperty("line.bot.channel-token", "seeded-channel-token")
                .withProperty("line.bot.channel-secret", "seeded-channel-secret")
                .withProperty(
                        "mongodb.uri",
                        "mongodb://seeded-user:seeded-mongo-password@mongo.example:27017/app")
                .withProperty("mongodb.database", "linebot-translator")
                .withProperty("openrouter.api.key", "seeded-openrouter-key")
                .withProperty("minio.access-key", "seeded-minio-access")
                .withProperty("minio.secret-key", "seeded-minio-secret");
    }
}
