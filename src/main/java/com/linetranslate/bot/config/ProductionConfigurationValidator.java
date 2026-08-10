package com.linetranslate.bot.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fails production startup with setting names only when core configuration is incomplete. */
@Component
@Profile("prod")
public class ProductionConfigurationValidator implements InitializingBean {

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

        List<String> missing = requiredSettings.entrySet().stream()
                .filter(entry -> isBlank(environment.getProperty(entry.getValue())))
                .map(Map.Entry::getKey)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required production configuration: " + String.join(", ", missing));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
