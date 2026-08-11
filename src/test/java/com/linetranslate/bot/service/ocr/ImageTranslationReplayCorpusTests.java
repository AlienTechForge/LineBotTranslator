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

    private void assertStructuredResponse(ReplayFixture fixture) throws Exception {
        List<ImageRegionTranslationInput> inputs = fixture.regions().stream()
                .map(region -> qualification.decide(region, .6f))
                .filter(decision -> decision.qualification() == OcrQualification.TRANSLATE)
                .map(decision -> new ImageRegionTranslationInput(decision.region().id(),
                        decision.region().text(), decision.region().languages().stream()
                                .findFirst().map(OcrDetectedLanguage::code).orElse("und"),
                        decision.protectedTokens()))
                .toList();
        String response = mapper.writeValueAsString(fixture.manifest().path("structuredTranslationResponse"));
        assertThat(new StructuredImageTranslationCodec(mapper).decode(response, inputs))
                .extracting(com.linetranslate.bot.service.translation.ImageRegionTranslation::regionId)
                .containsExactlyInAnyOrderElementsOf(inputs.stream().map(ImageRegionTranslationInput::regionId).toList());
        JsonNode expected = fixture.manifest().path("expected");
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
                regions.add(new OcrRegion(node.path("id").asText(), node.path("text").asText(), points,
                        List.of(), (float) node.path("confidence").asDouble(), true, OcrBlockType.TEXT,
                        "und".equals(language) ? List.of() : List.of(new OcrDetectedLanguage(language, .98f)), order++));
            }
            return new ReplayFixture(root.path("schemaVersion").asText(), root.path("fixtureId").asText(), regions, root);
        }
    }

    private static void writeDiagnostic(ReplayFixture fixture) throws Exception {
        int width = fixture.fixtureId().contains("korean") ? 900 : 680;
        int height = fixture.fixtureId().contains("korean") ? 580 : 240;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(248, 246, 222));
            graphics.fillRect(0, 0, width, height);
            for (OcrRegion region : fixture.regions()) {
                graphics.setColor(Color.BLUE);
                graphics.draw(OverlaySafetyPolicy.polygon(region.polygon()));
                OcrPoint label = region.polygon().get(0);
                graphics.drawString(region.id(), label.x(), Math.max(10, label.y()));
            }
        } finally {
            graphics.dispose();
        }
        Path output = Path.of("target", "image-translation-replay", fixture.fixtureId() + "-regions.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());

        BufferedImage mask = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D maskGraphics = mask.createGraphics();
        try {
            maskGraphics.setColor(Color.BLACK);
            maskGraphics.fillRect(0, 0, width, height);
            maskGraphics.setColor(Color.WHITE);
            fixture.regions().forEach(region -> maskGraphics.fill(OverlaySafetyPolicy.polygon(region.polygon())));
        } finally {
            maskGraphics.dispose();
        }
        ImageIO.write(mask, "png", output.resolveSibling(fixture.fixtureId() + "-mask.png").toFile());

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(image, "png", encoded);
        List<ImageRegionOverlay> overlays = fixture.regions().stream()
                .filter(region -> region.text().codePoints().anyMatch(Character::isLetter))
                .map(region -> new ImageRegionOverlay(region, "translated-" + region.id())).toList();
        ImageTranslationProperties properties = new ImageTranslationProperties(
                10_485_760, 4096, 16_000_000, .6f, true, .25, .5);
        OverlaySafetyPlan plan = new OverlaySafetyPolicy().evaluate(overlays, width, height, properties);
        if (plan.safe()) {
            RenderedImage rendered = new ImageTranslationOverlayRenderer().render(
                    new ValidatedImage(encoded.toByteArray(), "image/png", image), plan);
            Files.write(output.resolveSibling(fixture.fixtureId() + "-rendered.png"), rendered.pngBytes());
        }
    }

    private record ReplayFixture(
            String schemaVersion,
            String fixtureId,
            List<OcrRegion> regions,
            JsonNode manifest) {
    }
}
