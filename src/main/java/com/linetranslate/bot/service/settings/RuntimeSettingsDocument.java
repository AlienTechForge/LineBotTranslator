package com.linetranslate.bot.service.settings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document("runtime_settings")
@Data
@NoArgsConstructor
public class RuntimeSettingsDocument {

    @Id
    private String id;
    private Integer schemaVersion;
    private String defaultTargetLanguageForChinese;
    private String defaultTargetLanguageForOthers;
    private String defaultAiProvider;
    private String openAiDefaultModel;
    private String geminiDefaultModel;
    private Boolean ocrEnabled;
    private Long revision;
    private Instant updatedAt;
    private String updatedBy;
    private List<Change> changes = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Change {
        private String key;
        private Object previousValue;
        private Object newValue;
        private String updatedBy;
        private Instant updatedAt;
    }
}
