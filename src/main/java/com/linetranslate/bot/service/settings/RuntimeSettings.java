package com.linetranslate.bot.service.settings;

import java.time.Instant;

/** Immutable effective runtime settings. No credential fields are permitted here. */
public record RuntimeSettings(
        String defaultTargetLanguageForChinese,
        String defaultTargetLanguageForOthers,
        String openRouterDefaultModel,
        boolean ocrEnabled,
        int schemaVersion,
        long revision,
        Instant updatedAt,
        String updatedBy,
        Source source) {

    public enum Source {
        PERSISTED,
        DEPLOYMENT_DEFAULTS
    }
}
