package com.linetranslate.bot.service.line.intent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LineIntentParserTests {

    private final LineIntentParser parser = new LineIntentParser();

    @Test
    void generalTextAndQuickTranslationBecomeExplicitIntents() {
        assertThat(parser.parseText("hello"))
                .isEqualTo(new LineIntent.TranslateText("hello"));
        assertThat(parser.parseText("快速翻譯:en 你好"))
                .isEqualTo(new LineIntent.QuickTranslate("en", "你好"));
        assertThat(parser.parseText("快速翻譯：ja 晚安"))
                .isEqualTo(new LineIntent.QuickTranslate("ja", "晚安"));
        assertThat(parser.parseText("/translate-style formal Please reply soon"))
                .isEqualTo(new LineIntent.StyledTranslate("formal", "Please reply soon"));
    }

    @ParameterizedTest
    @MethodSource("commands")
    void everyExistingUserCommandHasStructuredIntent(
            String input,
            LineIntent.UserAction action,
            String argument) {
        assertThat(parser.parseText(input))
                .isEqualTo(new LineIntent.UserCommand(action, argument));
    }

    @Test
    void adminCommandsBecomeDedicatedSensitiveIntents() {
        assertThat(parser.parseText("/admin stats"))
                .isEqualTo(new LineIntent.AdminCommand("stats"));
        assertThat(parser.parseText("/adminhelp"))
                .isEqualTo(new LineIntent.AdminCommand("help"));
        assertThat(parser.parseText("/isadmin"))
                .isEqualTo(new LineIntent.AdminCommand("isadmin"));
    }

    @Test
    void validationIsConsistentBeforeExecution() {
        assertThat(parser.parseText("/setmodel"))
                .isEqualTo(new LineIntent.Invalid(LineIntent.InvalidReason.MODEL_REQUIRED, ""));
        assertThat(parser.parseText("/model https://evil.example/?x=1"))
                .isEqualTo(new LineIntent.Invalid(LineIntent.InvalidReason.INVALID_MODEL,
                        "https://evil.example/?x=1"));
        assertThat(parser.parseText("/models " + "a".repeat(81)))
                .isEqualTo(new LineIntent.Invalid(LineIntent.InvalidReason.MODEL_QUERY_TOO_LONG, ""));
        assertThat(parser.parseText("快速翻譯:xx hello"))
                .isEqualTo(new LineIntent.Invalid(LineIntent.InvalidReason.UNSUPPORTED_LANGUAGE, "xx"));
        assertThat(parser.parseText("快速翻譯:en"))
                .isEqualTo(new LineIntent.Invalid(LineIntent.InvalidReason.QUICK_TRANSLATION_FORMAT, ""));
        assertThat(parser.parseText("/does-not-exist"))
                .isEqualTo(new LineIntent.Invalid(LineIntent.InvalidReason.UNKNOWN_COMMAND, "does-not-exist"));
        assertThat(parser.parseText("/style retired"))
                .isEqualTo(new LineIntent.Invalid(LineIntent.InvalidReason.INVALID_STYLE, "retired"));
        assertThat(parser.parseText("/translate-style formal"))
                .isEqualTo(new LineIntent.Invalid(
                        LineIntent.InvalidReason.STYLED_TRANSLATION_FORMAT, ""));
    }

    @Test
    void postbackUsesTheSameAllowlistedCommandParser() {
        assertThat(parser.parsePostback("command=%2Fstatus"))
                .isEqualTo(new LineIntent.UserCommand(LineIntent.UserAction.STATUS, ""));
        assertThat(parser.parsePostback("command=%2Fadmin%20usage%20summary"))
                .isEqualTo(new LineIntent.AdminCommand("usage summary"));
        assertThat(parser.parsePostback("url=https%3A%2F%2Fevil.example"))
                .isEqualTo(new LineIntent.Invalid(LineIntent.InvalidReason.UNSUPPORTED_POSTBACK, ""));
        assertThat(parser.parsePostback("command=" + "x".repeat(301)))
                .isEqualTo(new LineIntent.Invalid(LineIntent.InvalidReason.UNSUPPORTED_POSTBACK, ""));
    }

    @Test
    void translationActionPostbackContainsOnlyTypedRecordReferenceAndTarget() {
        String recordId = "507f1f77bcf86cd799439011";

        assertThat(parser.parsePostback(
                "command=%2Fretranslate+" + recordId + "+ja"))
                .isEqualTo(new LineIntent.Retranslate(recordId, "ja"));
        assertThat(parser.parsePostback("command=%2Fretranslate+bad%2Fid+ja"))
                .isEqualTo(new LineIntent.Invalid(
                        LineIntent.InvalidReason.TRANSLATION_ACTION_FORMAT, ""));
        assertThat(parser.parsePostback(
                "command=%2Fretranslate+" + recordId + "+xx"))
                .isEqualTo(new LineIntent.Invalid(
                        LineIntent.InvalidReason.TRANSLATION_ACTION_FORMAT, ""));
        assertThat(parser.parsePostback(
                "command=%2Frestyle+" + recordId + "+business"))
                .isEqualTo(new LineIntent.Restyle(recordId, "business"));
    }

    private static Stream<Arguments> commands() {
        return Stream.of(
                Arguments.of("/help", LineIntent.UserAction.HELP, ""),
                Arguments.of("/about", LineIntent.UserAction.ABOUT, ""),
                Arguments.of("/setmodel anthropic/claude-sonnet-4", LineIntent.UserAction.SET_MODEL,
                        "anthropic/claude-sonnet-4"),
                Arguments.of("/model openai/gpt-4o-mini", LineIntent.UserAction.SET_MODEL,
                        "openai/gpt-4o-mini"),
                Arguments.of("/models", LineIntent.UserAction.MODELS, ""),
                Arguments.of("/models claude", LineIntent.UserAction.MODELS, "claude"),
                Arguments.of("/styles", LineIntent.UserAction.STYLES, ""),
                Arguments.of("/style business", LineIntent.UserAction.SET_STYLE, "business"),
                Arguments.of("/外文翻譯 日文", LineIntent.UserAction.SET_FOREIGN_LANGUAGE, "日文"),
                Arguments.of("/profile", LineIntent.UserAction.PROFILE, ""),
                Arguments.of("/status", LineIntent.UserAction.STATUS, ""),
                Arguments.of("/lang", LineIntent.UserAction.LANGUAGE_MENU, ""),
                Arguments.of("/中文翻譯 vi", LineIntent.UserAction.SET_CHINESE_LANGUAGE, "vi"));
    }
}
