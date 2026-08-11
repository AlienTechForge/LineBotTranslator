package com.linetranslate.bot.service.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class UsageReportRendererTests {

    @Test
    void rendersAggregatedFactsWithoutRecalculatingCost() {
        UsageReport report = new UsageReport(
                3, 2, 1, 2, 1,
                100, 50, 150, 300,
                new BigDecimal("0.12345678"),
                "USD",
                List.of(new UsageBreakdown("openai", 3, new BigDecimal("0.12345678"))),
                List.of(new UsageBreakdown("gpt-4o", 3, new BigDecimal("0.12345678"))));

        String rendered = new UsageReportRenderer().render("2026-08", report);

        assertThat(rendered)
                .contains("總次數: 3")
                .contains("成功: 2")
                .contains("失敗: 1")
                .contains("文字: 2")
                .contains("圖片: 1")
                .contains("Token: 150")
                .contains("平均延遲: 100 ms")
                .contains("openai: 3 次, USD 0.123457")
                .contains("gpt-4o: 3 次, USD 0.123457")
                .contains("總費用: USD 0.123457");
    }
}
