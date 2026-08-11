package com.linetranslate.bot.service.usage;

import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiProviderOperation;

/** Fail-open Seam between provider execution and durable accounting. */
@FunctionalInterface
public interface AiUsageEventSink {

    void record(AiProviderOperation operation, AiExecutionOutcome outcome);
}
