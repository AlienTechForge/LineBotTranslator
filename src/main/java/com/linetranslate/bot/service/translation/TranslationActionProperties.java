package com.linetranslate.bot.service.translation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

@Component
@Validated
@ConfigurationProperties(prefix = "app.translation")
public class TranslationActionProperties {

    /** How long a PROCESSING action claim is protected before another click may take it over. */
    @NotNull
    private Duration actionClaimLease = Duration.ofMinutes(5);

    public Duration getActionClaimLease() {
        return actionClaimLease;
    }

    public void setActionClaimLease(Duration actionClaimLease) {
        this.actionClaimLease = actionClaimLease;
    }
}
