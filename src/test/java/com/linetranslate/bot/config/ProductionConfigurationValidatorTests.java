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

    private static MockEnvironment completeEnvironment() {
        return new MockEnvironment()
                .withProperty("line.bot.channel-token", "seeded-channel-token")
                .withProperty("line.bot.channel-secret", "seeded-channel-secret")
                .withProperty(
                        "mongodb.uri",
                        "mongodb://seeded-user:seeded-mongo-password@mongo.example:27017/app")
                .withProperty("mongodb.database", "linebot-translator");
    }
}
