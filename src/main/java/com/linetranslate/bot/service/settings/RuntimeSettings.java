package com.linetranslate.bot.service.settings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.linetranslate.bot.logging.SafeLog;

/**
 * Immutable effective runtime settings. No credential fields are permitted here.
 *
 * <p>{@code adminUserIds} is the administrator list persisted by administrators; it is
 * empty while nothing has been persisted, and consumers then fall back to the deployment
 * bootstrap list. LINE user IDs are identifiers rather than credentials, but they are
 * personal identifiers: log them only through {@code SafeLog.user(...)}. {@link #toString()}
 * therefore reports only their count and a fingerprint of the last operator.
 */
public record RuntimeSettings(
        String defaultTargetLanguageForChinese,
        String defaultTargetLanguageForOthers,
        String openRouterDefaultModel,
        boolean ocrEnabled,
        boolean shortUrlEnabled,
        boolean imageTextReplyEnabled,
        List<String> adminUserIds,
        int schemaVersion,
        long revision,
        Instant updatedAt,
        String updatedBy,
        Source source) {

    public RuntimeSettings {
        adminUserIds = normalizeAdminUserIds(adminUserIds);
    }

    /** Compatibility constructor for callers that predate the persisted administrator list. */
    public RuntimeSettings(
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
        this(defaultTargetLanguageForChinese, defaultTargetLanguageForOthers,
                openRouterDefaultModel, ocrEnabled, shortUrlEnabled, imageTextReplyEnabled,
                List.of(), schemaVersion, revision, updatedAt, updatedBy, source);
    }

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

    /**
     * Trims entries, drops blank ones, removes duplicates while keeping the first
     * occurrence, and returns an unmodifiable list.
     */
    public static List<String> normalizeAdminUserIds(Collection<String> userIds) {
        if (userIds == null) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String userId : userIds) {
            if (userId != null && !userId.isBlank()) {
                unique.add(userId.trim());
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }

    @Override
    public String toString() {
        return "RuntimeSettings[defaultTargetLanguageForChinese=" + defaultTargetLanguageForChinese
                + ", defaultTargetLanguageForOthers=" + defaultTargetLanguageForOthers
                + ", openRouterDefaultModel=" + openRouterDefaultModel
                + ", ocrEnabled=" + ocrEnabled
                + ", shortUrlEnabled=" + shortUrlEnabled
                + ", imageTextReplyEnabled=" + imageTextReplyEnabled
                + ", adminUserCount=" + adminUserIds.size()
                + ", schemaVersion=" + schemaVersion
                + ", revision=" + revision
                + ", updatedAt=" + updatedAt
                + ", updatedBy=" + (updatedBy == null ? null : SafeLog.user(updatedBy))
                + ", source=" + source + "]";
    }

    public enum Source {
        PERSISTED,
        DEPLOYMENT_DEFAULTS
    }
}
