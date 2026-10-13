package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderExecutionModule;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class LanguageDetectionSettingsTests {

    private static final String ENGLISH_TEXT =
            "The quick brown fox jumps over the lazy dog while the river flows past the old stone bridge.";
    private static final String CHINESE_TEXT = "今天天氣很好，我們一起去公園散步吧，晚上再一起吃飯。";

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void blankUseAiFallsBackToTheDefault(String blank) {
        assertThat(LanguageDetectionSettings.useAi(blank)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", " true ", "on", "yes", "1"})
    void truthyUseAiKeepsAiDetectionOn(String value) {
        assertThat(LanguageDetectionSettings.useAi(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", " false ", "off", "no", "0"})
    void falsyUseAiTurnsAiDetectionOff(String value) {
        assertThat(LanguageDetectionSettings.useAi(value)).isFalse();
    }

    @Test
    void anUnrecognizedUseAiValueStillFailsStartupInsteadOfSilentlyDisablingDetection() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LanguageDetectionSettings.useAi("ture"))
                .withMessageContaining("app.language-detection.use-ai");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void blankDefaultChineseFallsBackToTheDefault(String blank) {
        assertThat(LanguageDetectionSettings.defaultChinese(blank)).isEqualTo("zh-tw");
    }

    @Test
    void explicitDefaultChineseIsTrimmedAndKept() {
        assertThat(LanguageDetectionSettings.defaultChinese(" zh-cn ")).isEqualTo("zh-cn");
    }

    @Test
    void applicationPropertiesBindTheOperatorVariablesWithTheCodeDefaults() throws IOException {
        assertThat(resolve(Map.of(), "app.language-detection.use-ai")).isEqualTo("true");
        assertThat(resolve(Map.of(), "app.language-detection.default-chinese")).isEqualTo("zh-tw");

        Map<String, Object> configured = Map.of(
                "LANGUAGE_DETECTION_USE_AI", "false",
                "LANGUAGE_DETECTION_DEFAULT_CHINESE", "zh-cn");
        assertThat(resolve(configured, "app.language-detection.use-ai")).isEqualTo("false");
        assertThat(resolve(configured, "app.language-detection.default-chinese")).isEqualTo("zh-cn");
    }

    @Test
    void anEmptyVariableResolvesToBlankSoTheServicesMustTolerateIt() throws IOException {
        // A placeholder default only covers a missing variable; an empty one wins over it.
        Map<String, Object> empty = Map.of(
                "LANGUAGE_DETECTION_USE_AI", "",
                "LANGUAGE_DETECTION_DEFAULT_CHINESE", "");

        assertThat(resolve(empty, "app.language-detection.use-ai")).isEmpty();
        assertThat(resolve(empty, "app.language-detection.default-chinese")).isEmpty();
    }

    @Test
    void blankUseAiStartsTheServiceWithAiDetectionOnLikeTheDefault() {
        AiLanguageDetectionService ai = aiDetecting("fr");

        runner(ai).withPropertyValues("app.language-detection.use-ai=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LanguageDetectionService.class).detectLanguage(ENGLISH_TEXT))
                    .isEqualTo("fr");
            verify(ai).detectLanguage(ENGLISH_TEXT);
        });
    }

    @Test
    void unsetUseAiKeepsAiDetectionOn() {
        AiLanguageDetectionService ai = aiDetecting("fr");

        runner(ai).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LanguageDetectionService.class).detectLanguage(ENGLISH_TEXT))
                    .isEqualTo("fr");
            verify(ai).detectLanguage(ENGLISH_TEXT);
        });
    }

    @Test
    void explicitFalseTurnsAiDetectionOff() {
        AiLanguageDetectionService ai = aiDetecting("fr");

        runner(ai).withPropertyValues("app.language-detection.use-ai=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LanguageDetectionService.class).detectLanguage(ENGLISH_TEXT))
                    .isEqualTo("en");
            verify(ai, never()).detectLanguage(anyString());
        });
    }

    @Test
    void anInvalidUseAiValueFailsStartup() {
        runner(aiDetecting("fr")).withPropertyValues("app.language-detection.use-ai=ture")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void localDetectionReportsChineseWithTheConfiguredCode() {
        AiLanguageDetectionService ai = aiDetecting("fr");

        runner(ai).withPropertyValues(
                "app.language-detection.use-ai=false",
                "app.language-detection.default-chinese=zh-cn").run(context ->
                assertThat(context.getBean(LanguageDetectionService.class).detectLanguage(CHINESE_TEXT))
                        .isEqualTo("zh-cn"));
        runner(ai).withPropertyValues(
                "app.language-detection.use-ai=false",
                "app.language-detection.default-chinese=").run(context ->
                assertThat(context.getBean(LanguageDetectionService.class).detectLanguage(CHINESE_TEXT))
                        .isEqualTo("zh-tw"));
    }

    @Test
    void aiDetectionReportsChineseWithTheConfiguredCodeAndDefaultsWhenBlank() {
        AiProviderExecutionModule provider = mock(AiProviderExecutionModule.class);
        when(provider.generateTextOutcome(anyString(), anyString()))
                .thenReturn(new AiExecutionOutcome.Success(new AiExecutionResult("zh", "openai", "gpt-test")));
        ApplicationContextRunner aiRunner = new ApplicationContextRunner()
                .withUserConfiguration(AiLanguageDetectionService.class)
                .withBean(AiProviderExecutionModule.class, () -> provider)
                .withBean(TranslationPromptFactory.class, TranslationPromptFactory::new);

        aiRunner.withPropertyValues("app.language-detection.default-chinese=zh-cn").run(context ->
                assertThat(context.getBean(AiLanguageDetectionService.class).detectLanguage(CHINESE_TEXT))
                        .isEqualTo("zh-cn"));
        aiRunner.withPropertyValues("app.language-detection.default-chinese=").run(context ->
                assertThat(context.getBean(AiLanguageDetectionService.class).detectLanguage(CHINESE_TEXT))
                        .isEqualTo("zh-tw"));
    }

    @Test
    void deploymentPassesTheSettingsAsVariablesWithTheCodeDefaults() throws IOException {
        // Variables, not secrets, and never blank: an unset variable falls back to the code default.
        for (String workflow : new String[] {"ci-cd.yml", "rotate-mongodb-credential.yml"}) {
            assertThat(Files.readString(Path.of(".github", "workflows", workflow)))
                    .as(workflow)
                    .contains("LANGUAGE_DETECTION_USE_AI: ${{ vars.LANGUAGE_DETECTION_USE_AI || 'true' }}")
                    .contains("LANGUAGE_DETECTION_DEFAULT_CHINESE: "
                            + "${{ vars.LANGUAGE_DETECTION_DEFAULT_CHINESE || 'zh-tw' }}")
                    .doesNotContain("secrets.LANGUAGE_DETECTION");
        }
    }

    private static ApplicationContextRunner runner(AiLanguageDetectionService ai) {
        return new ApplicationContextRunner()
                .withUserConfiguration(LanguageDetectionService.class)
                .withBean(AiLanguageDetectionService.class, () -> ai)
                .withBean(LanguageDetectionCache.class, () -> new LanguageDetectionCache(
                        new TranslationCacheProperties(), new SimpleMeterRegistry()));
    }

    private static AiLanguageDetectionService aiDetecting(String language) {
        AiLanguageDetectionService ai = mock(AiLanguageDetectionService.class);
        when(ai.detectLanguage(anyString())).thenReturn(language);
        return ai;
    }

    /** Resolves a property from the real application.properties against only the given variables. */
    private static String resolve(Map<String, Object> variables, String property) throws IOException {
        Properties application = new Properties();
        try (InputStream stream = new ClassPathResource("application.properties").getInputStream()) {
            application.load(stream);
        }
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addLast(new MapPropertySource("variables", variables));
        sources.addLast(new PropertiesPropertySource("application", application));
        return environment.getProperty(property);
    }
}
