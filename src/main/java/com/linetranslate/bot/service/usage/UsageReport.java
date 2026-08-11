package com.linetranslate.bot.service.usage;

import java.math.BigDecimal;
import java.util.List;

public record UsageReport(
        long totalExecutions,
        long successfulExecutions,
        long failedExecutions,
        long textExecutions,
        long imageExecutions,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        long totalLatencyMillis,
        BigDecimal totalCost,
        String currency,
        List<UsageBreakdown> byProvider,
        List<UsageBreakdown> byModel) {

    public UsageReport {
        totalCost = totalCost == null ? BigDecimal.ZERO : totalCost;
        currency = currency == null ? "USD" : currency;
        byProvider = byProvider == null ? List.of() : List.copyOf(byProvider);
        byModel = byModel == null ? List.of() : List.copyOf(byModel);
    }

    public static UsageReport empty() {
        return new UsageReport(
                0, 0, 0, 0, 0, 0, 0, 0, 0,
                BigDecimal.ZERO.setScale(8), "USD", List.of(), List.of());
    }
}
