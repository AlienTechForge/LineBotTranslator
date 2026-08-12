package com.linetranslate.bot.service.translation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Component
public class StructuredImageTranslationCodec {
    public static final String SCHEMA_VERSION = "image-regions-v3";
    private static final int MAX_REGION_TEXT = 4_000;
    private final ObjectMapper mapper;

    public StructuredImageTranslationCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(List<ImageRegionTranslationInput> regions, String targetLanguage, boolean repair) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("targetLocale", targetLanguage);
        root.put("repair", repair);
        root.put("documentContext",
                "Use every supplied translatable region in reading order while preserving exact region identity.");
        ArrayNode values = root.putArray("regions");
        for (ImageRegionTranslationInput region : regions) {
            if (!region.translatable()) continue;
            ObjectNode value = values.addObject();
            value.put("regionId", region.regionId());
            value.put("sourceText", region.sourceText());
            value.put("sourceLanguage", region.sourceLanguage());
            value.put("action", "TRANSLATE");
            value.put("readingOrder", region.readingOrder());
            ObjectNode layout = value.putObject("layout");
            layout.put("groupId", region.layout().groupId());
            layout.put("x", region.layout().x());
            layout.put("y", region.layout().y());
            layout.put("width", region.layout().width());
            layout.put("height", region.layout().height());
            layout.put("maxLines", region.layout().maxLines());
            layout.put("maxCharacters", region.layout().maxCharacters());
            layout.put("compactLabel", region.layout().compactLabel());
            ArrayNode tokens = value.putArray("protectedTokens");
            region.protectedTokens().forEach(tokens::add);
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception failure) {
            throw new StructuredTranslationException("Structured request could not be encoded");
        }
    }

    public List<ImageRegionTranslation> decode(
            String response,
            List<ImageRegionTranslationInput> expected) {
        try {
            JsonNode root = mapper.readTree(stripFence(response));
            if (!root.isObject() || !hasOnlyFields(root, Set.of("schemaVersion", "regions"))
                    || !root.path("schemaVersion").isTextual()
                    || !SCHEMA_VERSION.equals(root.path("schemaVersion").asText())
                    || !root.path("regions").isArray()) {
                throw new StructuredTranslationException("Structured response schema is invalid");
            }
            Set<String> expectedIds = new HashSet<>();
            expected.stream().filter(ImageRegionTranslationInput::translatable)
                    .forEach(region -> expectedIds.add(region.regionId()));
            if (expected.stream().map(ImageRegionTranslationInput::regionId).distinct().count() != expected.size()) {
                throw new StructuredTranslationException("Structured request contains duplicate region IDs");
            }
            Set<String> seen = new HashSet<>();
            List<ImageRegionTranslation> result = new ArrayList<>();
            for (JsonNode node : root.path("regions")) {
                if (!node.isObject() || !hasOnlyFields(node, Set.of("regionId", "translatedText"))
                        || !node.path("regionId").isTextual()
                        || !node.path("translatedText").isTextual()) {
                    throw new StructuredTranslationException("Structured response fields are invalid");
                }
                String id = node.path("regionId").asText("");
                String text = node.path("translatedText").asText("").strip();
                if (!expectedIds.contains(id) || !seen.add(id) || text.isBlank() || text.length() > MAX_REGION_TEXT) {
                    throw new StructuredTranslationException("Structured response region set is invalid");
                }
                ImageRegionTranslationInput source = expected.stream()
                        .filter(ImageRegionTranslationInput::translatable)
                        .filter(value -> value.regionId().equals(id)).findFirst().orElseThrow();
                if (text.codePointCount(0, text.length()) > source.layout().maxCharacters()) {
                    throw new StructuredTranslationException("Translated text exceeds its layout budget");
                }
                int cursor = 0;
                for (String token : source.protectedTokens()) {
                    int found = text.indexOf(token, cursor);
                    if (found < 0) throw new StructuredTranslationException("Protected token was changed");
                    cursor = found + token.length();
                }
                result.add(new ImageRegionTranslation(id, text));
            }
            if (!seen.equals(expectedIds)) {
                throw new StructuredTranslationException("Structured response is incomplete");
            }
            return List.copyOf(result);
        } catch (StructuredTranslationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new StructuredTranslationException("Structured response is malformed");
        }
    }

    private static String stripFence(String value) {
        if (value == null) return "";
        String stripped = value.strip();
        if (stripped.startsWith("```")) {
            stripped = stripped.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return stripped;
    }

    private static boolean hasOnlyFields(JsonNode node, Set<String> allowed) {
        java.util.Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) if (!allowed.contains(fields.next())) return false;
        return true;
    }
}
