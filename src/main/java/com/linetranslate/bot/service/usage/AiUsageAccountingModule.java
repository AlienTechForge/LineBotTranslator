package com.linetranslate.bot.service.usage;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.service.ai.AiExecutionFailure;
import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderAttempt;
import com.linetranslate.bot.service.ai.AiProviderOperation;
import com.linetranslate.bot.service.ai.AiTokenUsage;

/**
 * Deep Module for provider-attempt accounting. It owns event normalization,
 * pricing snapshots, indexes and database-side aggregation.
 */
@Service
public class AiUsageAccountingModule implements AiUsageEventSink {

    private static final String COLLECTION = "ai_usage_events";
    private static final String USD = "USD";

    private final MongoTemplate mongoTemplate;
    private final UsagePricingCatalog pricingCatalog;
    private final Clock clock;
    private final AtomicBoolean indexesReady = new AtomicBoolean();

    @Autowired
    public AiUsageAccountingModule(
            MongoTemplate mongoTemplate,
            UsagePricingCatalog pricingCatalog) {
        this(mongoTemplate, pricingCatalog, Clock.systemUTC());
    }

    public AiUsageAccountingModule(
            MongoTemplate mongoTemplate,
            UsagePricingCatalog pricingCatalog,
            Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.pricingCatalog = pricingCatalog;
        this.clock = clock;
    }

    @Override
    public void record(AiProviderOperation operation, AiExecutionOutcome outcome) {
        if (operation == null || outcome == null) {
            throw new IllegalArgumentException("Usage accounting requires operation and outcome");
        }
        ensureIndexes();
        Instant occurredAt = clock.instant();
        List<AiUsageEvent> events = events(operation, outcome, occurredAt);
        for (AiUsageEvent event : events) {
            mongoTemplate.insert(event);
        }
    }

    public UsageReport report(UsageQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("Usage query is required");
        }
        ensureIndexes();
        Criteria criteria = criteria(query);
        Document totals = aggregateTotals(criteria);
        if (totals == null) {
            return UsageReport.empty();
        }
        return new UsageReport(
                number(totals, "totalExecutions"),
                number(totals, "successfulExecutions"),
                number(totals, "failedExecutions"),
                number(totals, "textExecutions"),
                number(totals, "imageExecutions"),
                number(totals, "inputTokens"),
                number(totals, "outputTokens"),
                number(totals, "totalTokens"),
                number(totals, "totalLatencyMillis"),
                decimal(totals.get("totalCost")),
                USD,
                aggregateBreakdown(criteria, "provider"),
                aggregateBreakdown(criteria, "model"));
    }

    private List<AiUsageEvent> events(
            AiProviderOperation operation,
            AiExecutionOutcome outcome,
            Instant occurredAt) {
        if (outcome instanceof AiExecutionOutcome.Success success) {
            return successEvents(operation, success.result(), occurredAt);
        }
        return failureEvents(
                operation,
                ((AiExecutionOutcome.Failure) outcome).failure(),
                occurredAt);
    }

    private List<AiUsageEvent> successEvents(
            AiProviderOperation operation,
            AiExecutionResult result,
            Instant occurredAt) {
        List<AiProviderAttempt> attempts = result.attempts().isEmpty()
                ? List.of(AiProviderAttempt.success(
                        result.providerName(), result.modelName(), result.latencyMillis()))
                : result.attempts();
        List<AiUsageEvent> events = new ArrayList<>();
        for (int index = 0; index < attempts.size(); index++) {
            AiProviderAttempt attempt = attempts.get(index);
            boolean finalSuccess = attempt.status() == AiProviderAttempt.Status.SUCCESS
                    && same(attempt.provider(), result.providerName())
                    && same(attempt.model(), result.modelName());
            AiTokenUsage usage = finalSuccess ? result.tokenUsage() : AiTokenUsage.UNKNOWN;
            events.add(event(
                    operation,
                    attempt,
                    usage,
                    result.fallbackUsed(),
                    index + 1,
                    occurredAt));
        }
        return events;
    }

    private List<AiUsageEvent> failureEvents(
            AiProviderOperation operation,
            AiExecutionFailure failure,
            Instant occurredAt) {
        List<AiProviderAttempt> attempts = failure.attempts().isEmpty()
                ? List.of(new AiProviderAttempt(
                        dimension(failure.provider()),
                        dimension(failure.model()),
                        AiProviderAttempt.Status.FAILURE,
                        failure.outcome(),
                        failure.reason(),
                        null,
                        0))
                : failure.attempts();
        List<AiUsageEvent> events = new ArrayList<>();
        for (int index = 0; index < attempts.size(); index++) {
            events.add(event(
                    operation,
                    attempts.get(index),
                    AiTokenUsage.UNKNOWN,
                    attempts.size() > 1,
                    index + 1,
                    occurredAt));
        }
        return events;
    }

    private AiUsageEvent event(
            AiProviderOperation operation,
            AiProviderAttempt attempt,
            AiTokenUsage usage,
            boolean fallbackUsed,
            int attemptNumber,
            Instant occurredAt) {
        UsageContentKind contentKind = operation == AiProviderOperation.PROCESS_IMAGE
                ? UsageContentKind.IMAGE
                : UsageContentKind.TEXT;
        UsageExecutionStatus status = attempt.status() == AiProviderAttempt.Status.SUCCESS
                ? UsageExecutionStatus.SUCCESS
                : UsageExecutionStatus.FAILURE;
        boolean known = usage.inputTokens() >= 0
                && usage.outputTokens() >= 0
                && usage.totalTokens() >= 0;
        int imageCount = contentKind == UsageContentKind.IMAGE ? 1 : 0;
        UsagePriceQuote quote = pricingCatalog.quote(
                attempt.provider(), attempt.model(), occurredAt, usage, imageCount);
        return AiUsageEvent.builder()
                .occurredAt(occurredAt)
                .operation(operation.name())
                .contentKind(contentKind)
                .provider(dimension(attempt.provider()).toLowerCase(Locale.ROOT))
                .model(dimension(attempt.model()))
                .status(status)
                .outcome(attempt.outcome() == null ? null : attempt.outcome().name())
                .latencyMillis(attempt.latencyMillis())
                .inputTokens(known ? usage.inputTokens() : 0)
                .outputTokens(known ? usage.outputTokens() : 0)
                .totalTokens(known ? usage.totalTokens() : 0)
                .tokenUsageKnown(known)
                .imageCount(imageCount)
                .fallbackUsed(fallbackUsed)
                .attemptNumber(attemptNumber)
                .successCount(status == UsageExecutionStatus.SUCCESS ? 1 : 0)
                .failureCount(status == UsageExecutionStatus.FAILURE ? 1 : 0)
                .textCount(contentKind == UsageContentKind.TEXT ? 1 : 0)
                .imageExecutionCount(contentKind == UsageContentKind.IMAGE ? 1 : 0)
                .pricingVersion(quote.pricingVersion())
                .currency(quote.currency())
                .estimatedCost(quote.cost())
                .build();
    }

    private Document aggregateTotals(Criteria criteria) {
        List<AggregationOperation> operations = operations(criteria);
        operations.add(Aggregation.group()
                .count().as("totalExecutions")
                .sum("successCount").as("successfulExecutions")
                .sum("failureCount").as("failedExecutions")
                .sum("textCount").as("textExecutions")
                .sum("imageExecutionCount").as("imageExecutions")
                .sum("inputTokens").as("inputTokens")
                .sum("outputTokens").as("outputTokens")
                .sum("totalTokens").as("totalTokens")
                .sum("latencyMillis").as("totalLatencyMillis")
                .sum("estimatedCost").as("totalCost"));
        AggregationResults<Document> results = mongoTemplate.aggregate(
                Aggregation.newAggregation(operations), COLLECTION, Document.class);
        return results.getUniqueMappedResult();
    }

    private List<UsageBreakdown> aggregateBreakdown(Criteria criteria, String field) {
        List<AggregationOperation> operations = operations(criteria);
        operations.add(Aggregation.group(field)
                .count().as("executions")
                .sum("estimatedCost").as("cost"));
        operations.add(Aggregation.sort(Sort.Direction.DESC, "executions"));
        return mongoTemplate.aggregate(
                        Aggregation.newAggregation(operations), COLLECTION, Document.class)
                .getMappedResults().stream()
                .map(document -> new UsageBreakdown(
                        String.valueOf(document.get("_id")),
                        number(document, "executions"),
                        decimal(document.get("cost"))))
                .toList();
    }

    private static List<AggregationOperation> operations(Criteria criteria) {
        List<AggregationOperation> operations = new ArrayList<>();
        if (criteria != null) {
            operations.add(Aggregation.match(criteria));
        }
        return operations;
    }

    private static Criteria criteria(UsageQuery query) {
        List<Criteria> filters = new ArrayList<>();
        if (query.fromInclusive() != null) {
            filters.add(Criteria.where("occurredAt").gte(query.fromInclusive()));
        }
        if (query.toExclusive() != null) {
            filters.add(Criteria.where("occurredAt").lt(query.toExclusive()));
        }
        if (query.provider() != null) {
            filters.add(Criteria.where("provider").is(query.provider()));
        }
        if (query.model() != null) {
            filters.add(Criteria.where("model").is(query.model()));
        }
        if (query.contentKind() != null) {
            filters.add(Criteria.where("contentKind").is(query.contentKind()));
        }
        return filters.isEmpty()
                ? null
                : new Criteria().andOperator(filters.toArray(Criteria[]::new));
    }

    private void ensureIndexes() {
        if (indexesReady.get()) {
            return;
        }
        mongoTemplate.indexOps(AiUsageEvent.class).createIndex(
                new Index().named("usage_occurred_at").on("occurredAt", Sort.Direction.DESC));
        mongoTemplate.indexOps(AiUsageEvent.class).createIndex(
                new Index().named("usage_provider_time")
                        .on("provider", Sort.Direction.ASC)
                        .on("occurredAt", Sort.Direction.DESC));
        mongoTemplate.indexOps(AiUsageEvent.class).createIndex(
                new Index().named("usage_model_time")
                        .on("model", Sort.Direction.ASC)
                        .on("occurredAt", Sort.Direction.DESC));
        mongoTemplate.indexOps(AiUsageEvent.class).createIndex(
                new Index().named("usage_kind_time")
                        .on("contentKind", Sort.Direction.ASC)
                        .on("occurredAt", Sort.Direction.DESC));
        indexesReady.set(true);
    }

    private static long number(Document document, String key) {
        Object value = document.get(key);
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof org.bson.types.Decimal128 decimal) {
            return decimal.bigDecimalValue();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return BigDecimal.ZERO.setScale(8);
    }

    private static String dimension(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        String safe = value.trim().replaceAll("[\\p{Cntrl}]", "_");
        return safe.length() <= 128 ? safe : safe.substring(0, 128);
    }

    private static boolean same(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }
}
