package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class OverlaySafetyPolicyTests {
    private final OverlaySafetyPolicy policy = new OverlaySafetyPolicy();

    @Test
    void killSwitchAndUnknownConfidenceFailClosed() {
        OcrRegion region = region("r1", 10, 10, 30, 20, .95f, true);
        ImageTranslationProperties disabled = new ImageTranslationProperties(
                1000, 100, 10000, .6f, false, .2, .4);
        OverlaySafetyPlan disabledPlan = policy.evaluate(
                List.of(new ImageRegionOverlay(region, "翻譯")), 100, 100, disabled);
        assertThat(disabledPlan.safe()).isFalse();
        assertThat(disabledPlan.decisions()).containsExactly(
                new OverlayRenderDecision("r1", OverlayRenderStatus.REJECTED, "disabled"));
        OcrRegion unknown = region("r2", 10, 10, 30, 20, 0, false);
        OverlaySafetyPlan plan = policy.evaluate(List.of(new ImageRegionOverlay(unknown, "翻譯")),
                100, 100, enabled());
        assertThat(plan.safe()).isTrue();
        assertThat(plan.overlays()).isEmpty();
    }

    @Test
    void blocksOversizedTotalAndOverlaps() {
        OcrRegion large = region("large", 0, 0, 80, 80, .9f, true);
        assertThat(policy.evaluate(List.of(new ImageRegionOverlay(large, "翻譯")),
                100, 100, enabled()).overlays()).isEmpty();
        OcrRegion safe = region("safe", 85, 0, 10, 10, .9f, true);
        OverlaySafetyPlan partial = policy.evaluate(List.of(
                new ImageRegionOverlay(large, "large"),
                new ImageRegionOverlay(safe, "safe")), 100, 100, enabled());
        assertThat(partial.safe()).isTrue();
        assertThat(partial.overlays()).extracting(value -> value.region().id()).containsExactly("safe");
        assertThat(partial.decisions()).contains(
                new OverlayRenderDecision("large", OverlayRenderStatus.PRESERVED, "region-coverage"));
        OcrRegion a = region("a", 5, 5, 30, 20, .9f, true);
        OcrRegion b = region("b", 20, 10, 30, 20, .9f, true);
        assertThat(policy.evaluate(List.of(new ImageRegionOverlay(a, "甲"), new ImageRegionOverlay(b, "乙")),
                100, 100, enabled()).safe()).isFalse();

        OcrRegion c1 = region("c1", 0, 0, 30, 50, .9f, true);
        OcrRegion c2 = region("c2", 38, 0, 30, 50, .9f, true);
        OcrRegion c3 = region("c3", 76, 0, 24, 50, .9f, true);
        OverlaySafetyPlan total = policy.evaluate(List.of(
                new ImageRegionOverlay(c1, "one"), new ImageRegionOverlay(c2, "two"),
                new ImageRegionOverlay(c3, "three")), 100, 100, enabled());
        assertThat(total.safe()).isFalse();
        assertThat(total.reason()).isEqualTo("total-coverage");
    }

    @Test
    void invalidAndOutOfBoundsGeometryNeverReachRenderer() {
        OcrRegion degenerate = new OcrRegion("bad", "text", List.of(
                new OcrPoint(1, 1), new OcrPoint(1, 1), new OcrPoint(1, 1), new OcrPoint(1, 1)),
                List.of(), .9f, true, OcrBlockType.TEXT, List.of(), 0);
        OcrRegion outside = region("outside", 90, 90, 20, 20, .9f, true);
        OverlaySafetyPlan plan = policy.evaluate(List.of(
                new ImageRegionOverlay(degenerate, "one"),
                new ImageRegionOverlay(outside, "two")), 100, 100, enabled());
        assertThat(plan.safe()).isTrue();
        assertThat(plan.overlays()).isEmpty();
        assertThat(plan.skipped()).isEqualTo(2);
    }

    @Test
    void adjacentParagraphsRemainSafeWhenOnlyCleanupPaddingOverlaps() {
        OcrRegion first = region("first", 10, 10, 60, 20, .98f, true);
        OcrRegion second = region("second", 10, 33, 60, 20, .98f, true);
        ImageTranslationProperties limits = new ImageTranslationProperties(
                1000, 100, 10000, .6f, true, .2, .5);

        OverlaySafetyPlan plan = policy.evaluate(List.of(
                new ImageRegionOverlay(first, "第一行"),
                new ImageRegionOverlay(second, "第二行")), 100, 100, limits);

        assertThat(plan.safe()).isTrue();
        assertThat(plan.overlays()).extracting(value -> value.region().id())
                .containsExactly("first", "second");
    }

    private static ImageTranslationProperties enabled() {
        return new ImageTranslationProperties(1000, 100, 10000, .6f, true, .25, .4);
    }

    private static OcrRegion region(String id, int x, int y, int width, int height,
            float confidence, boolean known) {
        return new OcrRegion(id, "text", List.of(
                new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height)),
                List.of(), confidence, known, OcrBlockType.TEXT, List.of(), 0);
    }
}
