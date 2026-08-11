package com.linetranslate.bot.config;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.linetranslate.bot.health.DependencyHealthConfig;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class AppConfigurationSummary implements ApplicationListener<ApplicationStartedEvent> {

    private final int serverPort;
    private final Environment environment;
    private final HealthIndicator lineConfiguration;
    private final HealthIndicator mongo;
    private final HealthIndicator minio;
    private final HealthIndicator ocrConfiguration;
    private final HealthIndicator openRouterConfiguration;

    public AppConfigurationSummary(
            @Value("${server.port:8080}") int serverPort,
            Environment environment,
            @Qualifier("lineConfigurationHealthIndicator") HealthIndicator lineConfiguration,
            @Qualifier("mongoHealthIndicator") HealthIndicator mongo,
            @Qualifier("minioHealthIndicator") HealthIndicator minio,
            @Qualifier("ocrConfigurationHealthIndicator") HealthIndicator ocrConfiguration,
            @Qualifier("openRouterConfigurationHealthIndicator") HealthIndicator openRouterConfiguration) {
        this.serverPort = serverPort;
        this.environment = environment;
        this.lineConfiguration = lineConfiguration;
        this.mongo = mongo;
        this.minio = minio;
        this.ocrConfiguration = ocrConfiguration;
        this.openRouterConfiguration = openRouterConfiguration;
    }

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        String status = statusSummary();
        boolean production = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (production) {
            log.info("LINE Bot Translator running on port {}", serverPort);
            log.info("Services: {}", status);
        } else {
            log.info("LINE Bot Translator started on port {}: {}", serverPort, status);
        }
    }

    String statusSummary() {
        return String.join(", ",
                "LINE=" + configurationState(lineConfiguration),
                "MongoDB=" + requiredState(mongo),
                "MinIO=" + optionalState(minio),
                "OCR=" + configurationOptionalState(ocrConfiguration),
                "OpenRouter=" + configurationState(openRouterConfiguration));
    }

    private String requiredState(HealthIndicator indicator) {
        return Status.UP.equals(indicator.health().getStatus()) ? "ready" : "unavailable";
    }

    private String optionalState(HealthIndicator indicator) {
        Status status = indicator.health().getStatus();
        if (Status.UP.equals(status)) {
            return "ready";
        }
        if (DependencyHealthConfig.DISABLED.equals(status)) {
            return "disabled";
        }
        return "degraded";
    }

    private String configurationOptionalState(HealthIndicator indicator) {
        Status status = indicator.health().getStatus();
        if (Status.UP.equals(status)) {
            return "configured";
        }
        if (DependencyHealthConfig.DISABLED.equals(status)) {
            return "disabled";
        }
        return "degraded";
    }

    private String configurationState(HealthIndicator indicator) {
        Status status = indicator.health().getStatus();
        if (Status.UP.equals(status)) {
            return "configured";
        }
        if (DependencyHealthConfig.DISABLED.equals(status)) {
            return "disabled";
        }
        return "unavailable";
    }
}
