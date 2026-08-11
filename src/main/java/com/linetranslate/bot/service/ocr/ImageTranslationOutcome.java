package com.linetranslate.bot.service.ocr;

import java.util.Optional;

import com.linetranslate.bot.service.ai.AiExecutionFailure;

public sealed interface ImageTranslationOutcome
        permits ImageTranslationOutcome.Success, ImageTranslationOutcome.Failure {

    record Success(ImageTranslationPipelineResult result) implements ImageTranslationOutcome {

        public Success {
            if (result == null) {
                throw new IllegalArgumentException("Successful image translation requires a result");
            }
        }
    }

    record Failure(
            ImageTranslationFailureStage stage,
            Optional<AiExecutionFailure> executionFailure) implements ImageTranslationOutcome {

        public Failure(ImageTranslationFailureStage stage) {
            this(stage, Optional.empty());
        }

        public Failure(ImageTranslationFailureStage stage, AiExecutionFailure executionFailure) {
            this(stage, Optional.ofNullable(executionFailure));
        }

        public Failure {
            if (stage == null) {
                throw new IllegalArgumentException("Failed image translation requires a stage");
            }
            executionFailure = executionFailure == null ? Optional.empty() : executionFailure;
        }
    }
}
