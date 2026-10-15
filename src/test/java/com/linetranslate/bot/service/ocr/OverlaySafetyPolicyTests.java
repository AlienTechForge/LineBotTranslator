package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        OcrRegion nonConflicting = region("non-conflicting", 60, 60, 20, 15, .9f, true);
        OverlaySafetyPlan overlap = policy.evaluate(List.of(
                new ImageRegionOverlay(a, "甲"), new ImageRegionOverlay(b, "乙"),
                new ImageRegionOverlay(nonConflicting, "丙")), 100, 100, enabled());
        assertThat(overlap.safe()).isTrue();
        assertThat(overlap.overlays()).extracting(value -> value.region().id())
                .containsExactly("a", "non-conflicting");
        assertThat(overlap.decisions()).contains(
                new OverlayRenderDecision("b", OverlayRenderStatus.PRESERVED, "overlap"));

        OcrRegion c1 = region("c1", 0, 0, 30, 50, .9f, true);
        OcrRegion c2 = region("c2", 38, 0, 30, 50, .9f, true);
        OcrRegion c3 = region("c3", 76, 0, 24, 50, .9f, true);
        OverlaySafetyPlan total = policy.evaluate(List.of(
                new ImageRegionOverlay(c1, "one"), new ImageRegionOverlay(c2, "two"),
                new ImageRegionOverlay(c3, "three")), 100, 100, enabled());
        assertThat(total.safe()).isTrue();
        // The limit gives up the largest block (c2 is wider than c3, which the image edge trims),
        // not the last one in reading order; the accepted regions stay in reading order.
        assertThat(total.overlays()).extracting(value -> value.region().id())
                .containsExactly("c1", "c3");
        assertThat(total.decisions()).contains(
                new OverlayRenderDecision("c2", OverlayRenderStatus.PRESERVED, "total-coverage"));
    }

    @Test
    void totalLimitPreservesTheLargestBlocksWhereverTheySitInsteadOfTheLastInReadingOrder() {
        ImageTranslationProperties limits = new ImageTranslationProperties(
                1000, 100, 10000, .6f, true, .25, .35);
        OverlaySafetyPlan plan = policy.evaluate(List.of(
                new ImageRegionOverlay(region("title", 5, 5, 90, 20, .98f, true), "標題"),
                new ImageRegionOverlay(region("one", 5, 30, 20, 8, .98f, true), "一"),
                new ImageRegionOverlay(region("two", 30, 30, 20, 8, .98f, true), "二"),
                new ImageRegionOverlay(region("three", 55, 30, 20, 8, .98f, true), "三"),
                new ImageRegionOverlay(region("four", 5, 45, 20, 8, .98f, true), "四"),
                new ImageRegionOverlay(region("five", 30, 45, 20, 8, .98f, true), "五")),
                100, 100, limits);

        assertThat(plan.overlays()).extracting(value -> value.region().id())
                .as("every small label survives, in reading order")
                .containsExactly("one", "two", "three", "four", "five");
        assertThat(plan.decisions()).containsExactly(
                new OverlayRenderDecision("title", OverlayRenderStatus.PRESERVED, "total-coverage"));
        assertThat(plan.skipped()).isEqualTo(1);
    }

    @Test
    void decisionsStayInCandidateOrderWhateverOrderTheLimitWasAppliedIn() {
        ImageTranslationProperties limits = new ImageTranslationProperties(
                1000, 100, 10000, .6f, true, .25, .35);
        OverlaySafetyPlan plan = policy.evaluate(List.of(
                new ImageRegionOverlay(region("big-a", 5, 5, 90, 20, .98f, true), "甲"),
                new ImageRegionOverlay(region("empty", 5, 30, 20, 8, .98f, true), " "),
                new ImageRegionOverlay(region("big-b", 5, 40, 90, 20, .98f, true), "乙"),
                new ImageRegionOverlay(region("small", 5, 70, 20, 8, .98f, true), "丙")),
                100, 100, limits);

        assertThat(plan.overlays()).extracting(value -> value.region().id())
                .containsExactly("big-a", "small");
        assertThat(plan.decisions()).containsExactly(
                new OverlayRenderDecision("empty", OverlayRenderStatus.PRESERVED, "mapping-empty"),
                new OverlayRenderDecision("big-b", OverlayRenderStatus.PRESERVED, "total-coverage"));
    }

    @Test
    void posterModeAllowsMoreTotalCoverageThanGeneralAndLessThanDocument() {
        ImageTranslationProperties limits = new ImageTranslationProperties(
                1000, 100, 10000, .6f, true, .25, .35, .60);
        List<ImageRegionOverlay> paragraphs = List.of(
                new ImageRegionOverlay(region("p1", 5, 5, 90, 20, .98f, true), "第一段"),
                new ImageRegionOverlay(region("p2", 5, 30, 90, 20, .98f, true), "第二段"),
                new ImageRegionOverlay(region("p3", 5, 55, 90, 20, .98f, true), "第三段"));

        assertThat(policy.evaluate(paragraphs, 100, 100, limits, OverlayContentMode.GENERAL).overlays())
                .hasSize(1);
        assertThat(policy.evaluate(paragraphs, 100, 100, limits, OverlayContentMode.POSTER).overlays())
                .hasSize(2);
        assertThat(policy.evaluate(paragraphs, 100, 100, limits, OverlayContentMode.DOCUMENT).overlays())
                .hasSize(3);
    }

    @Test
    void posterModeKeepsTheGeneralPerRegionLimit() {
        ImageTranslationProperties limits = new ImageTranslationProperties(
                1000, 100, 10000, .6f, true, .12, .35, .60);
        OverlaySafetyPlan poster = policy.evaluate(List.of(
                new ImageRegionOverlay(region("block", 5, 5, 90, 20, .98f, true), "區塊")),
                100, 100, limits, OverlayContentMode.POSTER);

        assertThat(poster.overlays()).isEmpty();
        assertThat(poster.decisions()).containsExactly(
                new OverlayRenderDecision("block", OverlayRenderStatus.PRESERVED, "region-coverage"));
    }

    @Test
    void posterLimitNeverFallsBelowTheGeneralLimitThatWasConfigured() {
        ImageTranslationProperties limits = new ImageTranslationProperties(
                1000, 100, 10000, .6f, true, .25, .70, .50);
        List<ImageRegionOverlay> lines = List.of(
                new ImageRegionOverlay(region("l1", 5, 5, 80, 20, .98f, true), "一"),
                new ImageRegionOverlay(region("l2", 5, 30, 80, 20, .98f, true), "二"),
                new ImageRegionOverlay(region("l3", 5, 55, 80, 20, .98f, true), "三"));

        assertThat(policy.evaluate(lines, 100, 100, limits, OverlayContentMode.POSTER).overlays())
                .hasSize(3);
    }

    /**
     * NaN fails every comparison, so "<= 0 || > 1" lets it through and a limit built on it never
     * limits: in the policy {@code total + ratio > NaN} is false for every region.
     */
    @Test
    void notANumberAndInfinityAreRejectedForEveryLimitThatIsARatio() {
        double nan = Double.NaN;
        double infinity = Double.POSITIVE_INFINITY;
        for (double bad : new double[] {nan, infinity, Double.NEGATIVE_INFINITY}) {
            assertThatThrownBy(() -> new ImageTranslationProperties(
                    1000, 100, 10000, .6f, true, .12, .35, bad))
                    .as("poster limit %s", bad).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ImageTranslationProperties(
                    1000, 100, 10000, .6f, true, .12, bad))
                    .as("total limit %s", bad).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ImageTranslationProperties(
                    1000, 100, 10000, .6f, true, bad, .35))
                    .as("region limit %s", bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ImageTranslationProperties(
                1000, 100, 10000, Float.NaN, true, .12, .35))
                .as("confidence threshold").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void planCarriesTheLimitsItWasApprovedUnderAndHowMuchOfTheTotalItUses() {
        ImageTranslationProperties limits = new ImageTranslationProperties(
                1000, 100, 10000, .6f, true, .25, .35, .60);
        List<ImageRegionOverlay> lines = List.of(
                new ImageRegionOverlay(region("l1", 5, 5, 50, 10, .98f, true), "一"),
                new ImageRegionOverlay(region("l2", 5, 30, 50, 10, .98f, true), "二"));

        OverlaySafetyPlan general = policy.evaluate(lines, 100, 100, limits);
        OverlaySafetyPlan poster = policy.evaluate(lines, 100, 100, limits, OverlayContentMode.POSTER);
        OverlaySafetyPlan document = policy.evaluate(lines, 100, 100, limits, OverlayContentMode.DOCUMENT);

        assertThat(general.maxRegionRatio()).isEqualTo(.25);
        assertThat(general.maxTotalRatio()).isEqualTo(.35);
        assertThat(poster.maxTotalRatio()).isEqualTo(.60);
        assertThat(document.maxTotalRatio()).isEqualTo(.90);
        assertThat(document.maxRegionRatio()).isEqualTo(.45);
        assertThat(general.acceptedRatio()).isGreaterThan(.10).isLessThan(.35);
        assertThat(general.remainingRatio()).isEqualTo(.35 - general.acceptedRatio());
    }

    @Test
    void planBuiltWithoutLimitsHasNoAllowanceToSpend() {
        OverlaySafetyPlan unaccounted = new OverlaySafetyPlan(true, "safe", List.of(), 0);

        assertThat(unaccounted.remainingRatio()).isZero();
        assertThat(unaccounted.maxRegionRatio()).isZero();
        assertThat(new OverlaySafetyPlan(true, "safe", List.of(), 0, List.of(),
                Double.NaN, Double.NaN, Double.NaN).remainingRatio()).isZero();
    }

    @Test
    void posterLimitIsValidatedAndDefaultsWhenOmitted() {
        assertThat(new ImageTranslationProperties(1000, 100, 10000, .6f, true, .12, .35)
                .posterMaxTotalMaskRatio()).isEqualTo(.60);
        assertThat(ImageTranslationProperties.defaults().posterMaxTotalMaskRatio()).isEqualTo(.60);
        assertThatThrownBy(() -> new ImageTranslationProperties(
                1000, 100, 10000, .6f, true, .12, .35, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageTranslationProperties(
                1000, 100, 10000, .6f, true, .12, .35, 1.01))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void documentModeAllowsDenseTextCoverageWithoutDisablingPerRegionSafety() {
        ImageTranslationProperties limits = new ImageTranslationProperties(
                1000, 100, 10000, .6f, true, .25, .35);
        List<ImageRegionOverlay> paragraphs = List.of(
                new ImageRegionOverlay(region("p1", 5, 5, 90, 20, .98f, true), "第一段"),
                new ImageRegionOverlay(region("p2", 5, 30, 90, 20, .98f, true), "第二段"),
                new ImageRegionOverlay(region("p3", 5, 55, 90, 20, .98f, true), "第三段"));

        OverlaySafetyPlan general = policy.evaluate(paragraphs, 100, 100, limits);
        assertThat(general.safe()).isTrue();
        assertThat(general.overlays()).hasSize(1);
        OverlaySafetyPlan document = policy.evaluate(
                paragraphs, 100, 100, limits, OverlayContentMode.DOCUMENT);

        assertThat(document.safe()).isTrue();
        assertThat(document.overlays()).hasSize(3);
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

    @Test
    void sourceCleanupUsesSymbolGeometryAndLeavesStructureBetweenGlyphsUntouched() {
        List<OcrPoint> wordPolygon = rectangle(10, 10, 100, 24);
        OcrWord word = new OcrWord("AB", wordPolygon, .99f, true, List.of(
                new OcrSymbol("A", rectangle(10, 10, 18, 24), .99f, true),
                new OcrSymbol("B", rectangle(36, 10, 18, 24), .99f, true)));
        OcrRegion region = new OcrRegion("glyph-mask", "AB", rectangle(5, 5, 115, 34),
                List.of(word), .99f, true, OcrBlockType.TEXT, List.of(), 0);

        java.awt.geom.Area cleanup = OverlaySafetyPolicy.sourceCleanupMask(region, 140, 60);

        assertThat(cleanup.contains(18, 20)).isTrue();
        assertThat(cleanup.contains(45, 20)).isTrue();
        assertThat(cleanup.contains(80, 20))
                .as("grid or icon space inside the coarse word box")
                .isFalse();
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

    private static List<OcrPoint> rectangle(int x, int y, int width, int height) {
        return List.of(new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height));
    }
}
