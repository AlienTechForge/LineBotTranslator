package com.linetranslate.bot.service.settings;

import java.time.Instant;

/** Immutable effective runtime settings. No credential fields are permitted here. */
public record RuntimeSettings(
        String defaultTargetLanguageForChinese,
        String defaultTargetLanguageForOthers,
        String openRouterDefaultModel,
        boolean ocrEnabled,
        boolean shortUrlEnabled,
        boolean imageTextReplyEnabled,
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
                openRouterDefaultModel, ocrEnabled, false, true, schemaVersion, revision,
                updatedAt, updatedBy, source);
    }

    /** Compatibility constructor for callers that predate the image text reply switch. */
    public RuntimeSettings(
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
        this(defaultTargetLanguageForChinese, defaultTargetLanguageForOthers,
                openRouterDefaultModel, ocrEnabled, shortUrlEnabled, true, schemaVersion,
                revision, updatedAt, updatedBy, source);
    }

    public enum Source {
        PERSISTED,
        DEPLOYMENT_DEFAULTS
    }
}
