package com.linetranslate.bot.service.line.intent;

import java.util.Locale;

import org.springframework.stereotype.Component;

/** Pure parser that owns administrator command grammar and argument validation. */
@Component
public class AdminIntentParser {

    private static final int MAX_BROADCAST_CODE_POINTS = 5000;
    private static final int MAX_MODEL_QUERY_CODE_POINTS = 80;

    public AdminIntent parse(String command) {
        String normalized = command == null ? "" : command.trim();
        if (normalized.isEmpty()) {
            return action(AdminIntent.Action.DASHBOARD);
        }
        String[] parts = normalized.split("\\s+", 2);
        String commandName = parts[0].toLowerCase(Locale.ROOT);
        String parameter = parts.length > 1 ? parts[1].trim() : "";

        return switch (commandName) {
            case "help" -> action(AdminIntent.Action.DASHBOARD);
            case "isadmin" -> action(AdminIntent.Action.VERIFY);
            case "broadcast" -> broadcast(parameter);
            case "stats" -> action(AdminIntent.Action.STATS);
            case "today" -> action(AdminIntent.Action.TODAY);
            case "users" -> action(AdminIntent.Action.USERS);
            case "user" -> required(parameter, AdminIntent.Action.USER, AdminIntent.Problem.USER_REQUIRED);
            case "nickname" -> nickname(parameter);
            case "models" -> parameter.codePointCount(0, parameter.length()) > MAX_MODEL_QUERY_CODE_POINTS
                    ? AdminIntent.invalid(AdminIntent.Problem.MODEL_QUERY_TOO_LONG)
                    : AdminIntent.action(AdminIntent.Action.MODELS, parameter, "");
            case "config" -> config(parameter);
            case "usage" -> usage(parameter);
            case "add" -> required(parameter, AdminIntent.Action.ADD_ADMIN, AdminIntent.Problem.ADD_ADMIN_REQUIRED);
            case "remove" -> required(
                    parameter, AdminIntent.Action.REMOVE_ADMIN, AdminIntent.Problem.REMOVE_ADMIN_REQUIRED);
            default -> AdminIntent.action(AdminIntent.Action.UNKNOWN, commandName, "");
        };
    }

    private static AdminIntent broadcast(String parameter) {
        if (parameter.isEmpty()) {
            return AdminIntent.invalid(AdminIntent.Problem.BROADCAST_REQUIRED);
        }
        if (parameter.codePointCount(0, parameter.length()) > MAX_BROADCAST_CODE_POINTS) {
            return AdminIntent.invalid(AdminIntent.Problem.BROADCAST_TOO_LONG);
        }
        return AdminIntent.action(AdminIntent.Action.BROADCAST, parameter, "");
    }

    private static AdminIntent nickname(String parameter) {
        if (parameter.isEmpty()) {
            return AdminIntent.invalid(AdminIntent.Problem.NICKNAME_FORMAT);
        }
        String[] parts = parameter.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            return AdminIntent.invalid(AdminIntent.Problem.NICKNAME_REQUIRED);
        }
        return AdminIntent.action(AdminIntent.Action.NICKNAME, parts[0], parts[1].trim());
    }

    private static AdminIntent config(String parameter) {
        if (parameter.isEmpty()) {
            return action(AdminIntent.Action.CONFIG_SHOW);
        }
        String[] parts = parameter.split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String value = parts.length > 1 ? parts[1].trim() : "";
        if (value.isEmpty()) {
            return AdminIntent.invalid(AdminIntent.Problem.CONFIG_VALUE_REQUIRED);
        }
        AdminIntent.Action action = switch (command) {
            case "c2lang" -> AdminIntent.Action.CONFIG_C2LANG;
            case "lang" -> AdminIntent.Action.CONFIG_LANGUAGE;
            case "model", "openrouter" -> AdminIntent.Action.CONFIG_MODEL;
            case "ocr" -> AdminIntent.Action.CONFIG_OCR;
            case "image-proxy", "short-url" -> AdminIntent.Action.CONFIG_SHORT_URL;
            default -> AdminIntent.Action.UNKNOWN_CONFIG;
        };
        return AdminIntent.action(action, action == AdminIntent.Action.UNKNOWN_CONFIG ? command : value, "");
    }

    private static AdminIntent usage(String parameter) {
        if (parameter.isEmpty()) {
            return action(AdminIntent.Action.USAGE_CURRENT_MONTH);
        }
        String[] parts = parameter.split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String value = parts.length > 1 ? parts[1].trim() : "";
        return switch (command) {
            case "day" -> required(
                    value, AdminIntent.Action.USAGE_DAY, AdminIntent.Problem.USAGE_DAY_REQUIRED);
            case "month" -> required(
                    value, AdminIntent.Action.USAGE_MONTH, AdminIntent.Problem.USAGE_MONTH_REQUIRED);
            case "provider" -> required(
                    value, AdminIntent.Action.USAGE_PROVIDER, AdminIntent.Problem.USAGE_PROVIDER_REQUIRED);
            case "model" -> required(
                    value, AdminIntent.Action.USAGE_MODEL, AdminIntent.Problem.USAGE_MODEL_REQUIRED);
            case "type" -> required(
                    value, AdminIntent.Action.USAGE_TYPE, AdminIntent.Problem.USAGE_TYPE_REQUIRED);
            case "summary" -> action(AdminIntent.Action.USAGE_SUMMARY);
            default -> AdminIntent.action(AdminIntent.Action.UNKNOWN_USAGE, command, "");
        };
    }

    private static AdminIntent required(
            String value,
            AdminIntent.Action action,
            AdminIntent.Problem problem) {
        return value.isEmpty() ? AdminIntent.invalid(problem) : AdminIntent.action(action, value, "");
    }

    private static AdminIntent action(AdminIntent.Action action) {
        return AdminIntent.action(action, "", "");
    }
}
