package com.linetranslate.bot.service.settings;

import java.time.Instant;

/** Immutable effective runtime settings. No credential fields are permitted here. */
public record RuntimeSettings(
        String defaultTargetLanguageForChinese,
        String defaultTargetLanguageForOthers,
        String openRouterDefaultModel,
        boolean ocrEnabled,
        boolean shortUrlEnabled,
        int schemaVersion,
        long revision,
        Instant updatedAt,
        String updatedBy,
        Source source) {

    /** Compatibility constructor for existing focused tests and consumers. */
    public RuntimeSettings(
            String defaultTargetLanguageForChinese,
            String defaultTargetLanguageForOthers,
            String openRouterDefaultModel,
            boolean ocrEnabled,
            int schemaVersion,
            long revision,
            Instant updatedAt,
            String updatedBy,
            Source source) {
        this(defaultTargetLanguageForChinese, defaultTargetLanguageForOthers,
                openRouterDefaultModel, ocrEnabled, false, schemaVersion, revision,
                updatedAt, updatedBy, source);
    }

    public enum Source {
        PERSISTED,
        DEPLOYMENT_DEFAULTS
    }
}
