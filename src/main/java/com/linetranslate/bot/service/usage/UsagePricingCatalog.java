package com.linetranslate.bot.service.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.linetranslate.bot.service.ai.AiModelCatalog;
import com.linetranslate.bot.service.ai.AiModelDescriptor;
import com.linetranslate.bot.service.ai.AiTokenUsage;

/** Prices new OpenRouter events from the cached official model catalog snapshot. */
@Component
public class UsagePricingCatalog {

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final String USD = "USD";

    private final AiModelCatalog modelCatalog;
    private final List<UsagePriceRule> rules;

    @Autowired
    public UsagePricingCatalog(AiModelCatalog modelCatalog) {
        this.modelCatalog = modelCatalog;
        this.rules = List.of();
    }

    /** Focused-test constructor for historical immutable pricing rules. */
    public UsagePricingCatalog(List<UsagePriceRule> rules) {
        this.modelCatalog = null;
        this.rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public UsagePriceQuote quote(
            String provider,
            String model,
            Instant occurredAt,
            AiTokenUsage usage,
            int imageCount) {
        if ("openrouter".equalsIgnoreCase(provider) && modelCatalog != null) {
            AiModelDescriptor descriptor = modelCatalog.find(model).orElse(null);
            if (descriptor != null
                    && descriptor.promptPricePerToken() != null
                    && descriptor.completionPricePerToken() != null) {
                BigDecimal cost = descriptor.promptPricePerToken()
                        .multiply(BigDecimal.valueOf(known(usage == null ? -1 : usage.inputTokens())))
                        .add(descriptor.completionPricePerToken()
                                .multiply(BigDecimal.valueOf(known(
                                        usage == null ? -1 : usage.outputTokens()))));
                return new UsagePriceQuote(
                        cost.setScale(8, RoundingMode.HALF_UP),
                        USD,
                        "openrouter-catalog-v1",
                        true);
            }
        }

        UsagePriceRule rule = rules.stream()
                .filter(candidate -> candidate.matches(provider, model, occurredAt))
                .max(Comparator.comparing(UsagePriceRule::effectiveFrom)
                        .thenComparingInt(candidate -> candidate.modelFamily().length()))
                .orElse(null);
        if (rule == null) {
            return new UsagePriceQuote(BigDecimal.ZERO.setScale(8), USD, "unpriced-v1", false);
        }
        BigDecimal tokenCost = rule.inputPerMillion()
                .multiply(BigDecimal.valueOf(known(usage == null ? -1 : usage.inputTokens())))
                .add(rule.outputPerMillion().multiply(
                        BigDecimal.valueOf(known(usage == null ? -1 : usage.outputTokens()))))
                .divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
        BigDecimal imageCost = rule.perImage().multiply(BigDecimal.valueOf(Math.max(0, imageCount)));
        return new UsagePriceQuote(
                tokenCost.add(imageCost).setScale(8, RoundingMode.HALF_UP),
                rule.currency(),
                rule.version(),
                true);
    }

    private static long known(long tokens) {
        return Math.max(0, tokens);
    }
}
