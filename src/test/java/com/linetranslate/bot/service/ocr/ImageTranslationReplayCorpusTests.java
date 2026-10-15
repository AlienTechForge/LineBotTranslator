package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linetranslate.bot.service.translation.ImageRegionTranslationInput;
import com.linetranslate.bot.service.translation.StructuredImageTranslationCodec;

/** Offline, credential-free replay gates for the two observed failure geometries. */
class ImageTranslationReplayCorpusTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OcrRegionQualificationPolicy qualification = new OcrRegionQualificationPolicy();

    @Test
    void vietnameseDensePosterKeepsStableIdsAndDominantLanguage() throws Exception {
        ReplayFixture fixture = load("vietnamese-poster.json");
        assertThat(fixture.schemaVersion()).isEqualTo("image-replay-v1");
        assertThat(new OcrSourceLanguageResolver().resolve(fixture.regions())).isEqualTo("vi");
        assertThat(fixture.regions()).extracting(OcrRegion::id)
                .containsExactly("vi-title", "vi-body");
        assertThat(fixture.regions()).allSatisfy(region -> assertThat(
                qualification.decide(region, .6f).qualification()).isEqualTo(OcrQualification.TRANSLATE));
        assertStructuredResponse(fixture);
        writeDiagnostic(fixture);
    }

    @Test
    void koreanPackageRejectsDecorationAndPreservesDateAndPercent() throws Exception {
        ReplayFixture fixture = load("korean-package.json");
        assertThat(new OcrSourceLanguageResolver().resolve(fixture.regions())).isEqualTo("ko");
        assertThat(fixture.regions().stream().filter(r -> r.id().equals("en-brand")).findFirst().orElseThrow()
                .languages()).extracting(OcrDetectedLanguage::code).containsExactly("en");
        assertDecision(fixture, "decoration", OcrQualification.REJECT);
        assertDecision(fixture, "date", OcrQualification.PRESERVE);
        assertDecision(fixture, "percent", OcrQualification.PRESERVE);
        assertDecision(fixture, "ko-product", OcrQualification.TRANSLATE);
        assertStructuredResponse(fixture);
        writeDiagnostic(fixture);
    }

    @Test
    void maintainerCanReplaySanitizedGeometryAgainstAnExternalPrivateImage() throws Exception {
        String external = System.getProperty("image.translation.replay.image", "").strip();
        Assumptions.assumeTrue(!external.isBlank(), "external replay is opt-in");
        byte[] bytes = Files.readAllBytes(Path.of(external));
        ValidatedImage validated = new ImageInputValidator(ImageTranslationProperties.defaults())
                .validate(bytes, null);
        ReplayFixture fixture = load("korean-package.json");
        double scaleX = validated.image().getWidth() / 900d;
        double scaleY = validated.image().getHeight() / 580d;
        BufferedImage annotated = new BufferedImage(validated.image().getWidth(), validated.image().getHeight(),
                validated.image().getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = annotated.createGraphics();
        try {
            graphics.drawImage(validated.image(), 0, 0, null);
            graphics.setColor(Color.MAGENTA);
            for (OcrRegion region : fixture.regions()) {
                List<OcrPoint> scaled = region.polygon().stream()
                        .map(point -> new OcrPoint((int) Math.round(point.x() * scaleX),
                                (int) Math.round(point.y() * scaleY))).toList();
                graphics.draw(OverlaySafetyPolicy.polygon(scaled));
                graphics.drawString(region.id(), scaled.get(0).x(), Math.max(10, scaled.get(0).y()));
            }
        } finally {
            graphics.dispose();
        }
        Path output = Path.of("target", "image-translation-replay", "external-private-regions.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(annotated, "png", output.toFile());
        assertThat(output).exists();
    }

    /**
     * The fixtures are the pages that must go through exactly as before: the detector leaves them
     * alone (a mild skew mixed with upright text is not a rotated page), and the regions that stand
     * upright read as horizontal while the two skewed ones do not.
     */
    @Test
    void theSyntheticFixturesAreNotRotatedPagesAndTheirGeometryReadsAsBefore() throws Exception {
        ReplayFixture korean = load("korean-package.json");
        ReplayFixture vietnamese = load("vietnamese-poster.json");

        PageOrientation skewed = PageOrientationDetector.detect(korean.regions());
        PageOrientation upright = PageOrientationDetector.detect(vietnamese.regions());

        // 14 and 10 degree skews mixed with upright labels: no quarter holds 80 percent of the text.
        assertThat(skewed.verdict()).isEqualTo(PageOrientation.Verdict.MIXED);
        assertThat(skewed.retryAdvised()).isFalse();
        assertThat(upright.verdict()).isEqualTo(PageOrientation.Verdict.UPRIGHT);
        for (ReplayFixture fixture : List.of(korean, vietnamese)) {
            for (OcrRegion region : fixture.regions()) {
                boolean turned = region.id().equals("ko-product") || region.id().equals("en-brand")
                        || region.id().equals("decoration");
                assertThat(region.writingMode())
                        .as("%s %s", fixture.fixtureId(), region.id())
                        .isEqualTo(turned ? WritingMode.SKEWED : WritingMode.HORIZONTAL);
            }
        }
        // And the decisions the fixtures pin are untouched by any of it.
        assertDecision(korean, "ko-product", OcrQualification.TRANSLATE);
        assertDecision(korean, "date", OcrQualification.PRESERVE);
        assertDecision(korean, "decoration", OcrQualification.REJECT);
    }

    /**
     * A landscape page photographed a quarter turn off: every paragraph reads down the image. The
     * detector calls it a rotated page from the geometry and the text alone, the exact quarter-turn
     * map brings every polygon upright while the ids (hashes of the provider polygons in real runs)
     * stay as they are, and nothing is split into false columns afterwards.
     */
    @Test
    void rotatedLandscapePageIsBroughtUprightWithItsIdsAndDecisionsPinned() throws Exception {
        ReplayFixture fixture = load("rotated-page.json");
        JsonNode expected = fixture.manifest().path("expected");
        JsonNode input = fixture.manifest().path("input");
        assertThat(fixture.manifest().path("license").asText()).isEqualTo("synthetic-test-data");
        assertThat(fixture.regions()).extracting(OcrRegion::id)
                .containsExactly("p1", "p2", "p3", "p4", "p5", "p6");
        assertThat(fixture.regions()).allSatisfy(region ->
                assertThat(region.writingMode()).isEqualTo(WritingMode.ROTATED_90));

        PageOrientation orientation = PageOrientationDetector.detect(fixture.regions());
        assertThat(orientation.verdict()).isEqualTo(PageOrientation.Verdict.valueOf(expected.path("verdict").asText()));
        assertThat(orientation.quarterTurns()).isEqualTo(expected.path("quarterTurns").asInt());
        assertThat(orientation.retryAdvised()).isFalse();

        List<OcrRegion> upright = QuarterTurns.toUpright(fixture.regions(), orientation.quarterTurns(),
                input.path("width").asInt(), input.path("height").asInt());
        assertThat(upright).extracting(OcrRegion::id).containsExactlyElementsOf(
                fixture.regions().stream().map(OcrRegion::id).toList());
        for (OcrRegion region : upright) {
            assertThat(region.writingMode()).as(region.id()).isEqualTo(WritingMode.HORIZONTAL);
            assertThat(region.polygon()).as(region.id())
                    .isEqualTo(polygon(expected.path("uprightPolygons").path(region.id())));
        }
        // Upright, a line of this page stays one line: nothing is cut into columns.
        assertThat(new OcrRegionSegmenter().segment(upright)).extracting(OcrRegion::id)
                .containsExactlyElementsOf(upright.stream().map(OcrRegion::id).toList());
        BufferedImage page = new BufferedImage(expected.path("uprightWidth").asInt(),
                expected.path("uprightHeight").asInt(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = page.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, page.getWidth(), page.getHeight());
        graphics.dispose();
        assertThat(new OverlayContentClassifier().classify(page, upright)).isEqualTo(OverlayContentMode.DOCUMENT);

        for (OcrRegion region : fixture.regions()) {
            assertThat(qualification.decide(region, .6f).qualification()).as(region.id())
                    .isEqualTo(OcrQualification.TRANSLATE);
        }
        assertStructuredResponse(fixture.regions(), fixture.manifest());
        writeDiagnostic(fixture.fixtureId() + "-upright", upright, page.getWidth(), page.getHeight());
    }

    /**
     * A vertical menu as Vision reads it: one axis-aligned paragraph of single-glyph words, a dish
     * name and its price in each of four columns. Each column is cut into the dish and the price,
     * in the right-to-left reading order, as vertical writing; the dishes are translated and the
     * prices kept, and the layout budget is measured along the column.
     */
    @Test
    void verticalMenuIsCutIntoItsItemsInReadingOrderAsVerticalWriting() throws Exception {
        ReplayFixture fixture = load("vertical-menu.json");
        JsonNode expected = fixture.manifest().path("expected");
        assertThat(fixture.manifest().path("license").asText()).isEqualTo("synthetic-test-data");
        assertThat(PageOrientationDetector.detect(fixture.regions()).verdict())
                .as("one reliable paragraph is too little to judge a page by")
                .isEqualTo(PageOrientation.Verdict.INSUFFICIENT);

        List<OcrRegion> segmented = new OcrRegionSegmenter().segment(fixture.regions());

        assertThat(segmented).extracting(OcrRegion::id)
                .containsExactlyElementsOf(toStrings(expected.path("segmentedRegionIds")));
        // The pinned flows, none for a menu: its columns are items, and the prices end them.
        assertThat(segmented.stream().filter(region -> !region.flowCells().isEmpty()).map(OcrRegion::id).toList())
                .isEqualTo(toStrings(expected.path("flowRegionIds")));
        assertThat(segmented).allSatisfy(region -> {
            assertThat(region.writingMode()).isEqualTo(WritingMode.valueOf(expected.path("writingMode").asText()));
            assertThat(region.flowCells()).isEmpty();
        });
        assertThat(segmented.stream().filter(region -> region.id().endsWith(".1")))
                .extracting(OcrRegion::text)
                .containsExactly("南洋咖哩雞", "蔥燒台灣鯛", "鐵板豬排飯", "香酥雞腿飯");
        for (OcrRegion region : segmented) {
            OcrQualification decision = qualification.decide(region, .6f).qualification();
            // A stacked price has no letters and is not one token, so the policy rejects it: the
            // price stays as it is in the image, which is what is wanted.
            assertThat(decision).as(region.id() + " " + region.text()).isEqualTo(
                    toStrings(expected.path("translatedRegionIds")).contains(region.id())
                            ? OcrQualification.TRANSLATE : OcrQualification.REJECT);
            assertThat(toStrings(expected.path("rejectedRegionIds")).contains(region.id()))
                    .isEqualTo(decision == OcrQualification.REJECT);
        }
        assertStructuredResponse(segmented, fixture.manifest());

        OcrRegion dish = segmented.get(0);
        var layout = ImageTranslationPipeline.layout(dish);
        assertThat(layout.maxLines()).isEqualTo(1);
        assertThat(layout.width()).as("a line as long as the column").isGreaterThan(layout.height() * 4);
        writeDiagnostic(fixture.fixtureId(), segmented, 400, 360);
    }

    @Test
    void verticalMenuTranslationIsSetAsColumnsWhereTheDishesWere() throws Exception {
        ReplayFixture fixture = load("vertical-menu.json");
        List<OcrRegion> segmented = new OcrRegionSegmenter().segment(fixture.regions());
        List<ImageRegionOverlay> overlays = new ArrayList<>();
        JsonNode response = fixture.manifest().path("structuredTranslationResponse").path("regions");
        for (JsonNode translation : response) {
            OcrRegion region = segmented.stream()
                    .filter(value -> value.id().equals(translation.path("regionId").asText())).findFirst().orElseThrow();
            overlays.add(new ImageRegionOverlay(region, translation.path("translatedText").asText()));
        }
        BufferedImage page = VerticalFixtures.whiteImage(400, 360);

        VerticalFixtures.Rendered rendered = VerticalFixtures.render(
                page, VerticalFixtures.cjkFonts(), overlays.toArray(ImageRegionOverlay[]::new));
        VerticalFixtures.diagnostic(fixture.fixtureId() + "-rendered", rendered.output());

        assertThat(rendered.result().renderedBlockCount()).isEqualTo(4);
        for (ImageRegionOverlay overlay : overlays) {
            java.awt.Rectangle cell = OverlaySafetyPolicy.polygon(overlay.region().polygon()).getBounds();
            int[] ink = VerticalFixtures.inkBounds(rendered.output(), cell.x, cell.y, cell.width, cell.height);
            assertThat(ink[3]).as("%s runs down its column (%sx%s)", overlay.region().id(), ink[2], ink[3])
                    .isGreaterThan(ink[2] * 3);
        }
    }

    private void assertStructuredResponse(ReplayFixture fixture) throws Exception {
        assertStructuredResponse(fixture.regions(), fixture.manifest());
    }

    private void assertStructuredResponse(List<OcrRegion> regions, JsonNode manifest) throws Exception {
        List<ImageRegionTranslationInput> inputs = regions.stream()
                .map(region -> qualification.decide(region, .6f))
                .filter(decision -> decision.qualification() == OcrQualification.TRANSLATE)
                .map(decision -> new ImageRegionTranslationInput(decision.region().id(),
                        decision.region().text(), decision.region().languages().stream()
                                .findFirst().map(OcrDetectedLanguage::code).orElse("und"),
                        decision.protectedTokens()))
                .toList();
        String response = mapper.writeValueAsString(manifest.path("structuredTranslationResponse"));
        assertThat(new StructuredImageTranslationCodec(mapper).decode(response, inputs))
                .extracting(com.linetranslate.bot.service.translation.ImageRegionTranslation::regionId)
                .containsExactlyInAnyOrderElementsOf(inputs.stream().map(ImageRegionTranslationInput::regionId).toList());
        JsonNode expected = manifest.path("expected");
        assertThat(inputs).extracting(ImageRegionTranslationInput::regionId)
                .containsExactlyInAnyOrderElementsOf(toStrings(expected.path("translatedRegionIds")));
    }

    private static List<String> toStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private void assertDecision(ReplayFixture fixture, String id, OcrQualification expected) {
        OcrRegion region = fixture.regions().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
        assertThat(qualification.decide(region, .6f).qualification()).isEqualTo(expected);
    }

    private ReplayFixture load(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/image-translation-replay/v1/" + name)) {
            JsonNode root = mapper.readTree(input);
            List<OcrRegion> regions = new ArrayList<>();
            int order = 0;
            for (JsonNode node : root.path("regions")) {
                List<OcrPoint> points = new ArrayList<>();
                node.path("polygon").forEach(point -> points.add(new OcrPoint(point.get(0).asInt(), point.get(1).asInt())));
                String language = node.path("language").asText("und");
                // A region may list its words as [text, x, y, width, height].
                List<OcrWord> words = new ArrayList<>();
                node.path("words").forEach(word -> {
                    int x = word.get(1).asInt();
                    int y = word.get(2).asInt();
                    int width = word.get(3).asInt();
                    int height = word.get(4).asInt();
                    words.add(new OcrWord(word.get(0).asText(), List.of(new OcrPoint(x, y),
                            new OcrPoint(x + width, y), new OcrPoint(x + width, y + height),
                            new OcrPoint(x, y + height)), .98f, true));
                });
                regions.add(new OcrRegion(node.path("id").asText(), node.path("text").asText(), points,
                        words, (float) node.path("confidence").asDouble(), true, OcrBlockType.TEXT,
                        "und".equals(language) ? List.of() : List.of(new OcrDetectedLanguage(language, .98f)), order++));
            }
            return new ReplayFixture(root.path("schemaVersion").asText(), root.path("fixtureId").asText(), regions, root);
        }
    }

    private static List<OcrPoint> polygon(JsonNode points) {
        List<OcrPoint> result = new ArrayList<>();
        points.forEach(point -> result.add(new OcrPoint(point.get(0).asInt(), point.get(1).asInt())));
        return result;
    }

    private static void writeDiagnostic(ReplayFixture fixture) throws Exception {
        writeDiagnostic(fixture.fixtureId(), fixture.regions(),
                fixture.manifest().path("input").path("width").asInt(900),
                fixture.manifest().path("input").path("height").asInt(580));
    }

    private static void writeDiagnostic(String fixtureId, List<OcrRegion> regions, int width, int height)
            throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(248, 246, 222));
            graphics.fillRect(0, 0, width, height);
            for (OcrRegion region : regions) {
                graphics.setColor(Color.BLUE);
                graphics.draw(OverlaySafetyPolicy.polygon(region.polygon()));
                OcrPoint label = region.polygon().get(0);
                graphics.drawString(region.id(), label.x(), Math.max(10, label.y()));
            }
        } finally {
            graphics.dispose();
        }
        Path output = Path.of("target", "image-translation-replay", fixtureId + "-regions.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());

        BufferedImage mask = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D maskGraphics = mask.createGraphics();
        try {
            maskGraphics.setColor(Color.BLACK);
            maskGraphics.fillRect(0, 0, width, height);
            maskGraphics.setColor(Color.WHITE);
            regions.forEach(region -> maskGraphics.fill(OverlaySafetyPolicy.polygon(region.polygon())));
        } finally {
            maskGraphics.dispose();
        }
        ImageIO.write(mask, "png", output.resolveSibling(fixtureId + "-mask.png").toFile());

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(image, "png", encoded);
        List<ImageRegionOverlay> overlays = regions.stream()
                .filter(region -> region.text().codePoints().anyMatch(Character::isLetter))
                .map(region -> new ImageRegionOverlay(region, "translated-" + region.id())).toList();
        ImageTranslationProperties properties = new ImageTranslationProperties(
                10_485_760, 4096, 16_000_000, .6f, true, .25, .5);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(overlays, width, height, properties);
        if (plan.safe()) {
            RenderedImage rendered = new ImageTranslationOverlayRenderer().render(
                    new ValidatedImage(encoded.toByteArray(), "image/png", image), plan);
            Files.write(output.resolveSibling(fixtureId + "-rendered.png"), rendered.pngBytes());
        }
    }

    private record ReplayFixture(
            String schemaVersion,
            String fixtureId,
            List<OcrRegion> regions,
            JsonNode manifest) {
    }
}
