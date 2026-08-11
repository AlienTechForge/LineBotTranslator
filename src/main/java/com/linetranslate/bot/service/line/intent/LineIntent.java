package com.linetranslate.bot.service.line.intent;

/** Domain intent produced from LINE text or postback input. */
public sealed interface LineIntent permits
        LineIntent.TranslateText,
        LineIntent.QuickTranslate,
        LineIntent.Retranslate,
        LineIntent.UserCommand,
        LineIntent.AdminCommand,
        LineIntent.Invalid {

    record TranslateText(String text) implements LineIntent {
    }

    record QuickTranslate(String language, String text) implements LineIntent {
    }

    record Retranslate(String recordId, String targetLanguage) implements LineIntent {
    }

    record UserCommand(UserAction action, String argument) implements LineIntent {
    }

    record AdminCommand(String command) implements LineIntent {
    }

    record Invalid(InvalidReason reason, String value) implements LineIntent {
    }

    enum UserAction {
        HELP,
        ABOUT,
        SET_MODEL,
        MODELS,
        SET_FOREIGN_LANGUAGE,
        PROFILE,
        STATUS,
        LANGUAGE_MENU,
        SET_CHINESE_LANGUAGE
    }

    enum InvalidReason {
        MODEL_REQUIRED,
        INVALID_MODEL,
        MODEL_QUERY_TOO_LONG,
        TRANSLATION_ACTION_FORMAT,
        FOREIGN_LANGUAGE_REQUIRED,
        CHINESE_LANGUAGE_REQUIRED,
        QUICK_TRANSLATION_FORMAT,
        UNSUPPORTED_LANGUAGE,
        UNKNOWN_COMMAND,
        UNSUPPORTED_POSTBACK
    }
}
