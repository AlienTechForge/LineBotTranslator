package com.linetranslate.bot.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.linetranslate.bot.config.AppConfig;
import com.linetranslate.bot.config.GeminiConfig;
import com.linetranslate.bot.config.OpenAiConfig;

class RuntimeSettingsFallbackTests {

    @Test
    void mongoReadFailureSafelyReturnsDeploymentDefaults() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        AppConfig appConfig = mock(AppConfig.class);
        OpenAiConfig openAiConfig = mock(OpenAiConfig.class);
        GeminiConfig geminiConfig = mock(GeminiConfig.class);
        when(appConfig.getDefaultTargetLanguageForChinese()).thenReturn("en");
        when(appConfig.getDefaultTargetLanguageForOthers()).thenReturn("zh-TW");
        when(appConfig.getDefaultAiProvider()).thenReturn("openai");
        when(appConfig.isOcrEnabled()).thenReturn(true);
        when(openAiConfig.getModelName()).thenReturn("gpt-default");
        when(openAiConfig.getAvailableModels()).thenReturn(List.of("gpt-default"));
        when(geminiConfig.getModelName()).thenReturn("gemini-default");
        when(geminiConfig.getAvailableModels()).thenReturn(List.of("gemini-default"));
        when(mongoTemplate.findById(
                RuntimeSettingsModule.DOCUMENT_ID,
                RuntimeSettingsDocument.class))
                .thenThrow(new IllegalStateException("mongo unavailable"));

        RuntimeSettings settings = new RuntimeSettingsModule(
                mongoTemplate,
                appConfig,
                openAiConfig,
                geminiConfig,
                Clock.systemUTC()).current();

        assertThat(settings.defaultTargetLanguageForChinese()).isEqualTo("en");
        assertThat(settings.defaultTargetLanguageForOthers()).isEqualTo("zh-TW");
        assertThat(settings.defaultAiProvider()).isEqualTo("openai");
        assertThat(settings.openAiDefaultModel()).isEqualTo("gpt-default");
        assertThat(settings.geminiDefaultModel()).isEqualTo("gemini-default");
        assertThat(settings.ocrEnabled()).isTrue();
        assertThat(settings.source()).isEqualTo(RuntimeSettings.Source.DEPLOYMENT_DEFAULTS);
    }
}
