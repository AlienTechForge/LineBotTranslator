package com.linetranslate.bot.model;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document("translation_records")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslationRecord {

    @Id
    private String id;

    private String userId;
    private String sourceText;
    private String sourceLanguage;
    private String targetLanguage;
    private String translatedText;
    private String aiProvider; // openrouter（歷史資料可能保留舊維度）
    private String modelName;
    private String stylePresetId;
    private String stylePromptVersion;
    private LocalDateTime createdAt;
    private double processingTimeMs;
    private boolean isImageTranslation;
    private String imageUrl; // 如果是圖片翻譯，則存儲圖片URL
    private Boolean imageStored; // null = legacy record with unknown storage state

    // 圖片翻譯服務（image-translator）的結果；文字翻譯與舊紀錄皆為 null
    private String imageMode; // A 或 B：被採用的嘗試
    private String imageTier; // clean、warned，或 text_only 時為 null
    private String imageVerdict; // judge 的判定
    private Double imageUsability; // 0–1
    private Double imageCostUsd; // 這個請求花的錢（美元）

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
