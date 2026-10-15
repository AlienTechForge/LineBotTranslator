package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Production log of a 723x1024 Vietnamese infographic: 38 regions, every one reliable and
 * horizontal, {@code verdict=too-colourful}, nine regions kept for {@code total-coverage} at a
 * total of 0.3497 against the 0.35 limit, which were the last nine in reading order: the whole of
 * section 6. {@link SyntheticPoster} has the same shape (38 lines, the last nine being section 6,
 * 29 fitting under 35% and the 30th not), so this replays that plan without any real content.
 */
class OverlayPosterCoverageTests {

    private static final ImageTranslationProperties PROPERTIES = ImageTranslationProperties.defaults();
    private final OverlaySafetyPolicy policy = new OverlaySafetyPolicy();
    private final OverlayContentClassifier classifier = new OverlayContentClassifier();

    @Test
    void theSyntheticPlanReproducesTheProductionShapeUnderTheGeneralLimit() {
        OverlaySafetyPlan plan = evaluate(OverlayContentMode.GENERAL);

        assertThat(plan.overlays()).hasSize(29);
        assertThat(plan.skipped()).isEqualTo(SyntheticPoster.LAST_SECTION_REGIONS);
        assertThat(plan.decisions()).hasSize(SyntheticPoster.LAST_SECTION_REGIONS)
                .allSatisfy(decision -> assertThat(decision.reason()).isEqualTo("total-coverage"));
    }

    /**
     * Same limit, same 29 regions' worth of coverage, but the regions given up are the largest
     * blocks anywhere on the poster, so section 6 keeps most of its translation.
     */
    @Test
    void generalLimitSpreadsThePreservedRegionsInsteadOfDroppingTheWholeLastSection() {
        OverlaySafetyPlan plan = evaluate(OverlayContentMode.GENERAL);

        Set<String> accepted = plan.overlays().stream()
                .map(overlay -> overlay.region().id()).collect(Collectors.toSet());
        List<String> lastSection = java.util.stream.IntStream
                .range(SyntheticPoster.REGIONS - SyntheticPoster.LAST_SECTION_REGIONS, SyntheticPoster.REGIONS)
                .mapToObj(SyntheticPoster::regionId).toList();
        assertThat(lastSection).as("section 6 is no longer wholly left in the source language")
                .anyMatch(accepted::contains);
        assertThat(lastSection.stream().filter(accepted::contains).count())
                .as("most of section 6 is translated")
                .isGreaterThanOrEqualTo(5);

        double smallestPreserved = plan.decisions().stream()
                .map(decision -> regionArea(decision.regionId()))
                .mapToDouble(Double::doubleValue).min().orElseThrow();
        double largestAccepted = plan.overlays().stream()
                .mapToDouble(overlay -> overlay.region().area()).max().orElseThrow();
        assertThat(largestAccepted)
                .as("everything preserved for coverage is at least as large as everything accepted")
                .isLessThanOrEqualTo(smallestPreserved);
    }

    @Test
    void posterIsRecognisedAndThenTranslatedInFull() {
        BufferedImage image = SyntheticPoster.posterImage();

        OverlayContentMode mode = classifier.classify(image, SyntheticPoster.regions());
        OverlaySafetyPlan plan = evaluate(mode);

        assertThat(mode).isEqualTo(OverlayContentMode.POSTER);
        assertThat(plan.overlays()).hasSize(SyntheticPoster.REGIONS);
        assertThat(plan.decisions()).isEmpty();
        assertThat(plan.skipped()).isZero();
    }

    @Test
    void photoWithTheSameTextGeometryKeepsTheGeneralLimit() {
        BufferedImage image = SyntheticPoster.photoImage();

        OverlayContentMode mode = classifier.classify(image, SyntheticPoster.regions());
        OverlaySafetyPlan plan = evaluate(mode);

        assertThat(mode).isEqualTo(OverlayContentMode.GENERAL);
        assertThat(plan.overlays()).hasSize(29);
    }

    @Test
    void posterLimitStillCapsTheTotalWhenTheLayoutIsSmallerThanTheCoverageItAsksFor() {
        // 38 lines already take 45% of the poster; a stricter poster limit keeps the cap real.
        ImageTranslationProperties strict = new ImageTranslationProperties(
                10_485_760, 4_096, 16_000_000, 0.60f, true, .12, .35, .40);

        OverlaySafetyPlan plan = policy.evaluate(SyntheticPoster.overlays(), SyntheticPoster.WIDTH,
                SyntheticPoster.HEIGHT, strict, OverlayContentMode.POSTER);

        assertThat(plan.overlays()).hasSizeLessThan(SyntheticPoster.REGIONS).hasSizeGreaterThan(29);
        assertThat(plan.decisions()).allSatisfy(decision ->
                assertThat(decision.reason()).isEqualTo("total-coverage"));
    }

    private OverlaySafetyPlan evaluate(OverlayContentMode mode) {
        return policy.evaluate(SyntheticPoster.overlays(), SyntheticPoster.WIDTH,
                SyntheticPoster.HEIGHT, PROPERTIES, mode);
    }

    private static double regionArea(String id) {
        return SyntheticPoster.regions().stream()
                .filter(region -> region.id().equals(id)).findFirst().orElseThrow().area();
    }
}
