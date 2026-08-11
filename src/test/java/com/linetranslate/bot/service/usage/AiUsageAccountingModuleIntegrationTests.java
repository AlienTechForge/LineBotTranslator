package com.linetranslate.bot.service.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.linetranslate.bot.service.ai.AiExecutionFailure;
import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderAttempt;
import com.linetranslate.bot.service.ai.AiProviderException;
import com.linetranslate.bot.service.ai.AiProviderOperation;
import com.linetranslate.bot.service.ai.AiTokenUsage;
import com.linetranslate.bot.service.ai.AiModelCatalog;
import com.linetranslate.bot.service.ai.AiModelDescriptor;

@SpringBootTest
@ActiveProfiles("test")
class AiUsageAccountingModuleIntegrationTests {

    private static final String COLLECTION = "ai_usage_events";
    private static final Instant NOW = Instant.parse("2026-08-11T04:00:00Z");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Autowired
    private MongoTemplate mongoTemplate;

    private AiUsageAccountingModule module;

    @BeforeEach
    void setUp() {
        if (mongoTemplate.collectionExists(COLLECTION)) {
            mongoTemplate.dropCollection(COLLECTION);
        }
        AiModelCatalog catalog = org.mockito.Mockito.mock(AiModelCatalog.class);
        org.mockito.Mockito.when(catalog.find(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> Optional.of(new AiModelDescriptor(
                        invocation.getArgument(0), invocation.getArgument(0),
                        Set.of("text", "image"), Set.of("text"),
                        new java.math.BigDecimal("0.000003"),
                        new java.math.BigDecimal("0.000015"))));
        module = new AiUsageAccountingModule(
                mongoTemplate, new UsagePricingCatalog(catalog), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void reconcilesAttemptsActualMetadataPricingAndEveryQueryDimension() {
        module.record(AiProviderOperation.TRANSLATE_TEXT, new AiExecutionOutcome.Success(
                new AiExecutionResult(
                        "translated",
                        "openrouter",
                        "anthropic/claude-sonnet-4",
                        new AiTokenUsage(1_000, 500, 1_500),
                        100,
                        false,
                        List.of(AiProviderAttempt.success(
                                "openrouter", "anthropic/claude-sonnet-4", 100)))));
        module.record(AiProviderOperation.PROCESS_IMAGE, new AiExecutionOutcome.Success(
                new AiExecutionResult(
                        "recognized",
                        "openrouter",
                        "openai/gpt-4o-mini",
                        new AiTokenUsage(1_000, 500, 1_500),
                        75,
                        false,
                        List.of(AiProviderAttempt.success("openrouter", "openai/gpt-4o-mini", 75)))));
        AiProviderException transport = failure(
                AiProviderException.Outcome.TRANSPORT_ERROR,
                "openrouter",
                "openai/gpt-4o-mini",
                "io");
        module.record(AiProviderOperation.GENERATE_TEXT, new AiExecutionOutcome.Failure(
                new AiExecutionFailure(
                        AiProviderException.Outcome.TRANSPORT_ERROR,
                        "openrouter",
                        "openai/gpt-4o-mini",
                        "io",
                        "correlation-secret-not-persisted",
                        -1,
                        List.of(AiProviderAttempt.failure(transport, 50)))));

        UsageReport all = module.report(UsageQuery.all());

        assertThat(all.totalExecutions()).isEqualTo(3);
        assertThat(all.successfulExecutions()).isEqualTo(2);
        assertThat(all.failedExecutions()).isEqualTo(1);
        assertThat(all.textExecutions()).isEqualTo(2);
        assertThat(all.imageExecutions()).isEqualTo(1);
        assertThat(all.inputTokens()).isEqualTo(2_000);
        assertThat(all.outputTokens()).isEqualTo(1_000);
        assertThat(all.totalTokens()).isEqualTo(3_000);
        assertThat(all.totalLatencyMillis()).isEqualTo(225);
        assertThat(all.totalCost()).isEqualByComparingTo("0.02100000");
        assertThat(all.currency()).isEqualTo("USD");
        assertThat(all.byProvider()).extracting(UsageBreakdown::key, UsageBreakdown::executions)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("openrouter", 3L));

        assertThat(module.report(UsageQuery.forDay(LocalDate.of(2026, 8, 11), TAIPEI))
                .totalExecutions()).isEqualTo(3);
        assertThat(module.report(UsageQuery.forMonth(YearMonth.of(2026, 8), TAIPEI))
                .totalExecutions()).isEqualTo(3);
        assertThat(module.report(UsageQuery.all().withProvider("openrouter"))
                .totalExecutions()).isEqualTo(3);
        assertThat(module.report(UsageQuery.all().withModel("openai/gpt-4o-mini"))
                .totalExecutions()).isEqualTo(2);
        assertThat(module.report(UsageQuery.all().withContentKind(UsageContentKind.IMAGE))
                .totalExecutions()).isEqualTo(1);
        assertThat(module.report(UsageQuery.all().withContentKind(UsageContentKind.TEXT))
                .totalExecutions()).isEqualTo(2);

        List<Document> stored = mongoTemplate.getCollection(COLLECTION).find().into(new java.util.ArrayList<>());
        assertThat(stored).hasSize(3).allSatisfy(event -> {
            assertThat(event).containsKeys(
                    "occurredAt", "operation", "contentKind", "provider", "model",
                    "status", "latencyMillis", "pricingVersion", "currency", "estimatedCost");
            assertThat(event.keySet()).doesNotContain(
                    "sourceText", "translatedText", "userId", "correlationId", "apiKey", "token");
        });
    }

    private static AiProviderException failure(
            AiProviderException.Outcome outcome,
            String provider,
            String model,
            String reason) {
        return new AiProviderException(
                outcome, provider, model, reason, "correlation-ignored", -1, null);
    }
}
