package com.linetranslate.bot.service.line.intent;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.linetranslate.bot.util.LanguageUtils;

/** Pure parser and validation boundary shared by LINE message and postback input. */
@Component
public class LineIntentParser {

    private static final String QUICK_TRANSLATION_ASCII = "快速翻譯:";
    private static final String QUICK_TRANSLATION_FULL_WIDTH = "快速翻譯：";
    private static final String POSTBACK_COMMAND = "command=";
    private static final int MAX_POSTBACK_CODE_POINTS = 300;

    public LineIntent parseText(String text) {
        String safeText = text == null ? "" : text;
        if (safeText.startsWith("/")) {
            return parseCommand(safeText);
        }
        if (safeText.startsWith(QUICK_TRANSLATION_ASCII)
                || safeText.startsWith(QUICK_TRANSLATION_FULL_WIDTH)) {
            return parseQuickTranslation(safeText);
        }
        return new LineIntent.TranslateText(safeText);
    }

    public LineIntent parsePostback(String data) {
        if (data == null
                || data.codePointCount(0, data.length()) > MAX_POSTBACK_CODE_POINTS
                || !data.startsWith(POSTBACK_COMMAND)) {
            return invalid(LineIntent.InvalidReason.UNSUPPORTED_POSTBACK);
        }
        try {
            String command = URLDecoder.decode(
                    data.substring(POSTBACK_COMMAND.length()), StandardCharsets.UTF_8);
            if (!command.startsWith("/")) {
                return invalid(LineIntent.InvalidReason.UNSUPPORTED_POSTBACK);
            }
            return parseCommand(command);
        } catch (IllegalArgumentException exception) {
            return invalid(LineIntent.InvalidReason.UNSUPPORTED_POSTBACK);
        }
    }

    private LineIntent parseCommand(String command) {
        String content = command.substring(1).trim();
        String[] parts = content.split("\\s+", 2);
        String action = parts.length == 0 ? "" : parts[0].toLowerCase(Locale.ROOT);
        String argument = parts.length > 1 ? parts[1].trim() : "";

        return switch (action) {
            case "help" -> user(LineIntent.UserAction.HELP);
            case "about" -> user(LineIntent.UserAction.ABOUT);
            case "setai" -> argument.isEmpty()
                    ? invalid(LineIntent.InvalidReason.AI_PROVIDER_REQUIRED)
                    : user(LineIntent.UserAction.SET_AI, argument.toLowerCase(Locale.ROOT));
            case "setmodel" -> argument.isEmpty()
                    ? invalid(LineIntent.InvalidReason.MODEL_REQUIRED)
                    : user(LineIntent.UserAction.SET_MODEL, argument);
            case "models" -> user(LineIntent.UserAction.MODELS);
            case "外文翻譯" -> argument.isEmpty()
                    ? invalid(LineIntent.InvalidReason.FOREIGN_LANGUAGE_REQUIRED)
                    : user(LineIntent.UserAction.SET_FOREIGN_LANGUAGE, argument);
            case "profile" -> user(LineIntent.UserAction.PROFILE);
            case "status" -> user(LineIntent.UserAction.STATUS);
            case "lang" -> user(LineIntent.UserAction.LANGUAGE_MENU);
            case "中文翻譯" -> argument.isEmpty()
                    ? invalid(LineIntent.InvalidReason.CHINESE_LANGUAGE_REQUIRED)
                    : user(LineIntent.UserAction.SET_CHINESE_LANGUAGE, argument);
            case "adminhelp" -> new LineIntent.AdminCommand("help");
            case "admin" -> new LineIntent.AdminCommand(argument);
            case "isadmin" -> new LineIntent.AdminCommand("isadmin");
            default -> new LineIntent.Invalid(LineIntent.InvalidReason.UNKNOWN_COMMAND, action);
        };
    }

    private LineIntent parseQuickTranslation(String receivedText) {
        int prefixLength = receivedText.startsWith(QUICK_TRANSLATION_ASCII)
                ? QUICK_TRANSLATION_ASCII.length()
                : QUICK_TRANSLATION_FULL_WIDTH.length();
        String[] parts = receivedText.substring(prefixLength).trim().split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            return invalid(LineIntent.InvalidReason.QUICK_TRANSLATION_FORMAT);
        }
        String language = parts[0].trim();
        if (!LanguageUtils.isSupported(language)) {
            return new LineIntent.Invalid(LineIntent.InvalidReason.UNSUPPORTED_LANGUAGE, language);
        }
        return new LineIntent.QuickTranslate(language, parts[1].trim());
    }

    private static LineIntent.UserCommand user(LineIntent.UserAction action) {
        return user(action, "");
    }

    private static LineIntent.UserCommand user(LineIntent.UserAction action, String argument) {
        return new LineIntent.UserCommand(action, argument);
    }

    private static LineIntent.Invalid invalid(LineIntent.InvalidReason reason) {
        return new LineIntent.Invalid(reason, "");
    }
}
