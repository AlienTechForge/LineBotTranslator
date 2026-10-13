package com.linetranslate.bot.service.webhook;

import com.linecorp.bot.messaging.model.QuotaType;

/**
 * Push quota of the Official Account plus the fallback's own daily usage.
 *
 * @param quotaType LINE's quota type for the month
 * @param monthlyLimit messages allowed this month, {@code null} when LINE reports no limit
 * @param consumed messages sent this month by push, multicast, narrowcast and broadcast
 */
public record PushQuotaStatus(
        QuotaType quotaType,
        Long monthlyLimit,
        long consumed,
        boolean fallbackEnabled,
        int fallbackToday,
        int fallbackDailyLimit) {

    /** Messages left this month, or {@code null} when there is no monthly limit. */
    public Long remaining() {
        return monthlyLimit == null ? null : Math.max(0L, monthlyLimit - consumed);
    }
}
