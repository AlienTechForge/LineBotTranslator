package com.linetranslate.bot.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.test.context.ActiveProfiles;

import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;

@SpringBootTest
@ActiveProfiles("test")
class AdminStatsIntegrationTests {

    private static final String RECORDS = "translation_records";
    private static final String PROFILES = "user_profiles";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private AdminService adminService;

    @Autowired
    private AdminStatsIndexInitializer indexInitializer;

    @Autowired
    private TranslationRecordRepository translationRecordRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void clearCollections() {
        for (String collection : List.of(RECORDS, PROFILES)) {
            if (mongoTemplate.collectionExists(collection)) {
                mongoTemplate.dropCollection(collection);
            }
        }
        indexInitializer.ensureIndexes();
    }

    @Test
    void indexesAreNamedIdempotentAndServeTheStatisticsFilters() {
        indexInitializer.ensureIndexes();

        assertThat(indexNames(TranslationRecord.class))
                .contains(AdminStatsIndexInitializer.RECORD_CREATED_AT_INDEX_NAME);
        assertThat(indexNames(UserProfile.class))
                .contains(AdminStatsIndexInitializer.PROFILE_LAST_INTERACTION_INDEX_NAME);

        Date now = new Date();
        Document recordPlan = mongoTemplate.getCollection(RECORDS)
                .find(new Document("createdAt", new Document("$gt", new Date(0)).append("$lt", now)))
                .explain();
        assertThat(recordPlan.toJson()).contains(AdminStatsIndexInitializer.RECORD_CREATED_AT_INDEX_NAME);
        Document profilePlan = mongoTemplate.getCollection(PROFILES)
                .find(new Document("lastInteractionAt", new Document("$gt", new Date(0))))
                .explain();
        assertThat(profilePlan.toJson())
                .contains(AdminStatsIndexInitializer.PROFILE_LAST_INTERACTION_INDEX_NAME);
    }

    @Test
    void systemStatsCountInTheDatabaseAndCutDaysInThePersistenceZone() throws Exception {
        TimeZone original = TimeZone.getDefault();
        // Records at 01:00 Taipei time fall on the previous UTC day, so grouping by the
        // wrong zone would move them to another row of the per-day table.
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Taipei")));
        try {
            LocalDateTime now = nowAwayFromMidnight();
            LocalDate today = now.toLocalDate();
            saveProfile("U-active-000001", now.minusHours(1), now.minusDays(30));
            saveProfile("U-stale-000002", now.minusHours(25), now.minusDays(30));
            saveProfile("U-never-000003", null, now.minusMinutes(30));

            LocalDateTime earlyTwoDaysAgo = today.minusDays(2).atTime(1, 0);
            saveRecord(now.minusSeconds(10), false, "openrouter", 1_000);
            saveRecord(today.minusDays(1).atTime(23, 30), false, "openrouter", 1_000);
            saveRecord(earlyTwoDaysAgo, true, "openrouter", 1_000);
            saveRecord(earlyTwoDaysAgo.plusMinutes(5), false, "gemini", 1_000);
            saveRecord(now.minusDays(7).plusHours(1), false, "openrouter", 1_000);
            saveRecord(now.minusDays(8), false, "openrouter", 1_000);

            Map<String, Long> expectedDays = new TreeMap<>();
            for (LocalDateTime inWindow : List.of(
                    now.minusSeconds(10),
                    today.minusDays(1).atTime(23, 30),
                    earlyTwoDaysAgo,
                    earlyTwoDaysAgo.plusMinutes(5),
                    now.minusDays(7).plusHours(1))) {
                expectedDays.merge(inWindow.format(DAY), 1L, Long::sum);
            }
            assertThat(expectedDays).containsEntry(earlyTwoDaysAgo.format(DAY), 2L);

            StringBuilder expected = new StringBuilder()
                    .append("系統統計\n")
                    .append("-------------------\n")
                    .append("總用戶數: 3\n")
                    .append("總翻譯次數: 6\n")
                    .append("  文字翻譯: 5\n")
                    .append("  圖片翻譯: 1\n")
                    .append("過去24小時活躍用戶: 1\n")
                    .append("\nAI 提供商使用情況\n")
                    .append("-------------------\n")
                    .append("OpenRouter: 5 (").append(String.format("%.1f%%", 5.0 / 6 * 100)).append(")\n")
                    .append("\n過去7天翻譯量\n")
                    .append("-------------------\n");
            expectedDays.forEach((day, count) -> expected.append(day).append(": ").append(count).append("\n"));

            assertThat(adminService.getSystemStats()).isEqualTo(expected.toString());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void todayStatsAggregateOnlyTodaysRecords() throws Exception {
        LocalDateTime now = nowAwayFromMidnight();
        saveRecord(now.minusSeconds(10), false, "openrouter", 1_000);
        saveRecord(now.minusSeconds(20), false, "openrouter", 2_000);
        saveRecord(now.minusSeconds(30), true, "gemini", 3_000);
        // A legacy document without provider, image flag or processing time: counted as
        // a text translation of 0 ms and left out of the provider breakdown.
        mongoTemplate.getCollection(RECORDS).insertOne(
                new Document("createdAt", date(now.minusSeconds(40))));
        saveRecord(now.toLocalDate().minusDays(1).atTime(12, 0), true, "openrouter", 9_000);

        Map<String, Long> providers = new HashMap<>();
        providers.put("openrouter", 2L);
        providers.put("gemini", 1L);
        StringBuilder expected = new StringBuilder()
                .append("【今日統計】\n\n")
                .append("今日總翻譯次數：4\n")
                .append("文字翻譯：3 次\n")
                .append("圖片翻譯：1 次\n")
                .append("平均處理時間：").append(String.format("%.2f", 1_500.0 / 1000)).append(" 秒\n\n")
                .append("【AI 提供者使用情況】\n");
        providers.forEach((provider, count) ->
                expected.append(provider).append("：").append(count).append(" 次\n"));

        assertThat(adminService.getTodayStats()).isEqualTo(expected.toString());
    }

    @Test
    void todayStatsWithoutRecordsRenderZeros() {
        assertThat(adminService.getTodayStats()).isEqualTo(
                "【今日統計】\n\n"
                        + "今日總翻譯次數：0\n"
                        + "文字翻譯：0 次\n"
                        + "圖片翻譯：0 次\n"
                        + "平均處理時間：" + String.format("%.2f", 0.0) + " 秒\n\n"
                        + "【AI 提供者使用情況】\n");
    }

    @Test
    void recentUsersOrderProfilesWithoutLastInteractionByFirstInteraction() {
        LocalDateTime now = LocalDateTime.now();
        saveProfile("U-legacy-new-01", null, now.minusMinutes(30));
        saveProfile("U-hour-ago-0002", now.minusHours(1), now.minusDays(30));
        saveProfile("U-day-ago-00003", now.minusHours(25), now.minusDays(30));
        saveProfile("U-2days-ago-004", now.minusDays(2), now.minusDays(30));
        saveProfile("U-5days-ago-005", now.minusDays(5), now.minusDays(30));
        saveProfile("U-legacy-old-06", null, now.minusDays(10));

        assertThat(adminService.getRecentUsers(3))
                .extracting(user -> user.get("userId"))
                .containsExactly("U-legacy-new-01", "U-hour-ago-0002", "U-day-ago-00003");

        List<Map<String, Object>> all = adminService.getRecentUsers(10);
        assertThat(all)
                .extracting(user -> user.get("userId"))
                .containsExactly(
                        "U-legacy-new-01",
                        "U-hour-ago-0002",
                        "U-day-ago-00003",
                        "U-2days-ago-004",
                        "U-5days-ago-005",
                        "U-legacy-old-06");
        assertThat(all.get(0))
                .containsEntry("displayName", "U-legacy-new-01-name")
                .containsEntry("lastActiveTime", TIME.format(now.minusMinutes(30)))
                .containsEntry("totalTranslations", 0);
    }

    private void saveProfile(String userId, LocalDateTime lastInteractionAt, LocalDateTime firstInteractionAt) {
        userProfileRepository.save(UserProfile.builder()
                .userId(userId)
                .displayName(userId + "-name")
                .firstInteractionAt(firstInteractionAt)
                .lastInteractionAt(lastInteractionAt)
                .build());
    }

    private void saveRecord(LocalDateTime createdAt, boolean image, String provider, double processingTimeMs) {
        translationRecordRepository.save(TranslationRecord.builder()
                .userId("U-record-owner")
                .sourceText("source")
                .translatedText("translated")
                .aiProvider(provider)
                .createdAt(createdAt)
                .isImageTranslation(image)
                .processingTimeMs(processingTimeMs)
                .build());
    }

    private List<String> indexNames(Class<?> documentType) {
        return mongoTemplate.indexOps(documentType).getIndexInfo().stream()
                .map(IndexInfo::getName)
                .toList();
    }

    private static Date date(LocalDateTime value) {
        return Date.from(value.atZone(ZoneId.systemDefault()).toInstant());
    }

    /** Keeps seeded "today" records and the service's own clock on the same calendar day. */
    private static LocalDateTime nowAwayFromMidnight() throws InterruptedException {
        LocalTime time = LocalTime.now();
        if (time.isAfter(LocalTime.of(23, 59))) {
            Thread.sleep(Duration.between(time, LocalTime.MAX).toMillis() + 61_000);
        } else if (time.isBefore(LocalTime.of(0, 1))) {
            Thread.sleep(Duration.between(time, LocalTime.of(0, 1)).toMillis() + 1_000);
        }
        return LocalDateTime.now();
    }
}
