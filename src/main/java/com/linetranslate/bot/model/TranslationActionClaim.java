package com.linetranslate.bot.model;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Durable idempotency metadata for a translation-result action. */
@Document("translation_action_claims")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslationActionClaim {

    public enum Status {
        PROCESSING,
        COMPLETED,
        FAILED
    }

    @Id
    private String id;
    private String userId;
    private String sourceRecordId;
    private String targetLanguage;
    private String stylePresetId;
    private Status status;
    private String resultRecordId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
