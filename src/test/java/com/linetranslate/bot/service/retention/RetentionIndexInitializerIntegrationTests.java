package com.linetranslate.bot.service.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.linetranslate.bot.model.TranslationActionClaim;
import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.service.AdminStatsIndexInitializer;
import com.linetranslate.bot.service.usage.AiUsageEvent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RetentionIndexInitializerIntegrationTests {

    private static final String STARTUP = "startup";
    private static final String RECORDS = "translation_records";
    private static final String CLAIMS = "translation_action_claims";
    private static final String USAGE = "ai_usage_events";
    private static final String PROFILES = "user_profiles";
    private static final String RECORD_INDEX = "translation_record_created_at";
    private static final String CLAIM_INDEX = "translation_action_claim_ttl";
    private static final String USAGE_INDEX = "ai_usage_event_ttl";

    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private AdminStatsIndexInitializer adminStatsIndexInitializer;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("mongodb.database", () -> "linebot_retention_test");
    }

    @BeforeEach
    void dropCollections(TestInfo testInfo) {
        if (!testInfo.getTags().contains(STARTUP)) {
            dropAll();
        }
    }

    @AfterEach
    void dropCollectionsAgain() {
        dropAll();
    }

    // Keeps what ApplicationReadyEvent created while the context started, so it must be
    // the first test of the class; the bound properties are the defaults of
    // application.properties.
    @Test
    @Order(1)
    @Tag(STARTUP)
    void applicationStartupCreatesTheIndexesWithTheDefaultRetention() {
        assertThat(ttl(RECORDS, RECORD_INDEX)).contains(Duration.ofDays(90));
        assertThat(ttl(CLAIMS, CLAIM_INDEX)).contains(Duration.ofDays(7));
        assertThat(ttl(USAGE, USAGE_INDEX)).contains(Duration.ofDays(730));
    }

    @Test
    void createsEveryIndexWithTheConfiguredSecondsOnTheTimestampField() {
        initializer(properties("PT24H", "PT2H", "P30D")).ensureRetentionIndexes();

        IndexInfo records = index(RECORDS, RECORD_INDEX);
        IndexInfo claims = index(CLAIMS, CLAIM_INDEX);
        IndexInfo usage = index(USAGE, USAGE_INDEX);
        assertThat(records.getExpireAfter()).contains(Duration.ofSeconds(86_400));
        assertThat(claims.getExpireAfter()).contains(Duration.ofSeconds(7_200));
        assertThat(usage.getExpireAfter()).contains(Duration.ofSeconds(2_592_000));
        assertThat(key(records)).isEqualTo(new Document("createdAt", -1));
        assertThat(key(claims)).isEqualTo(new Document("createdAt", 1));
        assertThat(key(usage)).isEqualTo(new Document("occurredAt", 1));
        assertThat(indexNames(RECORDS)).containsExactlyInAnyOrder("_id_", RECORD_INDEX);
        assertThat(indexNames(CLAIMS)).containsExactlyInAnyOrder("_id_", CLAIM_INDEX);
        assertThat(indexNames(USAGE)).containsExactlyInAnyOrder("_id_", USAGE_INDEX);
    }

    @Test
    void runningAgainChangesNothing() {
        RetentionIndexInitializer initializer = initializer(properties("PT24H", "PT2H", "P30D"));
        initializer.ensureRetentionIndexes();
        List<Document> first = specs();

        initializer.ensureRetentionIndexes();
        initializer.ensureRetentionIndexes();

        assertThat(specs()).isEqualTo(first);
    }

    @Test
    void convertsAPlainIndexOnTheSameKeyInPlace() {
        // What AdminStatsIndexInitializer creates when it runs before this initializer.
        mongoTemplate.indexOps(TranslationRecord.class).createIndex(
                new Index().named(RECORD_INDEX).on("createdAt", Sort.Direction.DESC));
        assertThat(ttl(RECORDS, RECORD_INDEX)).isEmpty();

        initializer(properties("P90D", "P7D", "P730D")).ensureRetentionIndexes();

        assertThat(ttl(RECORDS, RECORD_INDEX)).contains(Duration.ofDays(90));
        assertThat(indexNames(RECORDS)).containsExactlyInAnyOrder("_id_", RECORD_INDEX);
    }

    @Test
    void theStatisticsInitializerDoesNotFightTheTtlIndexInEitherOrder() {
        adminStatsIndexInitializer.ensureIndexes();
        initializer(properties("P90D", "P7D", "P730D")).ensureRetentionIndexes();
        assertThat(ttl(RECORDS, RECORD_INDEX)).contains(Duration.ofDays(90));

        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger(AdminStatsIndexInitializer.class);
        logs.start();
        logger.addAppender(logs);
        try {
            adminStatsIndexInitializer.ensureIndexes();
            initializer(properties("P90D", "P7D", "P730D")).ensureRetentionIndexes();
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(logs.list).extracting(ILoggingEvent::getLevel).doesNotContain(Level.WARN);
        assertThat(ttl(RECORDS, RECORD_INDEX)).contains(Duration.ofDays(90));
        assertThat(indexNames(RECORDS)).containsExactlyInAnyOrder("_id_", RECORD_INDEX);
    }

    @Test
    void updatesAnIndexWhoseTtlDiffers() {
        initializer(properties("PT24H", "P7D", "P730D")).ensureRetentionIndexes();
        assertThat(ttl(RECORDS, RECORD_INDEX)).contains(Duration.ofHours(24));

        initializer(properties("PT48H", "P7D", "P730D")).ensureRetentionIndexes();

        assertThat(ttl(RECORDS, RECORD_INDEX)).contains(Duration.ofHours(48));
        assertThat(indexNames(RECORDS)).containsExactlyInAnyOrder("_id_", RECORD_INDEX);
    }

    @Test
    void aDisabledCollectionGetsNoIndexWhileTheOthersDo() {
        initializer(properties("", "PT0S", "P730D")).ensureRetentionIndexes();

        assertThat(indexNames(RECORDS)).doesNotContain(RECORD_INDEX);
        assertThat(indexNames(CLAIMS)).doesNotContain(CLAIM_INDEX);
        assertThat(ttl(USAGE, USAGE_INDEX)).contains(Duration.ofDays(730));
    }

    @Test
    void disablingLeavesAnExistingTtlIndexAlone() {
        initializer(properties("P90D", "P7D", "P730D")).ensureRetentionIndexes();

        initializer(properties("", "", "")).ensureRetentionIndexes();

        assertThat(ttl(RECORDS, RECORD_INDEX)).contains(Duration.ofDays(90));
        assertThat(ttl(CLAIMS, CLAIM_INDEX)).contains(Duration.ofDays(7));
        assertThat(ttl(USAGE, USAGE_INDEX)).contains(Duration.ofDays(730));
    }

    @Test
    void anInvalidValueIsNotFatalAndLeavesNoIndexBehind() {
        assertThatCode(() -> initializer(properties("90 days", "P7D", "P730D"))
                .ensureRetentionIndexes()).doesNotThrowAnyException();

        assertThat(indexNames(RECORDS)).doesNotContain(RECORD_INDEX);
        assertThat(ttl(CLAIMS, CLAIM_INDEX)).contains(Duration.ofDays(7));
    }

    @Test
    void theUsageModulesDescendingIndexCanStillBeCreatedBesideTheTtlIndex() {
        initializer(properties("P90D", "P7D", "P730D")).ensureRetentionIndexes();

        // AiUsageAccountingModule creates this one lazily on its first event.
        assertThatCode(() -> mongoTemplate.indexOps(AiUsageEvent.class).createIndex(
                new Index().named("usage_occurred_at").on("occurredAt", Sort.Direction.DESC)))
                .doesNotThrowAnyException();
        assertThat(indexNames(USAGE)).containsExactlyInAnyOrder(
                "_id_", USAGE_INDEX, "usage_occurred_at");
        assertThat(ttl(USAGE, "usage_occurred_at")).isEmpty();
    }

    @Test
    void theIndexedTimestampsAreStoredAsBsonDates() {
        // MongoDB only expires documents whose indexed field is a BSON date.
        mongoTemplate.save(TranslationRecord.builder()
                .userId("U-retention").createdAt(LocalDateTime.now()).build());
        mongoTemplate.save(TranslationActionClaim.builder()
                .id("claim-retention").createdAt(LocalDateTime.now()).build());
        mongoTemplate.save(AiUsageEvent.builder().occurredAt(Instant.now()).build());

        assertThat(rawField(RECORDS, "createdAt")).isInstanceOf(Date.class);
        assertThat(rawField(CLAIMS, "createdAt")).isInstanceOf(Date.class);
        assertThat(rawField(USAGE, "occurredAt")).isInstanceOf(Date.class);
    }

    private RetentionIndexInitializer initializer(RetentionProperties properties) {
        return new RetentionIndexInitializer(mongoTemplate, properties);
    }

    private static RetentionProperties properties(String records, String claims, String usage) {
        RetentionProperties properties = new RetentionProperties();
        properties.setTranslationRecords(records);
        properties.setTranslationActionClaims(claims);
        properties.setAiUsageEvents(usage);
        return properties;
    }

    private void dropAll() {
        for (String collection : List.of(RECORDS, CLAIMS, USAGE, PROFILES)) {
            if (mongoTemplate.collectionExists(collection)) {
                mongoTemplate.dropCollection(collection);
            }
        }
    }

    private IndexInfo index(String collection, String name) {
        return mongoTemplate.indexOps(collection).getIndexInfo().stream()
                .filter(info -> name.equals(info.getName()))
                .findFirst()
                .orElse(null);
    }

    private Optional<Duration> ttl(String collection, String name) {
        IndexInfo info = index(collection, name);
        assertThat(info).as("index %s on %s", name, collection).isNotNull();
        return info.getExpireAfter();
    }

    private List<String> indexNames(String collection) {
        if (!mongoTemplate.collectionExists(collection)) {
            return List.of();
        }
        return mongoTemplate.indexOps(collection).getIndexInfo().stream()
                .map(IndexInfo::getName)
                .toList();
    }

    private Document key(IndexInfo info) {
        Document key = new Document();
        info.getIndexFields().forEach(field -> key.append(
                field.getKey(), field.getDirection() == Sort.Direction.ASC ? 1 : -1));
        return key;
    }

    /** Raw index specifications of the three collections, ordered by name. */
    private List<Document> specs() {
        return List.of(RECORDS, CLAIMS, USAGE).stream()
                .flatMap(collection -> mongoTemplate.getCollection(collection)
                        .listIndexes(Document.class).into(new ArrayList<>()).stream())
                .sorted(Comparator.comparing(spec -> spec.getString("name")))
                .toList();
    }

    /** The collection holds a single document: every test starts from dropped collections. */
    private Object rawField(String collection, String field) {
        Document document = mongoTemplate.getCollection(collection).find().first();
        assertThat(document).isNotNull();
        return document.get(field);
    }
}
