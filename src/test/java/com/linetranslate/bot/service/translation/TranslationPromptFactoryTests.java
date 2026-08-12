package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TranslationPromptFactoryTests {

    private final TranslationPromptFactory prompts = new TranslationPromptFactory();

    @Test
    void textPromptExpandsNamedLocalePlaceholdersWithoutLeakingTemplateTokens() {
        String prompt = prompts.text("zh-TW", TranslationStylePreset.FAITHFUL);

        assertThat(prompt)
                .contains("Text Translation Contract", "zh-TW", "Traditional Chinese", "Taiwan")
                .contains("Do not output Simplified Chinese")
                .contains(TranslationStylePreset.FAITHFUL.promptVersion())
                .doesNotContain("{{");
        assertThat(prompts.text("zh-CN", TranslationStylePreset.FAITHFUL))
                .contains("Simplified Chinese", "Do not output Traditional Chinese");
    }

    @Test
    void imagePromptIsIndependentAndUsesWholeImageContextWithStrictRegionIdentity() {
        String prompt = prompts.image("en", TranslationStylePreset.NATURAL);

        assertThat(prompt)
                .contains("Image Translation Contract", "English", "whole image")
                .contains("regionId", "layout", "compactLabel", "protectedTokens")
                .contains("shortest conventional label")
                .doesNotContain("independently", "{{");
        assertThat(prompt).isNotEqualTo(prompts.text("en", TranslationStylePreset.NATURAL));
    }

    @Test
    void ocrAndLanguageDetectionPromptsAreVersionedAndBoundTheirOutputs() {
        assertThat(prompts.ocr())
                .contains("OCR Extraction Contract", TranslationPromptFactory.OCR_PROMPT_VERSION)
                .contains("visible text", "reading order", "Do not describe");
        assertThat(prompts.languageDetection("hello"))
                .contains("Language Detection Contract", TranslationPromptFactory.DETECTION_PROMPT_VERSION)
                .contains("BCP 47", "hello")
                .doesNotContain("{{");
    }
}
