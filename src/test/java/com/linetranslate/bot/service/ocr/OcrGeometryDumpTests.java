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
 * <pre>
 * ./mvnw -Dtest=OcrGeometryDumpTests test \
 *     -Docr.dump.image=Test_img/receipt.jpg \
 *     -Docr.dump.credentials=../linebot-translator/linebot.json
 * </pre>
 */
@SuppressWarnings("unchecked")
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

        List<OcrRegion> regions;
        try (ImageAnnotatorClient client = ImageAnnotatorClient.create(settings)) {
            ObjectProvider<ImageAnnotatorClient> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(client);
            GoogleVisionOcrService service = new GoogleVisionOcrService(provider);
            try (InputStream stream = Files.newInputStream(Path.of(imagePath))) {
                regions = service.recognizeRegions(stream);
            }
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("image", Path.of(imagePath).getFileName().toString());
        root.put("regionCount", regions.size());
        ArrayNode values = root.putArray("regions");
        for (OcrRegion region : regions) {
            ObjectNode value = values.addObject();
            value.put("id", region.id());
            value.put("text", region.text());
            value.put("groupId", region.groupId());
            value.put("compactLabel", region.compactLabel());
            value.put("confidence", region.confidence());
            value.put("orientation", region.orientation().name());
            value.set("polygon", points(region.polygon()));
            ArrayNode words = value.putArray("words");
            for (OcrWord word : region.words()) {
                ObjectNode entry = words.addObject();
                entry.put("text", word.text());
                entry.set("polygon", points(word.polygon()));
            }
        }

        Path output = Path.of("target", "ocr-dump",
                Path.of(imagePath).getFileName() + ".json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        assertThat(output).exists();
        assertThat(regions).isNotEmpty();

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
