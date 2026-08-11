package com.linetranslate.bot.service.settings;

/** Allowlisted, non-sensitive settings that administrators may change at runtime. */
public enum RuntimeSettingKey {
    DEFAULT_CHINESE_TARGET_LANGUAGE("defaultTargetLanguageForChinese"),
    DEFAULT_OTHER_TARGET_LANGUAGE("defaultTargetLanguageForOthers"),
    DEFAULT_AI_PROVIDER("defaultAiProvider"),
    OPENAI_DEFAULT_MODEL("openAiDefaultModel"),
    GEMINI_DEFAULT_MODEL("geminiDefaultModel"),
    OCR_ENABLED("ocrEnabled");

    private final String fieldName;

    RuntimeSettingKey(String fieldName) {
        this.fieldName = fieldName;
    }

    public String fieldName() {
        return fieldName;
    }
}
