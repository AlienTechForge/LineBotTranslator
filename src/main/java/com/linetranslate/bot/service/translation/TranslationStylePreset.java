package com.linetranslate.bot.service.translation;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Stable, localized and prompt-versioned translation style contract. */
public enum TranslationStylePreset {
    FAITHFUL(
            "faithful", "忠實", "Faithful", "faithful-v1",
            "Preserve the source meaning, tone, details, and ambiguity. Do not add or omit information."),
    NATURAL(
            "natural", "自然", "Natural", "natural-v1",
            "Use idiomatic, fluent phrasing natural to a native speaker while preserving the full meaning."),
    CASUAL(
            "casual", "口語", "Casual", "casual-v1",
            "Use friendly conversational language and natural everyday expressions without changing meaning."),
    FORMAL(
            "formal", "正式", "Formal", "formal-v1",
            "Use polished formal language, complete sentences, and a respectful tone."),
    BUSINESS(
            "business", "商務", "Business", "business-v1",
            "Use concise professional business terminology and an appropriate workplace tone."),
    SUBTITLE(
            "subtitle", "字幕精簡", "Subtitle concise", "subtitle-v1",
            "Produce concise subtitle-friendly wording. Remove verbal filler only when meaning is preserved.");

    private final String id;
    private final String zhTwName;
    private final String englishName;
    private final String promptVersion;
    private final String promptRule;

    TranslationStylePreset(
            String id,
            String zhTwName,
            String englishName,
            String promptVersion,
            String promptRule) {
        this.id = id;
        this.zhTwName = zhTwName;
        this.englishName = englishName;
        this.promptVersion = promptVersion;
        this.promptRule = promptRule;
    }

    public String id() { return id; }
    public String promptVersion() { return promptVersion; }
    public String promptRule() { return promptRule; }

    public String localizedName(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("zh")
                ? zhTwName
                : englishName;
    }

    public static List<TranslationStylePreset> available() {
        return List.copyOf(Arrays.asList(values()));
    }

    public static TranslationStylePreset defaultPreset() {
        return FAITHFUL;
    }

    public static Optional<TranslationStylePreset> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        return available().stream().filter(preset -> preset.id.equals(normalized)).findFirst();
    }

    /** Safe read path for missing, legacy or removed stored values. */
    public static TranslationStylePreset resolve(String id) {
        return find(id).orElse(defaultPreset());
    }
}
