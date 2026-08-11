package com.linetranslate.bot.service.preference;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.UserProfileRepository;

@SpringBootTest
@ActiveProfiles("test")
class UserPreferencesMongoCompatibilityTests {

    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private UserProfileRepository repository;
    @Autowired
    private UserPreferencesModule module;

    @BeforeEach
    void cleanProfiles() {
        mongoTemplate.dropCollection("user_profiles");
    }

    @Test
    void oldDocumentWithMissingPreferenceFieldsReceivesEffectiveDefaults() {
        mongoTemplate.getCollection("user_profiles").insertOne(new Document()
                .append("userId", "U-old-missing")
                .append("displayName", "Legacy User")
                .append("totalTranslations", 3));

        UserProfile legacy = repository.findByUserId("U-old-missing").orElseThrow();
        UserPreferences preferences = module.resolve(legacy);

        assertThat(preferences.targetLanguage()).isNotBlank();
        assertThat(preferences.chineseTargetLanguage()).isNotBlank();
        assertThat(preferences.model()).isNotBlank();
        assertThat(preferences.recentLanguages()).isEmpty();
    }

    @Test
    void oldDocumentWithInvalidValuesFallsBackButKeepsReadableMongoShape() {
        mongoTemplate.getCollection("user_profiles").insertOne(new Document()
                .append("userId", "U-old-invalid")
                .append("preferredLanguage", "xx-retired")
                .append("preferredChineseTargetLanguage", "yy-retired")
                .append("preferredAiProvider", "retired-ai")
                .append("openaiPreferredModel", "retired-model")
                .append("geminiPreferredModel", "retired-model")
                .append("recentLanguages", List.of("bad", "ja", "ja", "en")));

        UserProfile legacy = repository.findByUserId("U-old-invalid").orElseThrow();
        UserPreferences preferences = module.resolve(legacy);

        assertThat(preferences.targetLanguage()).isNotIn("xx-retired", "yy-retired");
        assertThat(preferences.model()).isNotEqualTo("retired-model");
        assertThat(preferences.recentLanguages()).containsExactly("ja", "en");
    }
}
