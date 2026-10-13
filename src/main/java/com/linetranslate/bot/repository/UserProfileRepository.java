package com.linetranslate.bot.repository;

import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.linetranslate.bot.model.UserProfile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserProfileRepository
        extends MongoRepository<UserProfile, String>, UserProfileAtomicUpdates {

    Optional<UserProfile> findByUserId(String userId);

    long countByLastInteractionAtAfter(LocalDateTime dateTime);

    List<UserProfile> findByLastInteractionAtNotNullOrderByLastInteractionAtDesc(Limit limit);

    /** Profiles without a completed translation; their last activity is firstInteractionAt. */
    List<UserProfile> findByLastInteractionAtIsNullOrderByFirstInteractionAtDesc(Limit limit);
}