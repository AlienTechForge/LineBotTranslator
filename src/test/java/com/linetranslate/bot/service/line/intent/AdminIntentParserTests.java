package com.linetranslate.bot.service.line.intent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminIntentParserTests {

    private final AdminIntentParser parser = new AdminIntentParser();

    @Test
    void parsesExistingAdminInteractionsIntoTypedActions() {
        assertAction("", AdminIntent.Action.DASHBOARD, "", "");
        assertAction("help", AdminIntent.Action.DASHBOARD, "", "");
        assertAction("isadmin", AdminIntent.Action.VERIFY, "", "");
        assertAction("broadcast 系統通知", AdminIntent.Action.BROADCAST, "系統通知", "");
        assertAction("stats", AdminIntent.Action.STATS, "", "");
        assertAction("today", AdminIntent.Action.TODAY, "", "");
        assertAction("users", AdminIntent.Action.USERS, "", "");
        assertAction("user U-target", AdminIntent.Action.USER, "U-target", "");
        assertAction("nickname U-target Jason", AdminIntent.Action.NICKNAME, "U-target", "Jason");
        assertAction("config", AdminIntent.Action.CONFIG_SHOW, "", "");
        assertAction("config c2lang en", AdminIntent.Action.CONFIG_C2LANG, "en", "");
        assertAction("config lang zh-TW", AdminIntent.Action.CONFIG_LANGUAGE, "zh-TW", "");
        assertAction("config model openai/gpt-4o-mini", AdminIntent.Action.CONFIG_MODEL,
                "openai/gpt-4o-mini", "");
        assertAction("config openrouter anthropic/claude-sonnet-4", AdminIntent.Action.CONFIG_MODEL,
                "anthropic/claude-sonnet-4", "");
        assertAction("config ocr on", AdminIntent.Action.CONFIG_OCR, "on", "");
        assertAction("usage", AdminIntent.Action.USAGE_CURRENT_MONTH, "", "");
        assertAction("usage day 2026-08-11", AdminIntent.Action.USAGE_DAY, "2026-08-11", "");
        assertAction("usage month 2026-08", AdminIntent.Action.USAGE_MONTH, "2026-08", "");
        assertAction("usage provider openrouter", AdminIntent.Action.USAGE_PROVIDER, "openrouter", "");
        assertAction("usage model gpt-4o", AdminIntent.Action.USAGE_MODEL, "gpt-4o", "");
        assertAction("usage type image", AdminIntent.Action.USAGE_TYPE, "image", "");
        assertAction("usage summary", AdminIntent.Action.USAGE_SUMMARY, "", "");
        assertAction("add U-target", AdminIntent.Action.ADD_ADMIN, "U-target", "");
        assertAction("remove U-target", AdminIntent.Action.REMOVE_ADMIN, "U-target", "");
    }

    @Test
    void rejectsMissingOrOversizedArgumentsBeforeServiceExecution() {
        assertProblem("broadcast", AdminIntent.Problem.BROADCAST_REQUIRED);
        assertProblem("broadcast " + "a".repeat(5001), AdminIntent.Problem.BROADCAST_TOO_LONG);
        assertProblem("user", AdminIntent.Problem.USER_REQUIRED);
        assertProblem("nickname", AdminIntent.Problem.NICKNAME_FORMAT);
        assertProblem("nickname U-target", AdminIntent.Problem.NICKNAME_REQUIRED);
        assertProblem("config ocr", AdminIntent.Problem.CONFIG_VALUE_REQUIRED);
        assertProblem("usage day", AdminIntent.Problem.USAGE_DAY_REQUIRED);
        assertProblem("usage provider", AdminIntent.Problem.USAGE_PROVIDER_REQUIRED);
        assertProblem("add", AdminIntent.Problem.ADD_ADMIN_REQUIRED);
        assertProblem("remove", AdminIntent.Problem.REMOVE_ADMIN_REQUIRED);
    }

    @Test
    void unknownScopesRemainStructured() {
        assertAction("config unknown value", AdminIntent.Action.UNKNOWN_CONFIG, "unknown", "");
        assertAction("usage unknown", AdminIntent.Action.UNKNOWN_USAGE, "unknown", "");
        assertAction("unknown", AdminIntent.Action.UNKNOWN, "unknown", "");
    }

    private void assertAction(String command, AdminIntent.Action action, String value, String secondary) {
        assertThat(parser.parse(command)).isEqualTo(AdminIntent.action(action, value, secondary));
    }

    private void assertProblem(String command, AdminIntent.Problem problem) {
        assertThat(parser.parse(command)).isEqualTo(AdminIntent.invalid(problem));
    }
}
