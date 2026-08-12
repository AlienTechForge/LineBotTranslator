package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class StructuredImageTranslationCodecTests {
    private final StructuredImageTranslationCodec codec = new StructuredImageTranslationCodec(new ObjectMapper());
    private final List<ImageRegionTranslationInput> input = List.of(
            new ImageRegionTranslationInput("r-a", "Nhiệt độ 38°C", "vi", List.of("38°C")),
            new ImageRegionTranslationInput("r-b", "불닭볶음면", "ko", List.of()),
            new ImageRegionTranslationInput("date", "2021.05.28", "und",
                    List.of("2021.05.28"), false, 2));

    @Test
    void mapsOutOfOrderResponseByExactRegionId() {
        String response = """
                {"schemaVersion":"image-regions-v3","regions":[
                  {"regionId":"r-b","translatedText":"辣雞炒麵"},
                  {"regionId":"r-a","translatedText":"溫度 38°C"}
                ]}
                """;
        assertThat(codec.decode(response, input)).extracting(ImageRegionTranslation::regionId)
                .containsExactly("r-b", "r-a");
    }

    @Test
    void rejectsMissingDuplicateUnknownMalformedAndChangedTokens() {
        assertInvalid("{\"schemaVersion\":\"image-regions-v3\",\"regions\":[]}");
        assertInvalid("{\"schemaVersion\":\"image-regions-v3\",\"regions\":[{\"regionId\":\"r-a\",\"translatedText\":\"38°C\"},{\"regionId\":\"r-a\",\"translatedText\":\"38°C\"}]}");
        assertInvalid("{\"schemaVersion\":\"image-regions-v3\",\"regions\":[{\"regionId\":\"evil\",\"translatedText\":\"x\"}]}");
        assertInvalid("not-json");
        assertInvalid("{\"schemaVersion\":\"image-regions-v3\",\"unexpected\":true,\"regions\":[]}");
        assertInvalid("{\"schemaVersion\":\"image-regions-v3\",\"regions\":[{\"regionId\":1,\"translatedText\":\"x\"}]}");
        assertInvalid("{\"schemaVersion\":\"image-regions-v3\",\"regions\":[{\"regionId\":\"r-a\",\"translatedText\":\"溫度 39°C\"},{\"regionId\":\"r-b\",\"translatedText\":\"辣雞炒麵\"}]}");
    }

    @Test
    void requestCarriesWholeImageContextAndLayoutConstraints() throws Exception {
        ImageRegionTranslationInput compact = new ImageRegionTranslationInput(
                "day-1", "六", "zh", List.of(), true, 0,
                new ImageRegionLayout("weekdays", 10, 20, 22, 28, 1, 3, true));

        var root = new ObjectMapper().readTree(codec.encode(List.of(compact), "en", false));

        assertThat(root.path("targetLocale").asText()).isEqualTo("en");
        assertThat(root.path("documentContext").asText()).contains("reading order");
        assertThat(root.path("regions").get(0).path("layout").path("groupId").asText())
                .isEqualTo("weekdays");
        assertThat(root.path("regions").get(0).path("layout").path("compactLabel").asBoolean()).isTrue();
        assertThat(root.path("regions").get(0).path("layout").path("maxLines").asInt()).isEqualTo(1);
    }

    @Test
    void requestOmitsPreservedRegionsThatNeedNoProviderWork() throws Exception {
        var root = new ObjectMapper().readTree(codec.encode(input, "zh-TW", false));

        assertThat(root.path("regions")).hasSize(2);
        assertThat(root.path("regions").findValuesAsText("regionId"))
                .containsExactly("r-a", "r-b");
    }

    @Test
    void rejectsTranslationThatExceedsItsRegionCharacterBudget() {
        ImageRegionTranslationInput compact = new ImageRegionTranslationInput(
                "menu-label", "染髮", "zh", List.of(), true, 0,
                new ImageRegionLayout("menu", 10, 20, 80, 24, 1, 8, true));
        String response = """
                {"schemaVersion":"image-regions-v3","regions":[
                  {"regionId":"menu-label","translatedText":"Hair dye package"}
                ]}
                """;

        assertThatThrownBy(() -> codec.decode(response, List.of(compact)))
                .isInstanceOf(StructuredTranslationException.class);
    }

    private void assertInvalid(String response) {
        assertThatThrownBy(() -> codec.decode(response, input))
                .isInstanceOf(StructuredTranslationException.class);
    }
}
