package com.linetranslate.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linetranslate.bot.config.OpenRouterConfig;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiModelCatalog;
import com.linetranslate.bot.service.line.LineUserProfileService;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.settings.RuntimeSettingsModule;
import com.linetranslate.bot.service.usage.AiUsageAccountingModule;
import com.linetranslate.bot.service.usage.UsageReportRenderer;

/**
 * APP_BROADCAST_TEST_MODE reaches the container as an empty string when the deploy workflow passes an
 * unset value; a primitive boolean cannot be built from that, so a blank value must mean the default.
 */
class BroadcastSettingsTests {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void blankTestModeFallsBackToTheDefaultOff(String blank) {
        assertThat(BroadcastSettings.testMode(blank)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", " true ", "on", "yes", "1"})
    void truthyTestModeTurnsSimulationOn(String value) {
        assertThat(BroadcastSettings.testMode(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", " false ", "off", "no", "0"})
    void falsyTestModeKeepsRealBroadcasts(String value) {
        assertThat(BroadcastSettings.testMode(value)).isFalse();
    }

    @Test
    void anUnrecognizedTestModeStillFailsStartupInsteadOfSilentlyBroadcastingForReal() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BroadcastSettings.testMode("ture"))
                .withMessageContaining("app.broadcast.test-mode");
    }

    @Test
    void applicationPropertiesBindTheOperatorVariableWithTheCodeDefault() throws IOException {
        assertThat(resolve(Map.of())).isEqualTo("false");
        assertThat(resolve(Map.of("APP_BROADCAST_TEST_MODE", "true"))).isEqualTo("true");
        // A placeholder default only covers a missing variable; an empty one wins over it.
        assertThat(resolve(Map.of("APP_BROADCAST_TEST_MODE", ""))).isEmpty();
    }

    @Test
    void anUnsetSettingKeepsRealBroadcasts() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(testMode(context.getBean(AdminService.class))).isFalse();
        });
    }

    @Test
    void aBlankSettingStartsTheServiceWithTheDefaultInsteadOfFailingStartup() {
        runner().withPropertyValues("app.broadcast.test-mode=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(testMode(context.getBean(AdminService.class))).isFalse();
        });
    }

    @Test
    void anEmptyEnvironmentVariableStartsTheServiceThroughTheRealApplicationProperties() {
        // Same chain as production: container env APP_BROADCAST_TEST_MODE= -> application.properties -> @Value.
        runner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("APP_BROADCAST_TEST_MODE=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(testMode(context.getBean(AdminService.class))).isFalse();
                });
    }

    @Test
    void trueAndFalseKeepTheirMeaning() {
        runner().withPropertyValues("app.broadcast.test-mode=true").run(context ->
                assertThat(testMode(context.getBean(AdminService.class))).isTrue());
        runner().withPropertyValues("app.broadcast.test-mode=false").run(context ->
                assertThat(testMode(context.getBean(AdminService.class))).isFalse());
        runner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("APP_BROADCAST_TEST_MODE=true")
                .run(context -> assertThat(testMode(context.getBean(AdminService.class))).isTrue());
    }

    @Test
    void theRawEnvironmentNameStillWorksWhenApplicationPropertiesAreNotLoaded() {
        runner().withPropertyValues("APP_BROADCAST_TEST_MODE=true").run(context ->
                assertThat(testMode(context.getBean(AdminService.class))).isTrue());
    }

    @Test
    void anInvalidSettingFailsStartup() {
        runner().withPropertyValues("app.broadcast.test-mode=ture")
                .run(context -> assertThat(context).hasFailed());
    }

    private static boolean testMode(AdminService service) {
        return (boolean) ReflectionTestUtils.getField(service, "broadcastTestMode");
    }

    private static ApplicationContextRunner runner() {
        // Registered as ready-made singletons so Spring does not try to inject the mocks' own @Value fields.
        return new ApplicationContextRunner()
                .withUserConfiguration(AdminService.class)
                .withInitializer(context -> {
                    ConfigurableListableBeanFactory beans = context.getBeanFactory();
                    for (Class<?> dependency : List.of(
                            TranslationRecordRepository.class, UserProfileRepository.class,
                            MessagingApiClient.class, OpenRouterConfig.class, AiModelCatalog.class,
                            LineUserProfileService.class, UserPreferencesModule.class,
                            RuntimeSettingsModule.class, AiUsageAccountingModule.class,
                            UsageReportRenderer.class)) {
                        beans.registerSingleton(dependency.getSimpleName(), mock(dependency));
                    }
                });
    }

    /** Resolves the property from the real application.properties against only the given variables. */
    private static String resolve(Map<String, Object> variables) throws IOException {
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
        return environment.getProperty("app.broadcast.test-mode");
    }
}
