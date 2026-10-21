package com.linetranslate.bot.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import com.linetranslate.bot.service.imagetranslator.ImageTranslatorClient;
import com.linetranslate.bot.service.imagetranslator.ImageTranslatorException;
import com.linetranslate.bot.service.imagetranslator.ImageTranslatorHealth;
import com.linetranslate.bot.service.imagetranslator.ImageTranslatorProperties;
import com.linetranslate.bot.testing.ImageTranslatorFixtures;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;

class ImageTranslatorHealthIndicatorTests {

    private final ImageTranslatorClient client = mock(ImageTranslatorClient.class);

    @Test
    void isUpWithTheVersionAndTheBudgetNumbersAsDetails() {
        when(client.health()).thenReturn(new ImageTranslatorHealth("ok", false, "1.0.0",
                new ImageTranslatorHealth.Budget("2026-10", 1.5, 15.0, 2.5, 80.0)));

        Health health = new ImageTranslatorHealthIndicator(client, () -> true).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("version", "1.0.0").containsEntry("mock", false);
        assertThat(health.getDetails().get("budget")).isEqualTo(Map.of(
                "month", "2026-10",
                "openrouterSpentUsd", 1.5,
                "openrouterBudgetUsd", 15.0,
                "anthropicSpentUsd", 2.5,
                "anthropicBudgetUsd", 80.0));
    }

    @Test
    void isUpWhenTheServiceSendsNoBudgetAtAll() {
        when(client.health()).thenReturn(new ImageTranslatorHealth("ok", null, null, null));

        Health health = new ImageTranslatorHealthIndicator(client, () -> true).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).isEmpty();
    }

    @Test
    void isDownWhenTheServiceDoesNotAnswer() {
        when(client.health()).thenThrow(new ImageTranslatorException(
                ImageTranslatorException.Kind.UNREACHABLE, 0, null, null, new IOException("refused")));

        Health health = new ImageTranslatorHealthIndicator(client, () -> true).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "unreachable");
    }

    @Test
    void isDownOnATimeoutAnHttpErrorAndAnUnexpectedStatus() {
        ImageTranslatorHealthIndicator indicator = new ImageTranslatorHealthIndicator(client, () -> true);
        when(client.health())
                .thenThrow(new ImageTranslatorException(ImageTranslatorException.Kind.TIMEOUT, 0, null, null, null))
                .thenThrow(new ImageTranslatorException(ImageTranslatorException.Kind.HTTP, 500, null, null, null))
                .thenReturn(new ImageTranslatorHealth("starting", null, null, null));

        assertThat(indicator.health().getDetails()).containsEntry("reason", "timeout");
        assertThat(indicator.health().getDetails()).containsEntry("reason", "http");
        Health unexpected = indicator.health();
        assertThat(unexpected.getStatus()).isEqualTo(Status.DOWN);
        assertThat(unexpected.getDetails()).containsEntry("reason", "unexpected_status");
    }

    @Test
    void recoversToUpAfterBeingDown() {
        ImageTranslatorHealthIndicator indicator = new ImageTranslatorHealthIndicator(client, () -> true);
        when(client.health())
                .thenThrow(new ImageTranslatorException(ImageTranslatorException.Kind.UNREACHABLE, 0, null, null, null))
                .thenReturn(new ImageTranslatorHealth("ok", false, "1.0.0", null));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void isDisabledWithoutCallingTheServiceWhenImageTranslationIsOff() {
        Health health = new ImageTranslatorHealthIndicator(client, () -> false).health();

        assertThat(health.getStatus()).isEqualTo(DependencyHealthConfig.DISABLED);
        verifyNoInteractions(client);
    }

    @Test
    void theBeanIsDisabledWhenOcrIsOffOrTheClientIsSwitchedOff() {
        ImageTranslatorProperties switchedOff = new ImageTranslatorProperties();
        switchedOff.setEnabled(false);
        DependencyHealthConfig config = new DependencyHealthConfig();

        HealthIndicator ocrOff = config.imageTranslatorHealthIndicator(client, new ImageTranslatorProperties(), false);
        HealthIndicator clientOff = config.imageTranslatorHealthIndicator(client, switchedOff, true);

        assertThat(ocrOff.health().getStatus()).isEqualTo(DependencyHealthConfig.DISABLED);
        assertThat(clientOff.health().getStatus()).isEqualTo(DependencyHealthConfig.DISABLED);
        verify(client, never()).health();
    }

    @Test
    void theBeanReadsTheRealHealthEndpointOfAServiceAndSeesItGoDown() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            ImageTranslatorProperties properties = new ImageTranslatorProperties();
            properties.setUrl(server.url("/").toString());
            ImageTranslatorClient real = new ImageTranslatorClient(properties);
            HealthIndicator indicator = new DependencyHealthConfig().imageTranslatorHealthIndicator(real, properties, true);
            server.enqueue(new MockResponse().setBody(ImageTranslatorFixtures.healthJson())
                    .addHeader("Content-Type", "application/json"));

            Health up = indicator.health();
            assertThat(up.getStatus()).isEqualTo(Status.UP);
            assertThat(server.takeRequest(5, TimeUnit.SECONDS).getPath()).isEqualTo("/healthz");

            server.enqueue(new MockResponse().setResponseCode(503));
            assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
            assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        }
    }
}
