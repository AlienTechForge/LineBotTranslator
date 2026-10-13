package com.linetranslate.bot.service.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Retention period of each MongoDB collection that would otherwise grow without
 * bound, as an ISO-8601 duration such as {@code P90D}. A blank value or
 * {@code PT0S} disables retention for that collection. The values stay strings
 * so that {@link RetentionIndexInitializer} decides what an invalid value means
 * for one collection instead of failing the binding of the whole application.
 */
@Component
@ConfigurationProperties(prefix = "app.retention")
public class RetentionProperties {

    private String translationRecords = "P90D";
    private String translationActionClaims = "P7D";
    private String aiUsageEvents = "P730D";

    public String getTranslationRecords() {
        return translationRecords;
    }

    public void setTranslationRecords(String translationRecords) {
        this.translationRecords = translationRecords;
    }

    public String getTranslationActionClaims() {
        return translationActionClaims;
    }

    public void setTranslationActionClaims(String translationActionClaims) {
        this.translationActionClaims = translationActionClaims;
    }

    public String getAiUsageEvents() {
        return aiUsageEvents;
    }

    public void setAiUsageEvents(String aiUsageEvents) {
        this.aiUsageEvents = aiUsageEvents;
    }
}
