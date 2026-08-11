package com.linetranslate.bot.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.linetranslate.bot.config.AppConfig;
import com.linetranslate.bot.config.OpenRouterConfig;
import com.linetranslate.bot.service.ai.AiModelCatalog;

class RuntimeSettingsFallbackTests {

    @Test
    void mongoReadFailureSafelyReturnsDeploymentDefaults() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        AppConfig appConfig = mock(AppConfig.class);
        OpenRouterConfig openRouterConfig = mock(OpenRouterConfig.class);
        AiModelCatalog modelCatalog = mock(AiModelCatalog.class);
        when(appConfig.getDefaultTargetLanguageForChinese()).thenReturn("en");
        when(appConfig.getDefaultTargetLanguageForOthers()).thenReturn("zh-TW");
        when(appConfig.isOcrEnabled()).thenReturn(true);
        when(openRouterConfig.getModelName()).thenReturn("openai/gpt-4o-mini");
        when(mongoTemplate.findById(
                RuntimeSettingsModule.DOCUMENT_ID,
                RuntimeSettingsDocument.class))
                .thenThrow(new IllegalStateException("mongo unavailable"));

        RuntimeSettings settings = new RuntimeSettingsModule(
                mongoTemplate,
                appConfig,
                openRouterConfig,
                modelCatalog,
                Clock.systemUTC()).current();

        assertThat(settings.defaultTargetLanguageForChinese()).isEqualTo("en");
        assertThat(settings.defaultTargetLanguageForOthers()).isEqualTo("zh-TW");
        assertThat(settings.openRouterDefaultModel()).isEqualTo("openai/gpt-4o-mini");
        assertThat(settings.ocrEnabled()).isTrue();
        assertThat(settings.source()).isEqualTo(RuntimeSettings.Source.DEPLOYMENT_DEFAULTS);
    }
}
