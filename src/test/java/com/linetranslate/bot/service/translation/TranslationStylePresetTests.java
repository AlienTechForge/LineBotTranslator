package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class TranslationStylePresetTests {

    @Test
    void catalogHasStableIdsLocalizedNamesAndVersionedRules() {
        assertThat(TranslationStylePreset.available())
                .extracting(TranslationStylePreset::id)
                .containsExactly("faithful", "natural", "casual", "formal", "business", "subtitle");
        assertThat(TranslationStylePreset.available())
                .allSatisfy(preset -> {
                    assertThat(preset.localizedName("zh-TW")).isNotBlank();
                    assertThat(preset.localizedName("en")).isNotBlank();
                    assertThat(preset.promptVersion()).matches("[a-z-]+-v[0-9]+");
                    assertThat(preset.promptRule()).isNotBlank();
                });
        assertThat(TranslationStylePreset.available().stream()
                .map(TranslationStylePreset::id)
                .collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(Set.of("faithful", "natural", "casual", "formal", "business", "subtitle"));
    }

    @Test
    void removedOrInvalidPresetFallsBackSafely() {
        assertThat(TranslationStylePreset.resolve("retired-style"))
                .isEqualTo(TranslationStylePreset.defaultPreset());
        assertThat(TranslationStylePreset.resolve(null))
                .isEqualTo(TranslationStylePreset.defaultPreset());
    }
}
