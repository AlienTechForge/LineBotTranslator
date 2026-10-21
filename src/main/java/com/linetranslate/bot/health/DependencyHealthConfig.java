package com.linetranslate.bot.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linetranslate.bot.service.imagetranslator.ImageTranslatorClient;
import com.linetranslate.bot.service.imagetranslator.ImageTranslatorProperties;
import com.linetranslate.bot.service.storage.MinioStorageService;

@Configuration
public class DependencyHealthConfig {

    public static final Status DEGRADED = new Status("DEGRADED");
    public static final Status DISABLED = new Status("DISABLED");

    @Bean(name = "lineConfigurationHealthIndicator")
    HealthIndicator lineConfigurationHealthIndicator(
            @Value("${line.bot.channel-token:}") String token,
            @Value("${line.bot.channel-secret:}") String secret) {
        return () -> configured(token) && configured(secret)
                ? Health.up().build()
                : Health.down().build();
    }

    @Bean(name = "minioHealthIndicator")
    HealthIndicator minioHealthIndicator(
            MinioStorageService minioStorageService,
            @Value("${minio.enabled:${MINIO_ENABLED:true}}") boolean enabled) {
        return () -> {
            if (!enabled) {
                return Health.status(DISABLED).build();
            }
            return minioStorageService.isAvailable()
                    ? Health.up().build()
                    : Health.status(DEGRADED).build();
        };
    }

    /**
     * The image-translator service of the same container. Registered in the liveness group only
     * (see {@code management.endpoint.health.group.liveness.include}): the readiness group must not
     * contain it, because a dead service must not stop the bot from answering text messages.
     */
    @Bean(name = "imageTranslatorHealthIndicator")
    HealthIndicator imageTranslatorHealthIndicator(
            ImageTranslatorClient client,
            ImageTranslatorProperties properties,
            @Value("${app.ocr.enabled:false}") boolean ocrEnabled) {
        return new ImageTranslatorHealthIndicator(client, () -> ocrEnabled && properties.isEnabled());
    }

    @Bean(name = "openRouterConfigurationHealthIndicator")
    HealthIndicator openRouterConfigurationHealthIndicator(
            @Value("${openrouter.api.key:${OPEN_ROUTE_API_KEY:}}") String apiKey) {
        return () -> configured(apiKey) ? Health.up().build() : Health.down().build();
    }

    private HealthIndicator configuredIndicator(String value) {
        return () -> configured(value)
                ? Health.up().build()
                : Health.status(DISABLED).build();
    }

    private static boolean configured(String value) {
        return value != null && !value.isBlank();
    }
}
