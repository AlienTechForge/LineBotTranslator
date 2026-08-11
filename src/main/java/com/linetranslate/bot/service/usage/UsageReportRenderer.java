package com.linetranslate.bot.service.usage;

import java.math.RoundingMode;

import org.springframework.stereotype.Component;

/** LINE-neutral renderer for usage accounting reports. */
@Component
public class UsageReportRenderer {

    public String render(String title, UsageReport report) {
        String heading = "💰 " + title + " 的 API 使用量和費用";
        if (report.totalExecutions() == 0) {
            return heading + "\n\n沒有任何 API 使用記錄。";
        }

        StringBuilder result = new StringBuilder(heading).append("\n\n")
                .append("【Provider executions】\n")
                .append("總次數: ").append(report.totalExecutions()).append("\n")
                .append("成功: ").append(report.successfulExecutions()).append("\n")
                .append("失敗: ").append(report.failedExecutions()).append("\n")
                .append("文字: ").append(report.textExecutions()).append("\n")
                .append("圖片: ").append(report.imageExecutions()).append("\n")
                .append("Token: ").append(report.totalTokens())
                .append(" (input ").append(report.inputTokens())
                .append(" / output ").append(report.outputTokens()).append(")\n")
                .append("平均延遲: ")
                .append(report.totalLatencyMillis() / report.totalExecutions()).append(" ms\n\n")
                .append("【提供者】\n");

        appendBreakdown(result, report.byProvider(), report.currency());
        result.append("\n【模型】\n");
        appendBreakdown(result, report.byModel(), report.currency());
        result.append("\n【費用】\n")
                .append("總費用: ").append(report.currency()).append(" ")
                .append(report.totalCost().setScale(6, RoundingMode.HALF_UP).toPlainString());
        return result.toString();
    }

    private static void appendBreakdown(
            StringBuilder target,
            java.util.List<UsageBreakdown> breakdowns,
            String currency) {
        for (UsageBreakdown breakdown : breakdowns) {
            target.append(breakdown.key()).append(": ")
                    .append(breakdown.executions()).append(" 次, ")
                    .append(currency).append(" ")
                    .append(breakdown.cost().setScale(6, RoundingMode.HALF_UP).toPlainString())
                    .append("\n");
        }
    }
}
