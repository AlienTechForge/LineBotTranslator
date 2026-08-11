package com.linetranslate.bot.service.usage;

import java.math.BigDecimal;

public record UsageBreakdown(String key, long executions, BigDecimal cost) {
}
