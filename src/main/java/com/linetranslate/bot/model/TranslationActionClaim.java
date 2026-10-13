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
    /**
     * Fencing token: incremented by every acquisition of this claim (the insert starts at 1,
     * each lease takeover adds one). Only the worker holding the current value may finalise
     * it. Absent on documents written before the field existed, which a takeover counts
     * from zero.
     */
    private Integer attempt;
    private String resultRecordId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
