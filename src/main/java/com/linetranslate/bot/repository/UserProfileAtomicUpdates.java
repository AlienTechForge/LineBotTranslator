package com.linetranslate.bot.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import com.linetranslate.bot.model.UserProfile;

/**
 * Field-level writes of {@code user_profiles}. Webhook events of one user run on different
 * worker threads, so a read-modify-write of the whole document lets the later
 * {@code save} overwrite fields the earlier writer changed. Every method here is one atomic
 * MongoDB update that touches only its own fields, never reads the document first, and does
 * not depend on any field a legacy document might lack. Whole-profile {@code insert} stays
 * with {@code UserPreferencesModule#profile}.
 */
public interface UserProfileAtomicUpdates {

    /** Maximum entries kept in {@code recentLanguages} and {@code recentTranslations}. */
    int RECENT_LIMIT = 5;

    /** Returns the profile after the update, or empty when no profile has this user ID. */
    Optional<UserProfile> setPreferredLanguage(String userId, String language);

    Optional<UserProfile> setPreferredChineseTargetLanguage(String userId, String language);

    Optional<UserProfile> setPreferredModel(String userId, String model);

    Optional<UserProfile> setPreferredTranslationStyle(String userId, String presetId);

    /** Returns false when no profile has this user ID. */
    boolean setDisplayName(String userId, String displayName);

    /**
     * Stores the LINE profile metadata. A null picture URL keeps the stored one; a null display
     * name or status message removes the stored value.
     */
    boolean setLineProfile(String userId, String displayName, String pictureUrl, String statusMessage);

    boolean setLastInteractionAt(String userId, LocalDateTime at);

    /**
     * Records one successful translation in a single update: last interaction time, total and
     * text/image counters, the newest-first {@link #RECENT_LIMIT} translated texts and, when
     * {@code targetLanguage} is not null, the newest-first de-duplicated recent languages.
     */
    boolean recordTranslation(
            String userId,
            LocalDateTime at,
            String translatedText,
            String targetLanguage,
            boolean imageTranslation);
}
