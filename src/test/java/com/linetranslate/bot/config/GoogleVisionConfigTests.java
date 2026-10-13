package com.linetranslate.bot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.retrying.RetrySettings;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.ImageAnnotatorSettings;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class GoogleVisionConfigTests {

    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(GoogleVisionConfig.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void batchAnnotateImagesIsBoundedByTheConfiguredTimeoutAndKeepsLibraryRetryCodes()
            throws IOException {
        GoogleVisionConfig config = new GoogleVisionConfig();
        ReflectionTestUtils.setField(config, "timeoutMs", 45_000L);

        ImageAnnotatorSettings settings = config.visionSettings(NoCredentialsProvider.create());

        RetrySettings retry = settings.batchAnnotateImagesSettings().getRetrySettings();
        assertThat(retry.getInitialRpcTimeoutDuration()).isEqualTo(Duration.ofSeconds(45));
        assertThat(retry.getMaxRpcTimeoutDuration()).isEqualTo(Duration.ofSeconds(45));
        assertThat(retry.getTotalTimeoutDuration()).isEqualTo(Duration.ofSeconds(45));
        assertThat(settings.batchAnnotateImagesSettings().getRetryableCodes())
                .isEqualTo(ImageAnnotatorSettings.newBuilder().build()
                        .batchAnnotateImagesSettings().getRetryableCodes());
    }

    @Test
    void defaultTimeoutIsOneMinuteInsteadOfTheLibraryDefault() throws IOException {
        Duration libraryDefault = ImageAnnotatorSettings.newBuilder().build()
                .batchAnnotateImagesSettings().getRetrySettings().getTotalTimeoutDuration();

        RetrySettings retry = new GoogleVisionConfig().visionSettings(null)
                .batchAnnotateImagesSettings().getRetrySettings();

        assertThat(retry.getTotalTimeoutDuration()).isEqualTo(Duration.ofMinutes(1));
        assertThat(retry.getInitialRpcTimeoutDuration()).isEqualTo(Duration.ofMinutes(1));
        assertThat(libraryDefault).isGreaterThan(Duration.ofMinutes(1));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveTimeoutIsRejected(long timeoutMs) {
        GoogleVisionConfig config = new GoogleVisionConfig();
        ReflectionTestUtils.setField(config, "timeoutMs", timeoutMs);

        assertThatThrownBy(() -> config.visionSettings(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    void fileCredentialClientCarriesTheConfiguredBound(@TempDir Path directory) throws IOException {
        String environmentCredentials = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        Assumptions.assumeTrue(environmentCredentials == null || environmentCredentials.isEmpty(),
                "the environment credential path takes precedence over the file path");
        Path credentials = directory.resolve("vision-test.json");
        Files.writeString(credentials, """
                {"type":"authorized_user","client_id":"test-client",\
                "client_secret":"test-secret","refresh_token":"test-refresh"}
                """);
        GoogleVisionConfig config = new GoogleVisionConfig();
        ReflectionTestUtils.setField(config, "credentialsPath", credentials.toString());
        ReflectionTestUtils.setField(config, "timeoutMs", 30_000L);

        try (ImageAnnotatorClient client = config.imageAnnotatorClient()) {
            assertThat(client).isNotNull();
            assertThat(client.getSettings().batchAnnotateImagesSettings().getRetrySettings()
                    .getTotalTimeoutDuration()).isEqualTo(Duration.ofSeconds(30));
        }
    }

    @Test
    void missingCredentialsFileWarningNamesTheVariableAndTheProductionSecret(@TempDir Path directory) {
        assumeNoEnvironmentCredentials();
        Path missing = directory.resolve("absent-credentials.json");
        GoogleVisionConfig config = new GoogleVisionConfig();
        ReflectionTestUtils.setField(config, "credentialsPath", missing.toString());

        assertThat(config.imageAnnotatorClient()).isNull();

        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN).singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("GOOGLE_APPLICATION_CREDENTIALS")
                        .contains("GOOGLE_CREDENTIALS_JSON")
                        .contains("deployment-guide.md")
                        .doesNotContain(missing.toString())
                        .doesNotContain(directory.toString()));
    }

    @Test
    void unreadableCredentialsFileErrorNamesTheVariableWithoutLeakingPathOrContent(@TempDir Path directory)
            throws IOException {
        assumeNoEnvironmentCredentials();
        Path broken = directory.resolve("broken-credentials.json");
        Files.writeString(broken, "private-credential-content");
        GoogleVisionConfig config = new GoogleVisionConfig();
        ReflectionTestUtils.setField(config, "credentialsPath", broken.toString());

        assertThat(config.imageAnnotatorClient()).isNull();

        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.ERROR).singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("GOOGLE_APPLICATION_CREDENTIALS")
                        .doesNotContain("private-credential-content")
                        .doesNotContain(broken.toString())
                        .doesNotContain(directory.toString()));
    }

    private static void assumeNoEnvironmentCredentials() {
        String environmentCredentials = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        Assumptions.assumeTrue(environmentCredentials == null || environmentCredentials.isEmpty(),
                "the environment credential path takes precedence over the file path");
    }
}
