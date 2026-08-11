package com.linetranslate.bot.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document("user_profiles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile {

    @Id
    private String id;

    private String userId;
    private String displayName;
    private String pictureUrl;
    private String statusMessage;
    private String preferredLanguage;      // 用戶偏好的語言 (翻譯目標)
    private String preferredModel;         // 用戶偏好的 OpenRouter 模型 slug
    private String preferredTranslationStyle; // stable TranslationStylePreset ID
    private String preferredChineseTargetLanguage; // 用戶偏好的中文翻譯目標語言

    @Builder.Default
    private List<String> recentTranslations = new ArrayList<>();

    @Builder.Default
    private Set<String> recentLanguages = new LinkedHashSet<>();  // 用戶最近使用的語言 (有序集合，最近的排在前面)

    @Builder.Default
    private LocalDateTime firstInteractionAt = LocalDateTime.now();

    private LocalDateTime lastInteractionAt;

    @Builder.Default
    private int totalTranslations = 0;

    @Builder.Default
    private int textTranslations = 0;

    @Builder.Default
    private int imageTranslations = 0;

    /**
     * 添加最近使用的語言
     *
     * @param languageCode 語言代碼
     */
    public void addRecentLanguage(String languageCode) {
        if (languageCode == null || languageCode.trim().isEmpty()) {
            return;
        }

        // 如果已經存在，先移除，然後再添加到集合的開頭
        recentLanguages.remove(languageCode);

        // 將新的語言添加到集合
        Set<String> newRecentLanguages = new LinkedHashSet<>();
        newRecentLanguages.add(languageCode);
        newRecentLanguages.addAll(recentLanguages);

        // 只保留最近的 5 個語言
        if (newRecentLanguages.size() > 5) {
            recentLanguages = newRecentLanguages.stream()
                    .limit(5)
                    .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        } else {
            recentLanguages = newRecentLanguages;
        }
    }

    /**
     * 獲取最近使用的語言列表
     *
     * @return 語言代碼列表
     */
    public List<String> getRecentLanguagesList() {
        return new ArrayList<>(recentLanguages);
    }
}
