package com.linetranslate.bot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.env.Environment;

import com.linetranslate.bot.health.DependencyHealthConfig;

class AppConfigurationSummaryTests {

    @Test
    void summaryUsesPreciseStatesInsteadOfTreatingConfigurationAsConnectivity() {
        AppConfigurationSummary summary = new AppConfigurationSummary(
                4040,
                mock(Environment.class),
                indicator(Health.up().build()),
                indicator(Health.down().build()),
                indicator(Health.status(DependencyHealthConfig.DEGRADED).build()),
                indicator(Health.status(DependencyHealthConfig.DISABLED).build()),
                indicator(Health.up().build()),
                indicator(Health.status(DependencyHealthConfig.DISABLED).build()));

        assertThat(summary.statusSummary()).isEqualTo(
                "LINE=configured, MongoDB=unavailable, MinIO=degraded, OCR=disabled, "
                        + "OpenAI=configured, Gemini=disabled");
    }

    private HealthIndicator indicator(Health health) {
        HealthIndicator indicator = mock(HealthIndicator.class);
        when(indicator.health()).thenReturn(health);
        return indicator;
    }
}
