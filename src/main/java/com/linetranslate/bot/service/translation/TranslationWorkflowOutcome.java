package com.linetranslate.bot.service.translation;

import com.linetranslate.bot.service.ai.AiExecutionFailure;

public sealed interface TranslationWorkflowOutcome
        permits TranslationWorkflowOutcome.Success, TranslationWorkflowOutcome.Failure {

    /**
     * @param unexpectedFailure a provider bug ({@code UNEXPECTED_ERROR}) hit on the way to this
     *        success, such as a structured image attempt that the plain-text fallback then made
     *        up for, or {@code null}; the caller reports it so a degraded success does not hide it
     */
    record Success(TranslationWorkflowResult result, AiExecutionFailure unexpectedFailure)
            implements TranslationWorkflowOutcome {

        public Success(TranslationWorkflowResult result) {
            this(result, null);
        }
    }

    record Failure(AiExecutionFailure failure) implements TranslationWorkflowOutcome {
    }
}
