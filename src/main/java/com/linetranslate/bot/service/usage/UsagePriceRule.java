package com.linetranslate.bot.service.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

/** Versioned list price, expressed per one million tokens and per image. */
public record UsagePriceRule(
        String provider,
        String modelFamily,
        String currency,
        String version,
        Instant effectiveFrom,
        BigDecimal inputPerMillion,
        BigDecimal outputPerMillion,
        BigDecimal perImage) {

    public UsagePriceRule {
        if (blank(provider) || blank(modelFamily) || blank(currency) || blank(version)
                || effectiveFrom == null || negative(inputPerMillion)
                || negative(outputPerMillion) || negative(perImage)) {
            throw new IllegalArgumentException("Pricing rule is incomplete or negative");
        }
        provider = provider.trim().toLowerCase(Locale.ROOT);
        modelFamily = modelFamily.trim().toLowerCase(Locale.ROOT);
        currency = currency.trim().toUpperCase(Locale.ROOT);
        version = version.trim();
    }

    public boolean matches(String candidateProvider, String candidateModel, Instant occurredAt) {
        if (candidateProvider == null || candidateModel == null || occurredAt == null
                || occurredAt.isBefore(effectiveFrom)) {
            return false;
        }
        String normalizedProvider = candidateProvider.trim().toLowerCase(Locale.ROOT);
        String normalizedModel = candidateModel.trim().toLowerCase(Locale.ROOT);
        return provider.equals(normalizedProvider)
                && (modelFamily.equals(normalizedModel)
                        || normalizedModel.startsWith(modelFamily + "-"));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean negative(BigDecimal value) {
        return value == null || value.signum() < 0;
    }
}
