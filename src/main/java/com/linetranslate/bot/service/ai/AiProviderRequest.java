package com.linetranslate.bot.service.ai;

public record AiProviderRequest(
        AiProviderOperation operation,
        String model,
        String input,
        String targetLanguage,
        String imageData,
        String translationStyleId,
        String translationPromptVersion,
        String translationStyleInstruction) {

    public AiProviderRequest {
        if (operation == null) {
            throw new IllegalArgumentException("AI provider operation is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("AI provider model is required");
        }
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("AI provider input is required");
        }
        if (operation == AiProviderOperation.TRANSLATE_TEXT
                && (targetLanguage == null || targetLanguage.isBlank())) {
            throw new IllegalArgumentException("Target language is required for translation");
        }
        if (operation == AiProviderOperation.TRANSLATE_TEXT
                && (translationStyleId == null || translationStyleId.isBlank()
                        || translationPromptVersion == null || translationPromptVersion.isBlank()
                        || translationStyleInstruction == null || translationStyleInstruction.isBlank())) {
            throw new IllegalArgumentException("Versioned translation style is required for translation");
        }
        if (operation == AiProviderOperation.PROCESS_IMAGE
                && (imageData == null || imageData.isBlank())) {
            throw new IllegalArgumentException("Image data is required for image processing");
        }
    }

    public static AiProviderRequest translate(String model, String text, String targetLanguage) {
        return translate(model, text, targetLanguage,
                "faithful", "faithful-v1",
                "Preserve the source meaning, tone, details, and ambiguity. Do not add or omit information.");
    }

    public static AiProviderRequest translate(
            String model,
            String text,
            String targetLanguage,
            String styleId,
            String promptVersion,
            String styleInstruction) {
        return new AiProviderRequest(
                AiProviderOperation.TRANSLATE_TEXT,
                model,
                text,
                targetLanguage,
                null,
                styleId,
                promptVersion,
                styleInstruction);
    }

    public static AiProviderRequest image(String model, String prompt, String imageData) {
        return new AiProviderRequest(
                AiProviderOperation.PROCESS_IMAGE,
                model,
                prompt,
                null,
                imageData,
                null,
                null,
                null);
    }

    public static AiProviderRequest generate(String model, String prompt) {
        return new AiProviderRequest(
                AiProviderOperation.GENERATE_TEXT,
                model,
                prompt,
                null,
                null,
                null,
                null,
                null);
    }
}
