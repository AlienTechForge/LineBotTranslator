package com.linetranslate.bot.service.line;

import java.util.Locale;

import com.linecorp.bot.messaging.model.QuotaType;
import com.linetranslate.bot.service.webhook.PushQuotaStatus;

/** Renders the Official Account push quota and the expired-token fallback usage. */
final class PushQuotaReport {

    private static final String UNLIMITED = "無上限";
    private static final String UNKNOWN = "未知";

    private PushQuotaReport() {
    }

    static String render(PushQuotaStatus status) {
        return "額度類型：" + type(status.quotaType()) + "\n"
                + "每月上限：" + limit(status) + "\n"
                + "本月已用：" + count(status.consumed()) + "\n"
                + "本月剩餘：" + remaining(status) + "\n\n"
                + "額度計入推播、多人推播與廣播等主動訊息；回覆訊息不計入。\n\n"
                + "reply token 過期備援推播：" + (status.fallbackEnabled() ? "已啟用" : "已停用") + "\n"
                + "今日備援推播：" + status.fallbackToday() + " / " + status.fallbackDailyLimit()
                + " 則（以 UTC+8 計日）";
    }

    private static String type(QuotaType type) {
        if (type == QuotaType.LIMITED) {
            return "有限額";
        }
        return type == QuotaType.NONE ? UNLIMITED : UNKNOWN;
    }

    private static String limit(PushQuotaStatus status) {
        if (status.quotaType() == QuotaType.NONE) {
            return UNLIMITED;
        }
        return status.monthlyLimit() == null ? UNKNOWN : count(status.monthlyLimit());
    }

    private static String remaining(PushQuotaStatus status) {
        if (status.quotaType() == QuotaType.NONE) {
            return UNLIMITED;
        }
        return status.remaining() == null ? UNKNOWN : count(status.remaining());
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value) + " 則";
    }
}
