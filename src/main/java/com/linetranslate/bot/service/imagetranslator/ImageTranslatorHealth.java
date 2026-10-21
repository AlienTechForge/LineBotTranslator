package com.linetranslate.bot.service.imagetranslator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The answer of the service's {@code GET /healthz}: it carries no key or token, only whether it is
 * up, whether the canned mock is on, its version and this month's spending against its budgets
 * (a budget of 0 means no cap).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImageTranslatorHealth(String status, Boolean mock, String version, Budget budget) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Budget(
            String month,
            Double openrouterSpentUsd,
            Double openrouterBudgetUsd,
            Double anthropicSpentUsd,
            Double anthropicBudgetUsd) {
    }

    public boolean up() {
        return "ok".equalsIgnoreCase(status);
    }
}
