package com.linetranslate.bot.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails production startup with setting names only when core configuration is
 * incomplete, or when enabled MinIO storage would use the factory credentials.
 */
@Component
@Profile("prod")
public class ProductionConfigurationValidator implements InitializingBean {

    /** Factory root user and password of a fresh MinIO server; anyone can guess them. */
    private static final String DEFAULT_MINIO_CREDENTIAL = "minioadmin";

    private final Environment environment;

    public ProductionConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    void validate() {
        Map<String, String> requiredSettings = new LinkedHashMap<>();
        requiredSettings.put("LINE_BOT_CHANNEL_TOKEN", "line.bot.channel-token");
        requiredSettings.put("LINE_BOT_CHANNEL_SECRET", "line.bot.channel-secret");
        requiredSettings.put("MONGODB_URI", "mongodb.uri");
        requiredSettings.put("MONGODB_DATABASE", "mongodb.database");
        requiredSettings.put("OPEN_ROUTE_API_KEY", "openrouter.api.key");

        List<String> missing = new ArrayList<>(requiredSettings.entrySet().stream()
                .filter(entry -> isBlank(environment.getProperty(entry.getValue())))
                .map(Map.Entry::getKey)
                .toList());
        List<String> insecure = new ArrayList<>();
        if (minioEnabled()) {
            String accessKey = environment.getProperty("minio.access-key");
            String secretKey = environment.getProperty("minio.secret-key");
            if (isBlank(accessKey)) {
                missing.add("MINIO_ACCESS_KEY");
            }
            if (isBlank(secretKey)) {
                missing.add("MINIO_SECRET_KEY");
            }
            // The secret is what makes the factory account guessable; a default
            // user name paired with an operator-chosen secret is not.
            if (isDefaultMinioCredential(secretKey)) {
                if (isDefaultMinioCredential(accessKey)) {
                    insecure.add("MINIO_ACCESS_KEY");
                }
                insecure.add("MINIO_SECRET_KEY");
            }
        }

        List<String> problems = new ArrayList<>();
        if (!missing.isEmpty()) {
            problems.add("Missing required production configuration: " + String.join(", ", missing));
        }
        if (!insecure.isEmpty()) {
            problems.add("Insecure production configuration: " + String.join(", ", insecure));
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", problems));
        }
    }

    /** Mirrors {@code ${minio.enabled:${MINIO_ENABLED:true}}} used by the MinIO beans. */
    private boolean minioEnabled() {
        boolean fallback = environment.getProperty("MINIO_ENABLED", Boolean.class, true);
        return environment.getProperty("minio.enabled", Boolean.class, fallback);
    }

    private static boolean isDefaultMinioCredential(String value) {
        return value != null && DEFAULT_MINIO_CREDENTIAL.equals(value.strip());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
