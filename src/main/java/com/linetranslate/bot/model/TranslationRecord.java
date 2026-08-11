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
    private LocalDateTime createdAt;
    private double processingTimeMs;
    private boolean isImageTranslation;
    private String imageUrl; // 如果是圖片翻譯，則存儲圖片URL
    private Boolean imageStored; // null = legacy record with unknown storage state

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
