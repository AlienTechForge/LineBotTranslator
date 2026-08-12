package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class OverlayDegradationSummaryTests {

    @Test
    void coverageAndFontSkipsAreNotReportedAsLowConfidence() {
        OverlayDegradationSummary summary = OverlayDegradationSummary.fromDecisions(List.of(
                new OverlayRenderDecision("a", OverlayRenderStatus.PRESERVED, "region-coverage"),
                new OverlayRenderDecision("b", OverlayRenderStatus.PRESERVED, "font-coverage"),
                new OverlayRenderDecision("c", OverlayRenderStatus.PRESERVED, "untrusted-confidence")));

        assertThat(summary.count(OverlayDegradationReason.COVERAGE)).isEqualTo(1);
        assertThat(summary.count(OverlayDegradationReason.FONT)).isEqualTo(1);
        assertThat(summary.count(OverlayDegradationReason.LOW_CONFIDENCE)).isEqualTo(1);
        assertThat(summary.total()).isEqualTo(3);
    }
}
