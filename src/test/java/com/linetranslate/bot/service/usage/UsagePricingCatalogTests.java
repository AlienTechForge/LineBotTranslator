package com.linetranslate.bot.service.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.linetranslate.bot.service.ai.AiModelCatalog;
import com.linetranslate.bot.service.ai.AiModelDescriptor;
import com.linetranslate.bot.service.ai.AiTokenUsage;

class UsagePricingCatalogTests {

    @Test
    void openRouterCatalogPricesActualTokensAndSnapshotsVersion() {
        AiModelCatalog models = mock(AiModelCatalog.class);
        when(models.find("anthropic/claude-sonnet-4")).thenReturn(Optional.of(new AiModelDescriptor(
                "anthropic/claude-sonnet-4",
                "Claude Sonnet 4",
                Set.of("text"),
                Set.of("text"),
                new BigDecimal("0.000003"),
                new BigDecimal("0.000015"))));

        UsagePriceQuote quote = new UsagePricingCatalog(models).quote(
                "openrouter",
                "anthropic/claude-sonnet-4",
                Instant.parse("2026-08-11T00:00:00Z"),
                new AiTokenUsage(1_000, 500, 1_500),
                0);

        assertThat(quote.cost()).isEqualByComparingTo("0.01050000");
        assertThat(quote.currency()).isEqualTo("USD");
        assertThat(quote.pricingVersion()).isEqualTo("openrouter-catalog-v1");
        assertThat(quote.priced()).isTrue();
    }

    @Test
    void eventDateStillSelectsApplicableHistoricalRule() {
        UsagePricingCatalog catalog = new UsagePricingCatalog(List.of(
                rule("v1", "2026-01-01T00:00:00Z", "1.00"),
                rule("v2", "2026-07-01T00:00:00Z", "2.00")));

        UsagePriceQuote june = catalog.quote(
                "legacy", "model", Instant.parse("2026-06-30T23:59:59Z"),
                new AiTokenUsage(1_000_000, 0, 1_000_000), 0);
        UsagePriceQuote july = catalog.quote(
                "legacy", "model", Instant.parse("2026-07-01T00:00:00Z"),
                new AiTokenUsage(1_000_000, 0, 1_000_000), 0);

        assertThat(june.pricingVersion()).isEqualTo("v1");
        assertThat(july.pricingVersion()).isEqualTo("v2");
        assertThat(july.cost()).isEqualByComparingTo("2.00000000");
    }

    private static UsagePriceRule rule(String version, String effectiveFrom, String inputRate) {
        return new UsagePriceRule(
                "legacy", "model", "USD", version, Instant.parse(effectiveFrom),
                new BigDecimal(inputRate), BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
