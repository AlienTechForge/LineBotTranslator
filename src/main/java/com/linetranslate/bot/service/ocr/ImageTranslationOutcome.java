package com.linetranslate.bot.service.ocr;

public sealed interface ImageTranslationOutcome
        permits ImageTranslationOutcome.Success, ImageTranslationOutcome.Failure {

    record Success(ImageTranslationPipelineResult result) implements ImageTranslationOutcome {

        public Success {
            if (result == null) {
                throw new IllegalArgumentException("Successful image translation requires a result");
            }
        }
    }

    record Failure(ImageTranslationFailureStage stage) implements ImageTranslationOutcome {

        public Failure {
            if (stage == null) {
                throw new IllegalArgumentException("Failed image translation requires a stage");
            }
        }
    }
}
