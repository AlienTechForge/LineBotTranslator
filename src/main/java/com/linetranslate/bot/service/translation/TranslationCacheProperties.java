package com.linetranslate.bot.service.translation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Component
@Validated
@ConfigurationProperties(prefix = "app.translation.cache")
public class TranslationCacheProperties {

    @NotNull
    private Duration ttl = Duration.ofMinutes(30);

    @Min(1)
    private long maxEntries = 1_000;

    @NotBlank
    private String style = "neutral";

    @NotBlank
    private String glossaryVersion = "none";

    @NotBlank
    private String promptVersion = "translation-v1";

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public long getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(long maxEntries) {
        this.maxEntries = maxEntries;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }

    public String getGlossaryVersion() {
        return glossaryVersion;
    }

    public void setGlossaryVersion(String glossaryVersion) {
        this.glossaryVersion = glossaryVersion;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public TranslationCacheVariant currentVariant() {
        return new TranslationCacheVariant(style, glossaryVersion, promptVersion);
    }
}
