package com.linetranslate.bot.service.ai;

public sealed interface AiExecutionOutcome permits AiExecutionOutcome.Success, AiExecutionOutcome.Failure {

    record Success(AiExecutionResult result) implements AiExecutionOutcome {
    }

    record Failure(AiExecutionFailure failure) implements AiExecutionOutcome {
    }

    default AiExecutionResult resultOrThrow() {
        if (this instanceof Success success) {
            return success.result();
        }
        throw ((Failure) this).failure().toException();
    }
}
