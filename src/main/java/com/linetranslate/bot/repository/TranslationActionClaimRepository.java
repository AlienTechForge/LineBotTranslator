package com.linetranslate.bot.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.linetranslate.bot.model.TranslationActionClaim;

@Repository
public interface TranslationActionClaimRepository
        extends MongoRepository<TranslationActionClaim, String> {
}
