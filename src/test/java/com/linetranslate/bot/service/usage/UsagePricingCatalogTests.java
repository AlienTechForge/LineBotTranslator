package com.linetranslate.bot.service.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.linetranslate.bot.service.ai.AiTokenUsage;

class UsagePricingCatalogTests {

    @Test
    void officialRulesPriceActualTokensPerMillionWithVersionAndCurrency() {
        UsagePriceQuote openAi = new UsagePricingCatalog().quote(
                "openai",
                "gpt-4o",
                Instant.parse("2026-08-11T00:00:00Z"),
                new AiTokenUsage(1_000_000, 1_000_000, 2_000_000),
                0);
        UsagePriceQuote gemini = new UsagePricingCatalog().quote(
                "gemini",
                "gemini-1.5-pro",
                Instant.parse("2026-08-11T00:00:00Z"),
                new AiTokenUsage(1_000, 500, 1_500),
                0);

        assertThat(openAi.cost()).isEqualByComparingTo("12.50000000");
        assertThat(openAi.currency()).isEqualTo("USD");
        assertThat(openAi.pricingVersion()).isEqualTo("openai-2026-08-11-v1");
        assertThat(gemini.cost()).isEqualByComparingTo("0.00375000");
        assertThat(gemini.pricingVersion()).isEqualTo("gemini-2025-09-25-v1");
    }

    @Test
    void eventDateSelectsTheApplicablePricingVersion() {
        UsagePricingCatalog catalog = new UsagePricingCatalog(List.of(
                rule("v1", "2026-01-01T00:00:00Z", "1.00"),
                rule("v2", "2026-07-01T00:00:00Z", "2.00")));

        UsagePriceQuote june = catalog.quote(
                "openai", "gpt-test", Instant.parse("2026-06-30T23:59:59Z"),
                new AiTokenUsage(1_000_000, 0, 1_000_000), 0);
        UsagePriceQuote july = catalog.quote(
                "openai", "gpt-test", Instant.parse("2026-07-01T00:00:00Z"),
                new AiTokenUsage(1_000_000, 0, 1_000_000), 0);

        assertThat(june.pricingVersion()).isEqualTo("v1");
        assertThat(june.cost()).isEqualByComparingTo("1.00000000");
        assertThat(july.pricingVersion()).isEqualTo("v2");
        assertThat(july.cost()).isEqualByComparingTo("2.00000000");
    }

    private static UsagePriceRule rule(String version, String effectiveFrom, String inputRate) {
        return new UsagePriceRule(
                "openai",
                "gpt-test",
                "USD",
                version,
                Instant.parse(effectiveFrom),
                new BigDecimal(inputRate),
                BigDecimal.ZERO,
                BigDecimal.ZERO);
    }
}
