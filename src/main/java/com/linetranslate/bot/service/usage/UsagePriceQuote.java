package com.linetranslate.bot.service.usage;

import java.math.BigDecimal;

public record UsagePriceQuote(
        BigDecimal cost,
        String currency,
        String pricingVersion,
        boolean priced) {
}
