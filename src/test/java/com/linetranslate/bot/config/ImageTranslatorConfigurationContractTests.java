package com.linetranslate.bot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/** What the shipped configuration files say about image translation (the files are read as text). */
class ImageTranslatorConfigurationContractTests {

    private static final Path PROPERTIES = Path.of("src", "main", "resources", "application.properties");

    @Test
    void theServiceIsConfiguredFromTheEnvironmentWithLoopbackAndFourMinuteDefaults() throws IOException {
        String properties = Files.readString(PROPERTIES);

        assertThat(properties)
                .contains("app.image-translator.enabled=${IMAGE_TRANSLATOR_ENABLED:true}")
                .contains("app.image-translator.url=${IMAGE_TRANSLATOR_URL:http://127.0.0.1:4041}")
                .contains("app.image-translator.token=${IMAGE_TRANSLATOR_TOKEN:}")
                .contains("app.image-translator.timeout=${IMAGE_TRANSLATOR_TIMEOUT:PT4M}");
    }

    @Test
    void theImageTranslatorIsCheckedByLivenessAndNotByReadiness() throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(Files.readString(PROPERTIES)));

        assertThat(members(properties, "management.endpoint.health.group.liveness.include"))
                .contains("livenessState", "imageTranslator");
        assertThat(members(properties, "management.endpoint.health.group.readiness.include"))
                .contains("readinessState", "lineConfiguration", "mongo", "minio", "openRouterConfiguration")
                .doesNotContain("imageTranslator", "ocrConfiguration");
    }

    @Test
    void thePictureIsTheWholeReplyUnlessTheTextReplyIsAskedFor() throws IOException {
        assertThat(Files.readString(PROPERTIES))
                .contains("app.image-translation.text-reply-enabled=${IMAGE_TEXT_REPLY_ENABLED:false}");
    }

    @Test
    void theDeliveryLimitsOfThePictureStay() throws IOException {
        assertThat(Files.readString(PROPERTIES))
                .contains("app.image-translation.max-file-size-bytes=")
                .contains("app.image-translation.max-dimension=")
                .contains("app.image-translation.max-pixels=")
                .contains("app.image-translation.max-output-bytes=")
                .contains("app.image-translation.delivery-target-bytes=")
                .contains("app.image-translation.cleanup-interval=")
                .contains("app.image-translation.proxy-public-base-url=");
    }

    @Test
    void theOverlayAndVisionSettingsOfTheRemovedJavaPipelineAreGone() throws IOException {
        String main = Files.readString(PROPERTIES);

        assertThat(main).doesNotContain(
                "low-confidence-threshold", "overlay-enabled", "max-region-area-ratio", "max-total-mask-ratio",
                "poster-max-total-mask-ratio", "orientation-retry-enabled", "google.cloud.vision",
                "GOOGLE_VISION_TIMEOUT_MS", "IMAGE_TRANSLATION_OVERLAY_ENABLED",
                "IMAGE_TRANSLATION_LOW_CONFIDENCE_THRESHOLD", "IMAGE_TRANSLATION_ORIENTATION_RETRY_ENABLED");
        for (String profile : List.of("application-dev.properties", "application-prod.properties")) {
            assertThat(Files.readString(PROPERTIES.resolveSibling(profile)))
                    .as(profile)
                    .doesNotContain("vision", "overlay")
                    // The switch that stays: image translation is on or off with OCR_ENABLED.
                    .contains("app.ocr.enabled=${OCR_ENABLED:true}");
        }
    }

    @Test
    void theBotNoLongerDependsOnGoogleCloudVisionAndTestsAgainstAMockWebServerOfTheSameOkHttp() throws IOException {
        String pom = Files.readString(Path.of("pom.xml")).replaceAll("\\s+", "");

        assertThat(pom)
                .doesNotContain("google-cloud-vision")
                .contains("<artifactId>okhttp-jvm</artifactId><version>${okhttp.pinned.version}</version>")
                .contains("<artifactId>mockwebserver</artifactId><version>${okhttp.pinned.version}</version>"
                        + "<scope>test</scope>");
    }

    private static List<String> members(Properties properties, String key) {
        return Arrays.stream(properties.getProperty(key, "").split(",")).map(String::strip).toList();
    }
}
