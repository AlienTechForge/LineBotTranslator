package com.linetranslate.bot.service.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/**
 * Push messages count against the Official Account's monthly message quota
 * (replies are free), so the fallback is switchable and bounded per day.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.line.push-fallback")
public class PushFallbackProperties {

    private boolean enabled = true;

    /** Fallback pushes allowed per UTC+8 day; 0 blocks every fallback push. */
    @Min(0)
    private int dailyLimit = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDailyLimit() {
        return dailyLimit;
    }

    public void setDailyLimit(int dailyLimit) {
        this.dailyLimit = dailyLimit;
    }
}
