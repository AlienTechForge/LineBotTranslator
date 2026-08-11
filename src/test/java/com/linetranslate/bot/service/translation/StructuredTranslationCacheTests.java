package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderExecutionModule;
import com.linetranslate.bot.service.ai.AiProviderRoute;
import com.linetranslate.bot.service.preference.UserPreferences;

class StructuredTranslationCacheTests {
    @Test
    void validStructuredValueUsesContractVersionInCacheIdentity() {
        Fixture fixture = fixture("valid");
        fixture.adapter.translateValidated(fixture.preferences, "wire-v1", "zh-TW",
                TranslationStylePreset.FAITHFUL, "image-regions-v1", result -> result.text().equals("valid"));

        ArgumentCaptor<TranslationCacheKey> planned = ArgumentCaptor.forClass(TranslationCacheKey.class);
        verify(fixture.cache).put(planned.capture(), any(), any());
        assertThat(planned.getValue().promptVersion()).contains("faithful-v1", "image-regions-v1");
    }

    @Test
    void invalidStructuredValueIsNeverCached() {
        Fixture fixture = fixture("invalid");
        fixture.adapter.translateValidated(fixture.preferences, "wire-v1", "zh-TW",
                TranslationStylePreset.FAITHFUL, "image-regions-v1", result -> false);

        verify(fixture.cache, never()).put(any(), any(), any());
        verify(fixture.cache).recordSkipped(TranslationCacheSkipReason.INVALID_RESPONSE);
    }

    private static Fixture fixture(String response) {
        AiProviderExecutionModule provider = mock(AiProviderExecutionModule.class);
        TranslationCacheStore cache = mock(TranslationCacheStore.class);
        TranslationCacheProperties properties = new TranslationCacheProperties();
        UserPreferences preferences = new UserPreferences("zh-TW", "en", "zh-TW", "model", List.of());
        when(provider.planText(preferences)).thenReturn(new AiProviderRoute("openrouter", "model"));
        when(provider.translateTextOutcome(eq(preferences), eq("wire-v1"), eq("zh-TW"),
                eq(TranslationStylePreset.FAITHFUL))).thenReturn(new AiExecutionOutcome.Success(
                        new AiExecutionResult(response, "openrouter", "model")));
        when(cache.find(any())).thenReturn(Optional.empty());
        return new Fixture(new CachedTranslationAdapter(provider, cache, properties), cache, preferences);
    }

    private record Fixture(
            CachedTranslationAdapter adapter,
            TranslationCacheStore cache,
            UserPreferences preferences) {
    }
}
