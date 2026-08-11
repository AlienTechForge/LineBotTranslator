package com.linetranslate.bot.service.line;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.linecorp.bot.jackson.ModelObjectMapper;
import com.linecorp.bot.messaging.model.FlexBox;
import com.linecorp.bot.messaging.model.FlexBubble;
import com.linecorp.bot.messaging.model.FlexButton;
import com.linecorp.bot.messaging.model.FlexComponent;
import com.linecorp.bot.messaging.model.FlexMessage;
import com.linecorp.bot.messaging.model.FlexText;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.PostbackAction;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linetranslate.bot.service.ai.AiModelDescriptor;
import com.linetranslate.bot.service.ai.AiModelPage;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class AdminCardRenderer {

    private static final int MAX_ALT_TEXT_CODE_POINTS = 1500;
    private static final int MAX_TEXT_MESSAGE_CODE_POINTS = 5000;
    private static final int MAX_BUBBLE_JSON_BYTES = 30 * 1024;
    private static final String INFO_COLOR = "#2457C5";
    private static final String SUCCESS_COLOR = "#16835D";
    private static final String ERROR_COLOR = "#B42318";
    private static final Pattern MODEL_SELECTION_COMMAND = Pattern.compile(
            "^/admin config model [A-Za-z0-9~][A-Za-z0-9._:~/\\-]{0,199}$");
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "/admin",
            "/admin stats",
            "/admin today",
            "/admin users",
            "/admin models",
            "/admin config",
            "/admin usage",
            "/admin usage summary");

    private static final List<AdminCardAction> DASHBOARD_ACTIONS = List.of(
            new AdminCardAction("系統統計", "/admin stats"),
            new AdminCardAction("今日狀態", "/admin today"),
            new AdminCardAction("最近使用者", "/admin users"),
            new AdminCardAction("選擇預設模型", "/admin models"),
            new AdminCardAction("系統設定", "/admin config"),
            new AdminCardAction("本月用量", "/admin usage"),
            new AdminCardAction("用量總覽", "/admin usage summary"));

    private static final List<AdminCardAction> HOME_ACTION =
            List.of(new AdminCardAction("返回管理面板", "/admin"));

    public Message dashboard() {
        return renderCard(
                "管理員控制台",
                "選擇要查看的管理功能。\n\n"
                        + "需輸入參數的操作：\n"
                        + "/admin broadcast [訊息]\n"
                        + "/admin user [用戶 ID]\n"
                        + "/admin nickname [用戶 ID] [暱稱]\n"
                        + "/admin add [用戶 ID]\n"
                        + "/admin remove [用戶 ID]\n\n"
                        + "每次操作都會重新驗證管理員權限。",
                DASHBOARD_ACTIONS,
                INFO_COLOR);
    }

    public Message info(String title, String body) {
        return renderCard(title, body, HOME_ACTION, INFO_COLOR);
    }

    public Message success(String title, String body) {
        return renderCard(title, body, HOME_ACTION, SUCCESS_COLOR);
    }

    public Message error(String title, String body) {
        return renderCard(title, body, HOME_ACTION, ERROR_COLOR);
    }

    public Message accessDenied() {
        return renderCard(
                "沒有管理員權限",
                "此操作只開放給已授權的管理員。",
                List.of(),
                ERROR_COLOR);
    }

    public Message card(String title, String body, List<AdminCardAction> actions) {
        return renderCard(title, body, actions, INFO_COLOR);
    }

    public Message modelSelection(AiModelPage page, String query, String currentModel) {
        AiModelPage safePage = page == null ? new AiModelPage(List.of(), 0, true) : page;
        String safeQuery = query == null ? "" : sanitize(query, "");
        String safeCurrent = sanitize(currentModel, "未設定");
        String scope = safeQuery.isBlank() ? "全部模型" : "搜尋：「" + safeQuery + "」";
        String body = scope + "\n"
                + "顯示 " + safePage.models().size() + " / " + safePage.total() + " 個\n"
                + "目前預設：" + safeCurrent
                + (safePage.stale() ? "\n\n⚠️ 目前顯示快取／備援清單。" : "")
                + "\n\n點選模型即可設為全域預設。"
                + "\n需要篩選時輸入：/admin models [搜尋字]";

        List<AdminCardAction> actions = new ArrayList<>();
        for (AiModelDescriptor model : safePage.models()) {
            String prefix = model.id().equals(currentModel) ? "✓ " : "";
            String label = truncateByCodePoint(
                    prefix + sanitize(model.displayName(), model.id()), 40);
            actions.add(new AdminCardAction(
                    label,
                    "/admin config model " + model.id()));
        }
        actions.addAll(HOME_ACTION);
        return renderCard("選擇 OpenRouter 模型", body, actions, INFO_COLOR);
    }

    private Message renderCard(String title, String body, List<AdminCardAction> actions, String accentColor) {
        String safeTitle = sanitize(title, "管理員訊息");
        String safeBody = sanitize(body, "暫無資料");
        List<AdminCardAction> safeActions = actions == null ? List.of() : List.copyOf(actions);
        validateActions(safeActions);

        FlexBox header = new FlexBox.Builder(
                FlexBox.Layout.VERTICAL,
                List.of(new FlexText.Builder()
                        .text(safeTitle)
                        .color("#FFFFFF")
                        .weight(FlexText.Weight.BOLD)
                        .size("lg")
                        .wrap(true)
                        .build()))
                .backgroundColor(accentColor)
                .paddingAll("18px")
                .build();

        FlexBox bodyBox = new FlexBox.Builder(
                FlexBox.Layout.VERTICAL,
                List.of(new FlexText.Builder()
                        .text(safeBody)
                        .color("#202124")
                        .size("sm")
                        .wrap(true)
                        .build()))
                .paddingAll("18px")
                .build();

        List<FlexComponent> buttons = safeActions.stream()
                .map(this::button)
                .map(FlexComponent.class::cast)
                .toList();
        FlexBox footer = buttons.isEmpty()
                ? null
                : new FlexBox.Builder(FlexBox.Layout.VERTICAL, buttons)
                        .spacing("sm")
                        .paddingAll("12px")
                        .build();

        FlexBubble bubble = new FlexBubble.Builder()
                .size(FlexBubble.Size.MEGA)
                .header(header)
                .body(bodyBox)
                .footer(footer)
                .build();
        String altText = truncateByCodePoint(safeTitle + "：" + safeBody, MAX_ALT_TEXT_CODE_POINTS);
        FlexMessage message = new FlexMessage.Builder(altText, bubble).build();

        try {
            int payloadSize = ModelObjectMapper.createNewObjectMapper().writeValueAsBytes(bubble).length;
            if (payloadSize <= MAX_BUBBLE_JSON_BYTES) {
                return message;
            }
            log.warn("管理員卡片超過 LINE bubble 限制，改用純文字: bytes={}", payloadSize);
        } catch (Exception e) {
            log.warn("管理員卡片序列化失敗，改用純文字: type={}", e.getClass().getSimpleName());
        }

        return fallbackText(safeTitle, safeBody);
    }

    private FlexButton button(AdminCardAction action) {
        String data = "command=" + URLEncoder.encode(action.command(), StandardCharsets.UTF_8);
        PostbackAction postbackAction = new PostbackAction(
                action.label(), data, null, null, null, null);
        return new FlexButton.Builder(postbackAction)
                .style(FlexButton.Style.SECONDARY)
                .height(FlexButton.Height.SM)
                .color("#E8EEF9")
                .build();
    }

    private void validateActions(List<AdminCardAction> actions) {
        for (AdminCardAction action : actions) {
            if (action == null || !isAllowedCommand(action.command())) {
                throw new IllegalArgumentException("Admin card action is not on the allowlist");
            }
            if (action.label() == null || action.label().isBlank()
                    || action.label().codePointCount(0, action.label().length()) > 40) {
                throw new IllegalArgumentException("Admin card action label is invalid");
            }
        }
    }

    private static boolean isAllowedCommand(String command) {
        return command != null
                && (ALLOWED_COMMANDS.contains(command)
                        || MODEL_SELECTION_COMMAND.matcher(command).matches());
    }

    private Message fallbackText(String title, String body) {
        String marker = "\n\n[內容過長，已改用純文字並截斷]";
        String prefix = title + "\n\n";
        int available = MAX_TEXT_MESSAGE_CODE_POINTS
                - prefix.codePointCount(0, prefix.length())
                - marker.codePointCount(0, marker.length());
        return new TextMessage(prefix + truncateByCodePoint(body, Math.max(0, available)) + marker);
    }

    private static String sanitize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "�").trim();
    }

    private static String truncateByCodePoint(String value, int maxCodePoints) {
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) {
            return value;
        }
        int endIndex = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, endIndex);
    }

    public record AdminCardAction(String label, String command) {
    }
}
