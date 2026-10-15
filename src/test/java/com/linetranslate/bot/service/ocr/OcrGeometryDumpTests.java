package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.ImageAnnotatorSettings;

/**
 * Opt-in tool that runs the real OCR against a local image and writes the resulting geometry to
 * disk. Layout defects are geometry problems, and without the actual region and word boxes every
 * fix is guesswork against production logs. Skipped unless both system properties are supplied, so
 * CI never calls the OCR API.
 *
 * <p>It is also the Step 0 tool for page orientation: the dump records what the provider reports
 * for a page that is turned a quarter or written vertically, which decides whether the symbol frames
 * can be trusted ({@code PageOrientationDetector.SYMBOL_FRAME_FOLLOWS_GLYPH}) and confirms the
 * thresholds. Per paragraph it writes the writing mode its frame implies; per symbol the text, its
 * length, the polygon and the angle of the edge from vertex 0 to vertex 1; and the size of the page
 * with what the detector measured on it, from the provider's own paragraphs before any page action.
 * Run it on the two production images, on a synthetic vertical-Japanese page, and on the same text set
 * horizontally and turned 90 and 270 degrees. Everything it writes is a local developer artefact (it
 * holds the page's text): never commit an image or a dump.
 *
 * <pre>
 * ./mvnw -Dtest=OcrGeometryDumpTests test \
 *     -Docr.dump.image=Test_img/receipt.jpg \
 *     -Docr.dump.credentials=../linebot-translator/linebot.json
 * </pre>
 */
@SuppressWarnings({"unchecked", "deprecation"})
class OcrGeometryDumpTests {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void dumpsExternalImageGeometryForLocalLayoutDebugging() throws Exception {
        String imagePath = System.getProperty("ocr.dump.image", "").strip();
        String credentialPath = System.getProperty("ocr.dump.credentials", "").strip();
        Assumptions.assumeTrue(!imagePath.isBlank() && !credentialPath.isBlank(),
                "external OCR dump is opt-in");

        // REST rather than gRPC: gRPC negotiates TLS through Netty and ignores javax.net.ssl, so a
        // machine behind a TLS-intercepting proxy cannot reach the API at all. HTTP/JSON uses the
        // JDK stack and honours -Djavax.net.ssl.trustStoreType=Windows-ROOT.
        ImageAnnotatorSettings settings = ImageAnnotatorSettings.newHttpJsonBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(
                        GoogleCredentials.fromStream(Files.newInputStream(Path.of(credentialPath)))))
                .build();

        GoogleVisionOcrService.ProviderInspection inspection;
        try (ImageAnnotatorClient client = ImageAnnotatorClient.create(settings)) {
            ObjectProvider<ImageAnnotatorClient> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(client);
            GoogleVisionOcrService service = new GoogleVisionOcrService(provider);
            try (InputStream stream = Files.newInputStream(Path.of(imagePath))) {
                inspection = service.inspect(stream);
            }
        }
        // The segmented regions of the image as submitted, which is what the dump always held.
        List<OcrRegion> regions = new OcrRegionSegmenter().segment(inspection.page().regions());

        ObjectNode root = mapper.createObjectNode();
        root.put("image", Path.of(imagePath).getFileName().toString());
        root.put("regionCount", regions.size());
        root.put("providerParagraphCount", inspection.page().regions().size());
        ObjectNode page = root.putObject("page");
        page.put("width", inspection.page().width());
        page.put("height", inspection.page().height());
        page.put("pageCount", inspection.page().pageCount());
        root.set("orientation", orientation(inspection.orientation()));
        ArrayNode values = root.putArray("regions");
        for (OcrRegion region : regions) {
            ObjectNode value = values.addObject();
            value.put("id", region.id());
            value.put("text", region.text());
            value.put("groupId", region.groupId());
            value.put("compactLabel", region.compactLabel());
            value.put("confidence", region.confidence());
            value.put("writingMode", region.writingMode().name());
            value.put("edgeAngle", region.rotationDegrees());
            value.set("polygon", points(region.polygon()));
            ArrayNode words = value.putArray("words");
            for (OcrWord word : region.words()) {
                ObjectNode entry = words.addObject();
                entry.put("text", word.text());
                entry.set("polygon", points(word.polygon()));
                ArrayNode symbols = entry.putArray("symbols");
                for (OcrSymbol symbol : word.symbols()) {
                    ObjectNode glyph = symbols.addObject();
                    glyph.put("textLength", symbol.text().length());
                    glyph.put("text", symbol.text());
                    glyph.put("edgeAngle", edgeAngle(symbol.polygon()));
                    glyph.set("polygon", points(symbol.polygon()));
                }
            }
        }

        Path output = Path.of("target", "ocr-dump",
                Path.of(imagePath).getFileName() + ".json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        assertThat(output).exists();
        assertThat(regions).isNotEmpty();

    }

    /** What the detector measured, with the constants that decide how it is acted on. */
    private ObjectNode orientation(PageOrientation orientation) {
        ObjectNode node = mapper.createObjectNode();
        node.put("verdict", orientation.verdict().name());
        node.put("quarterTurns", orientation.quarterTurns());
        node.put("regions", orientation.regions());
        node.put("reliableRegions", orientation.reliableRegions());
        node.put("dominantShare", orientation.dominantShare());
        node.put("skewedShare", orientation.skewedShare());
        node.put("reliableShare", orientation.reliableShare());
        node.put("cjkShare", orientation.cjkShare());
        node.put("symbolSamples", orientation.symbolSamples());
        node.put("uprightGlyphShare", orientation.uprightGlyphShare());
        node.put("rotatedGlyphShare", orientation.rotatedGlyphShare());
        node.put("punctuationSamples", orientation.punctuationSamples());
        node.put("punctuationOffset", orientation.punctuationOffset());
        node.put("retryAdvised", orientation.retryAdvised());
        node.put("symbolFrameFollowsGlyph", PageOrientationDetector.SYMBOL_FRAME_FOLLOWS_GLYPH);
        return node;
    }

    /** Degrees clockwise from +x of the edge from vertex 0 to vertex 1; 0 when there is no edge. */
    private static double edgeAngle(List<OcrPoint> polygon) {
        if (polygon.size() < 2) return 0;
        return Math.toDegrees(Math.atan2(
                polygon.get(1).y() - polygon.get(0).y(), polygon.get(1).x() - polygon.get(0).x()));
    }

    private ArrayNode points(List<OcrPoint> polygon) {
        ArrayNode array = mapper.createArrayNode();
        polygon.forEach(point -> {
            ObjectNode node = array.addObject();
            node.put("x", point.x());
            node.put("y", point.y());
        });
        return array;
    }
}
