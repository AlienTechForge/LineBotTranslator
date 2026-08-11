package com.linetranslate.bot.service.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.linetranslate.bot.service.ai.AiTokenUsage;

/** Central, versioned pricing catalog. Unknown usage is retained as unpriced. */
@Component
public class UsagePricingCatalog {

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final String USD = "USD";

    private final List<UsagePriceRule> rules;

    public UsagePricingCatalog() {
        this(officialRules());
    }

    public UsagePricingCatalog(List<UsagePriceRule> rules) {
        this.rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public UsagePriceQuote quote(
            String provider,
            String model,
            Instant occurredAt,
            AiTokenUsage usage,
            int imageCount) {
        UsagePriceRule rule = rules.stream()
                .filter(candidate -> candidate.matches(provider, model, occurredAt))
                .max(Comparator.comparing(UsagePriceRule::effectiveFrom)
                        .thenComparingInt(candidate -> candidate.modelFamily().length()))
                .orElse(null);
        if (rule == null) {
            return new UsagePriceQuote(
                    BigDecimal.ZERO.setScale(8), USD, "unpriced-v1", false);
        }

        long inputTokens = known(usage == null ? -1 : usage.inputTokens());
        long outputTokens = known(usage == null ? -1 : usage.outputTokens());
        BigDecimal tokenCost = rule.inputPerMillion()
                .multiply(BigDecimal.valueOf(inputTokens))
                .add(rule.outputPerMillion().multiply(BigDecimal.valueOf(outputTokens)))
                .divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
        BigDecimal imageCost = rule.perImage().multiply(BigDecimal.valueOf(Math.max(0, imageCount)));
        return new UsagePriceQuote(
                tokenCost.add(imageCost).setScale(8, RoundingMode.HALF_UP),
                rule.currency(),
                rule.version(),
                true);
    }

    /**
     * USD standard-tier rates verified against the official OpenAI model pages
     * and Gemini API pricing page. Each revision receives a new effective date
     * and version instead of rewriting historical events.
     */
    private static List<UsagePriceRule> officialRules() {
        return List.of(
                rule("openai", "gpt-4o-mini", "openai-2026-08-11-v1",
                        "2026-08-11T00:00:00Z", "0.15", "0.60"),
                rule("openai", "gpt-4o", "openai-2026-08-11-v1",
                        "2026-08-11T00:00:00Z", "2.50", "10.00"),
                rule("openai", "gpt-3.5-turbo", "openai-legacy-2026-08-11-v1",
                        "2026-08-11T00:00:00Z", "0.50", "1.50"),
                rule("gemini", "gemini-1.5-flash", "gemini-2025-09-25-v1",
                        "2025-09-25T00:00:00Z", "0.075", "0.30"),
                rule("gemini", "gemini-1.5-pro", "gemini-2025-09-25-v1",
                        "2025-09-25T00:00:00Z", "1.25", "5.00"));
    }

    private static UsagePriceRule rule(
            String provider,
            String model,
            String version,
            String effectiveFrom,
            String input,
            String output) {
        return new UsagePriceRule(
                provider,
                model,
                USD,
                version,
                Instant.parse(effectiveFrom),
                new BigDecimal(input),
                new BigDecimal(output),
                BigDecimal.ZERO);
    }

    private static long known(long tokens) {
        return Math.max(0, tokens);
    }
}
