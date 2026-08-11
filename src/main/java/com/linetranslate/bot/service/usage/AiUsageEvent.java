package com.linetranslate.bot.service.usage;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document("ai_usage_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiUsageEvent {

    @Id
    private String id;
    private Instant occurredAt;
    private String operation;
    private UsageContentKind contentKind;
    private String provider;
    private String model;
    private UsageExecutionStatus status;
    private String outcome;
    private long latencyMillis;
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    private boolean tokenUsageKnown;
    private int imageCount;
    private boolean fallbackUsed;
    private int attemptNumber;
    private int successCount;
    private int failureCount;
    private int textCount;
    private int imageExecutionCount;
    private String pricingVersion;
    private String currency;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal estimatedCost;
}
