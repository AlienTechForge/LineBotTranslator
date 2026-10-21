package com.linetranslate.bot.service.imagetranslator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ImageTranslatorPropertiesTests {

    @Test
    void theDefaultsPointAtTheServiceOfTheSameContainer() {
        ImageTranslatorProperties properties = new ImageTranslatorProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getUrl()).isEqualTo("http://127.0.0.1:4041");
        assertThat(properties.getTimeout()).isEqualTo(Duration.ofMinutes(4));
        assertThat(properties.hasToken()).isFalse();
    }

    @Test
    void aBlankUrlAndAMissingTimeoutFallBackToTheDefaults() {
        ImageTranslatorProperties properties = new ImageTranslatorProperties();
        properties.setUrl("  ");
        properties.setTimeout(null);
        properties.setToken(null);

        assertThat(properties.getUrl()).isEqualTo(ImageTranslatorProperties.DEFAULT_URL);
        assertThat(properties.getTimeout()).isEqualTo(ImageTranslatorProperties.DEFAULT_TIMEOUT);
        assertThat(properties.getToken()).isEmpty();
    }

    @Test
    void aTimeoutThatIsNotPositiveIsRefused() {
        ImageTranslatorProperties properties = new ImageTranslatorProperties();

        assertThatThrownBy(() -> properties.setTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theBaseUrlHasNoTrailingSlash() {
        ImageTranslatorProperties properties = new ImageTranslatorProperties();
        properties.setUrl(" http://127.0.0.1:4041/// ");

        assertThat(properties.baseUrl()).isEqualTo("http://127.0.0.1:4041");
    }

    @Test
    void theTokenIsNeverPrinted() {
        ImageTranslatorProperties properties = new ImageTranslatorProperties();
        properties.setToken("s3cret-token-value");

        assertThat(properties.toString()).doesNotContain("s3cret-token-value").contains("token=set");
        assertThat(properties.hasToken()).isTrue();
    }

    @Test
    void bindsFromTheEnvironmentStyleKeys() {
        new ApplicationContextRunner()
                .withUserConfiguration(Binding.class)
                .withPropertyValues(
                        "app.image-translator.enabled=false",
                        "app.image-translator.url=http://127.0.0.1:5055/",
                        "app.image-translator.token=abc",
                        "app.image-translator.timeout=PT90S")
                .run(context -> {
                    ImageTranslatorProperties bound = context.getBean(ImageTranslatorProperties.class);
                    assertThat(bound.isEnabled()).isFalse();
                    assertThat(bound.baseUrl()).isEqualTo("http://127.0.0.1:5055");
                    assertThat(bound.getToken()).isEqualTo("abc");
                    assertThat(bound.getTimeout()).isEqualTo(Duration.ofSeconds(90));
                });
    }

    @Test
    void emptyValuesFromAnEnvironmentFileKeepTheDefaults() {
        new ApplicationContextRunner()
                .withUserConfiguration(Binding.class)
                .withPropertyValues(
                        "app.image-translator.url=",
                        "app.image-translator.token=",
                        "app.image-translator.timeout=")
                .run(context -> {
                    ImageTranslatorProperties bound = context.getBean(ImageTranslatorProperties.class);
                    assertThat(bound.getUrl()).isEqualTo(ImageTranslatorProperties.DEFAULT_URL);
                    assertThat(bound.getTimeout()).isEqualTo(ImageTranslatorProperties.DEFAULT_TIMEOUT);
                    assertThat(bound.hasToken()).isFalse();
                });
    }

    @EnableConfigurationProperties(ImageTranslatorProperties.class)
    static class Binding {
    }
}
