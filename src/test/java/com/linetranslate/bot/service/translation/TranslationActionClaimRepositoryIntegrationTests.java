package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.linetranslate.bot.model.TranslationActionClaim;
import com.linetranslate.bot.repository.TranslationActionClaimRepository;

@SpringBootTest
@ActiveProfiles("test")
class TranslationActionClaimRepositoryIntegrationTests {

    private static final String COLLECTION = "translation_action_claims";

    @Autowired
    private TranslationActionClaimRepository repository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void clearClaims() {
        if (mongoTemplate.collectionExists(COLLECTION)) {
            mongoTemplate.dropCollection(COLLECTION);
        }
    }

    @Test
    void deterministicIdRejectsDuplicateAndPersistsNoTranslationContent() {
        TranslationActionClaim claim = TranslationActionClaim.builder()
                .id("claim-1")
                .userId("U-user")
                .sourceRecordId("record-1")
                .targetLanguage("ja")
                .status(TranslationActionClaim.Status.PROCESSING)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        repository.insert(claim);

        assertThatThrownBy(() -> repository.insert(claim))
                .isInstanceOf(DuplicateKeyException.class);
        Document stored = mongoTemplate.getCollection(COLLECTION)
                .find(new Document("_id", "claim-1"))
                .first();
        assertThat(stored).isNotNull();
        assertThat(stored.keySet()).doesNotContain(
                "sourceText", "translatedText", "payload", "postbackData", "secret");
    }
}
