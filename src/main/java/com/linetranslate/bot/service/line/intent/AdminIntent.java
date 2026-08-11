package com.linetranslate.bot.service.line.intent;

/** Validated administrator intent. */
public record AdminIntent(Action action, String value, String secondary, Problem problem) {

    public static AdminIntent action(Action action, String value, String secondary) {
        return new AdminIntent(action, value, secondary, null);
    }

    public static AdminIntent invalid(Problem problem) {
        return new AdminIntent(Action.INVALID, "", "", problem);
    }

    public enum Action {
        DASHBOARD,
        VERIFY,
        BROADCAST,
        STATS,
        TODAY,
        USERS,
        USER,
        NICKNAME,
        CONFIG_SHOW,
        CONFIG_C2LANG,
        CONFIG_LANGUAGE,
        CONFIG_AI,
        CONFIG_OPENAI,
        CONFIG_GEMINI,
        CONFIG_OCR,
        USAGE_CURRENT_MONTH,
        USAGE_DAY,
        USAGE_MONTH,
        USAGE_PROVIDER,
        USAGE_MODEL,
        USAGE_TYPE,
        USAGE_SUMMARY,
        ADD_ADMIN,
        REMOVE_ADMIN,
        UNKNOWN_CONFIG,
        UNKNOWN_USAGE,
        UNKNOWN,
        INVALID
    }

    public enum Problem {
        BROADCAST_REQUIRED,
        BROADCAST_TOO_LONG,
        USER_REQUIRED,
        NICKNAME_FORMAT,
        NICKNAME_REQUIRED,
        CONFIG_VALUE_REQUIRED,
        USAGE_DAY_REQUIRED,
        USAGE_MONTH_REQUIRED,
        USAGE_PROVIDER_REQUIRED,
        USAGE_MODEL_REQUIRED,
        USAGE_TYPE_REQUIRED,
        ADD_ADMIN_REQUIRED,
        REMOVE_ADMIN_REQUIRED
    }
}
