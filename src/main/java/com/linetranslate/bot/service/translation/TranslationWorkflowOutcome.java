package com.linetranslate.bot.service.translation;

import com.linetranslate.bot.service.ai.AiExecutionFailure;

public sealed interface TranslationWorkflowOutcome
        permits TranslationWorkflowOutcome.Success, TranslationWorkflowOutcome.Failure {

    record Success(TranslationWorkflowResult result) implements TranslationWorkflowOutcome {
    }

    record Failure(AiExecutionFailure failure) implements TranslationWorkflowOutcome {
    }
}
