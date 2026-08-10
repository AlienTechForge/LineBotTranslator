package com.linetranslate.bot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OpenAiConfigCompatibilityTests {

    @Test
    void configuredClientCanBeConstructedWithManagedJacksonVersion() {
        OpenAiConfig config = new OpenAiConfig();
        ReflectionTestUtils.setField(config, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(config, "apiUrl", "https://api.openai.com/v1");

        assertThatCode(config::openAiClient).doesNotThrowAnyException();
    }

    @Test
    void legacyChatCompletionsEndpointIsConvertedToApiBaseUrl() {
        assertThat(OpenAiConfig.normalizeBaseUrl("https://api.openai.com/v1/chat/completions"))
                .isEqualTo("https://api.openai.com/v1");
    }

    @Test
    void blankEndpointUsesOfficialApiBaseUrl() {
        assertThat(OpenAiConfig.normalizeBaseUrl("  "))
                .isEqualTo("https://api.openai.com/v1");
    }
}
