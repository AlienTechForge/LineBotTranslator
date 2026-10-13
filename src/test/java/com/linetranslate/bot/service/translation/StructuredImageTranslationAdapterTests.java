package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linetranslate.bot.service.ai.AiExecutionFailure;
import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderException;
import com.linetranslate.bot.service.preference.UserPreferences;

class StructuredImageTranslationAdapterTests {
    @Test
    void performsAtMostOneRepairAndReturnsReadableExecution() {
        CachedTranslationAdapter provider = mock(CachedTranslationAdapter.class);
        UserPreferences preferences = new UserPreferences("zh-TW", "en", "zh-TW", "model", List.of());
        when(provider.translateValidated(eq(preferences), anyString(), eq("zh-TW"),
                eq(TranslationStylePreset.FAITHFUL), eq(StructuredImageTranslationCodec.SCHEMA_VERSION), any()))
                .thenReturn(success("malformed"))
                .thenReturn(success("{\"schemaVersion\":\"image-regions-v3\",\"regions\":[{\"regionId\":\"r1\",\"translatedText\":\"你好\"}]}"));
        StructuredImageTranslationAdapter adapter = new StructuredImageTranslationAdapter(
                provider, new StructuredImageTranslationCodec(new ObjectMapper()));

        StructuredImageTranslationAdapter.Result result = adapter.translate(preferences,
                List.of(
                        new ImageRegionTranslationInput("r1", "hello", "en", List.of(), true, 0),
                        new ImageRegionTranslationInput("date", "2021.05.28", "und",
                                List.of("2021.05.28"), false, 1)),
                "zh-TW", TranslationStylePreset.FAITHFUL);

        assertThat(result.execution().text()).isEqualTo("你好\n2021.05.28");
        assertThat(result.translations()).containsExactly(new ImageRegionTranslation("r1", "你好"));
        verify(provider, times(2)).translateValidated(eq(preferences), anyString(), eq("zh-TW"),
                eq(TranslationStylePreset.FAITHFUL), eq(StructuredImageTranslationCodec.SCHEMA_VERSION), any());
    }

    @Test
    void secondInvalidResponseFailsClosedWithoutThirdAttempt() {
        CachedTranslationAdapter provider = mock(CachedTranslationAdapter.class);
        UserPreferences preferences = new UserPreferences("zh-TW", "en", "zh-TW", "model", List.of());
        when(provider.translateValidated(eq(preferences), anyString(), eq("zh-TW"),
                eq(TranslationStylePreset.FAITHFUL), eq(StructuredImageTranslationCodec.SCHEMA_VERSION), any()))
                .thenReturn(success("malformed"));
        StructuredImageTranslationAdapter adapter = new StructuredImageTranslationAdapter(
                provider, new StructuredImageTranslationCodec(new ObjectMapper()));

        assertThatThrownBy(() -> adapter.translate(preferences,
                List.of(new ImageRegionTranslationInput("r1", "hello", "en", List.of())),
                "zh-TW", TranslationStylePreset.FAITHFUL))
                .isInstanceOf(StructuredTranslationException.class);
        verify(provider, times(2)).translateValidated(eq(preferences), anyString(), eq("zh-TW"),
                eq(TranslationStylePreset.FAITHFUL), eq(StructuredImageTranslationCodec.SCHEMA_VERSION), any());
    }

    @Test
    void truncatedResponsesAreReportedAsProviderFailureRatherThanUnusableRegions() {
        CachedTranslationAdapter provider = mock(CachedTranslationAdapter.class);
        UserPreferences preferences = new UserPreferences("zh-TW", "en", "zh-TW", "model", List.of());
        AiExecutionOutcome truncated = new AiExecutionOutcome.Failure(new AiExecutionFailure(
                AiProviderException.Outcome.MALFORMED_RESPONSE, "openrouter", "model",
                "TRUNCATED_OUTPUT", "correlation-1", 200, List.of()));
        when(provider.translateValidated(eq(preferences), anyString(), eq("zh-TW"),
                eq(TranslationStylePreset.FAITHFUL), eq(StructuredImageTranslationCodec.SCHEMA_VERSION), any()))
                .thenReturn(truncated);
        StructuredImageTranslationAdapter adapter = new StructuredImageTranslationAdapter(
                provider, new StructuredImageTranslationCodec(new ObjectMapper()));

        assertThatThrownBy(() -> adapter.translate(preferences,
                List.of(new ImageRegionTranslationInput("r1", "hello", "en", List.of())),
                "zh-TW", TranslationStylePreset.FAITHFUL))
                .isInstanceOfSatisfying(StructuredTranslationException.class, failure -> assertThat(
                        failure.reason()).isEqualTo(StructuredTranslationException.PROVIDER_FAILED));
        verify(provider, times(2)).translateValidated(eq(preferences), anyString(), eq("zh-TW"),
                eq(TranslationStylePreset.FAITHFUL), eq(StructuredImageTranslationCodec.SCHEMA_VERSION), any());
    }

    @Test
    void simplifiedChineseResponseForTaiwanTriggersTheSingleRepairAttempt() {
        CachedTranslationAdapter provider = mock(CachedTranslationAdapter.class);
        UserPreferences preferences = new UserPreferences("zh-TW", "en", "zh-TW", "model", List.of());
        when(provider.translateValidated(eq(preferences), anyString(), eq("zh-TW"),
                eq(TranslationStylePreset.FAITHFUL), eq(StructuredImageTranslationCodec.SCHEMA_VERSION), any()))
                .thenReturn(success("{\"schemaVersion\":\"image-regions-v3\",\"regions\":[{\"regionId\":\"r1\",\"translatedText\":\"保护自己免受热伤害\"}]}"))
                .thenReturn(success("{\"schemaVersion\":\"image-regions-v3\",\"regions\":[{\"regionId\":\"r1\",\"translatedText\":\"保護自己免受熱傷害\"}]}"));
        StructuredImageTranslationAdapter adapter = new StructuredImageTranslationAdapter(
                provider, new StructuredImageTranslationCodec(new ObjectMapper()), new TargetLocalePolicy());

        var result = adapter.translate(preferences,
                List.of(new ImageRegionTranslationInput("r1", "Protect yourself", "en", List.of())),
                "zh-TW", TranslationStylePreset.FAITHFUL);

        assertThat(result.translations()).containsExactly(
                new ImageRegionTranslation("r1", "保護自己免受熱傷害"));
        verify(provider, times(2)).translateValidated(eq(preferences), anyString(), eq("zh-TW"),
                eq(TranslationStylePreset.FAITHFUL), eq(StructuredImageTranslationCodec.SCHEMA_VERSION), any());
    }

    @Test
    void overBudgetCellDoesNotTriggerWholeImageRepairOrFallback() {
        CachedTranslationAdapter provider = mock(CachedTranslationAdapter.class);
        UserPreferences preferences = new UserPreferences("zh-TW", "en", "en", "model", List.of());
        when(provider.translateValidated(eq(preferences), anyString(), eq("en"),
                eq(TranslationStylePreset.FAITHFUL), eq(StructuredImageTranslationCodec.SCHEMA_VERSION), any()))
                .thenReturn(success("{\"schemaVersion\":\"image-regions-v3\",\"regions\":["
                        + "{\"regionId\":\"long\",\"translatedText\":\"Long translated menu label\"},"
                        + "{\"regionId\":\"short\",\"translatedText\":\"Cut\"}]}"));
        StructuredImageTranslationAdapter adapter = new StructuredImageTranslationAdapter(
                provider, new StructuredImageTranslationCodec(new ObjectMapper()));
        List<ImageRegionTranslationInput> regions = List.of(
                new ImageRegionTranslationInput("long", "染髮套餐", "zh", List.of(), true, 0,
                        new ImageRegionLayout("menu", 0, 0, 80, 24, 1, 8, true)),
                new ImageRegionTranslationInput("short", "剪髮", "zh", List.of(), true, 1,
                        new ImageRegionLayout("menu", 0, 30, 80, 24, 1, 8, true)));

        var result = adapter.translate(preferences, regions, "en", TranslationStylePreset.FAITHFUL);

        assertThat(result.translations()).hasSize(2);
        verify(provider, times(1)).translateValidated(eq(preferences), anyString(), eq("en"),
                eq(TranslationStylePreset.FAITHFUL), eq(StructuredImageTranslationCodec.SCHEMA_VERSION), any());
    }

    @Test
    void incompleteResponseStillOverlaysTheRegionsThatWereReturnedCorrectly() {
        CachedTranslationAdapter provider = mock(CachedTranslationAdapter.class);
        UserPreferences preferences = new UserPreferences("zh-TW", "en", "zh-TW", "model", List.of());
        when(provider.translateValidated(eq(preferences), anyString(), eq("zh-TW"),
                eq(TranslationStylePreset.FAITHFUL), eq(StructuredImageTranslationCodec.SCHEMA_VERSION), any()))
                .thenReturn(success("{\"schemaVersion\":\"image-regions-v3\",\"regions\":["
                        + "{\"regionId\":\"r1\",\"translatedText\":\"你好\"}]}"));
        StructuredImageTranslationAdapter adapter = new StructuredImageTranslationAdapter(
                provider, new StructuredImageTranslationCodec(new ObjectMapper()));

        StructuredImageTranslationAdapter.Result result = adapter.translate(preferences,
                List.of(
                        new ImageRegionTranslationInput("r1", "hello", "en", List.of(), true, 0),
                        new ImageRegionTranslationInput("r2", "world", "en", List.of(), true, 1)),
                "zh-TW", TranslationStylePreset.FAITHFUL);

        assertThat(result.translations()).containsExactly(new ImageRegionTranslation("r1", "你好"));
        assertThat(result.execution().text()).isEqualTo("你好\nworld");
        verify(provider, times(2)).translateValidated(eq(preferences), anyString(), eq("zh-TW"),
                eq(TranslationStylePreset.FAITHFUL), eq(StructuredImageTranslationCodec.SCHEMA_VERSION), any());
    }

    @Test
    void localeViolationDropsOnlyTheOffendingRegionAfterTheRepairAttempt() {
        CachedTranslationAdapter provider = mock(CachedTranslationAdapter.class);
        UserPreferences preferences = new UserPreferences("zh-TW", "en", "zh-TW", "model", List.of());
        String simplifiedSecondRegion = "{\"schemaVersion\":\"image-regions-v3\",\"regions\":["
                + "{\"regionId\":\"r1\",\"translatedText\":\"保護自己\"},"
                + "{\"regionId\":\"r2\",\"translatedText\":\"这为个们来时说对会还国\"}]}";
        when(provider.translateValidated(eq(preferences), anyString(), eq("zh-TW"),
                eq(TranslationStylePreset.FAITHFUL), eq(StructuredImageTranslationCodec.SCHEMA_VERSION), any()))
                .thenReturn(success(simplifiedSecondRegion));
        StructuredImageTranslationAdapter adapter = new StructuredImageTranslationAdapter(
                provider, new StructuredImageTranslationCodec(new ObjectMapper()), new TargetLocalePolicy());

        StructuredImageTranslationAdapter.Result result = adapter.translate(preferences,
                List.of(
                        new ImageRegionTranslationInput("r1", "Protect yourself", "en", List.of(), true, 0),
                        new ImageRegionTranslationInput("r2", "Stay hydrated", "en", List.of(), true, 1)),
                "zh-TW", TranslationStylePreset.FAITHFUL);

        assertThat(result.translations()).containsExactly(new ImageRegionTranslation("r1", "保護自己"));
        assertThat(result.execution().text()).isEqualTo("保護自己\nStay hydrated");
    }

    @Test
    void aProviderBugInAnAttemptIsKeptEvenWhenTheRepairAttemptSucceeds() {
        CachedTranslationAdapter provider = mock(CachedTranslationAdapter.class);
        UserPreferences preferences = new UserPreferences("zh-TW", "en", "zh-TW", "model", List.of());
        NullPointerException bug = new NullPointerException("seeded-private-detail");
        AiExecutionOutcome unexpected = providerFailure(AiProviderException.Outcome.UNEXPECTED_ERROR, bug);
        when(provider.translateValidated(eq(preferences), anyString(), eq("zh-TW"),
                eq(TranslationStylePreset.FAITHFUL), eq(StructuredImageTranslationCodec.SCHEMA_VERSION), any()))
                .thenReturn(unexpected)
                .thenReturn(success("{\"schemaVersion\":\"image-regions-v3\",\"regions\":["
                        + "{\"regionId\":\"r1\",\"translatedText\":\"你好\"}]}"));
        StructuredImageTranslationAdapter adapter = new StructuredImageTranslationAdapter(
                provider, new StructuredImageTranslationCodec(new ObjectMapper()));

        StructuredImageTranslationAdapter.Result result = adapter.translate(preferences,
                List.of(new ImageRegionTranslationInput("r1", "hello", "en", List.of(), true, 0)),
                "zh-TW", TranslationStylePreset.FAITHFUL);

        assertThat(result.translations()).containsExactly(new ImageRegionTranslation("r1", "你好"));
        assertThat(result.unexpectedFailure()).isSameAs(((AiExecutionOutcome.Failure) unexpected).failure());
        assertThat(result.unexpectedFailure().cause()).isSameAs(bug);
    }

    @Test
    void aProviderBugInAnAttemptTravelsWithTheExceptionWhenBothAttemptsFail() {
        CachedTranslationAdapter provider = mock(CachedTranslationAdapter.class);
        UserPreferences preferences = new UserPreferences("zh-TW", "en", "zh-TW", "model", List.of());
        NullPointerException bug = new NullPointerException("seeded-private-detail");
        AiExecutionOutcome unexpected = providerFailure(AiProviderException.Outcome.UNEXPECTED_ERROR, bug);
        when(provider.translateValidated(eq(preferences), anyString(), eq("zh-TW"),
                eq(TranslationStylePreset.FAITHFUL), eq(StructuredImageTranslationCodec.SCHEMA_VERSION), any()))
                .thenReturn(unexpected)
                .thenReturn(providerFailure(AiProviderException.Outcome.RATE_LIMITED, null));
        StructuredImageTranslationAdapter adapter = new StructuredImageTranslationAdapter(
                provider, new StructuredImageTranslationCodec(new ObjectMapper()));

        assertThatThrownBy(() -> adapter.translate(preferences,
                List.of(new ImageRegionTranslationInput("r1", "hello", "en", List.of())),
                "zh-TW", TranslationStylePreset.FAITHFUL))
                .isInstanceOfSatisfying(StructuredTranslationException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(StructuredTranslationException.PROVIDER_FAILED);
                    assertThat(failure.unexpectedFailure())
                            .isSameAs(((AiExecutionOutcome.Failure) unexpected).failure());
                });
    }

    @Test
    void knownProviderFailuresAreNotReportedAsUnexpected() {
        CachedTranslationAdapter provider = mock(CachedTranslationAdapter.class);
        UserPreferences preferences = new UserPreferences("zh-TW", "en", "zh-TW", "model", List.of());
        when(provider.translateValidated(eq(preferences), anyString(), eq("zh-TW"),
                eq(TranslationStylePreset.FAITHFUL), eq(StructuredImageTranslationCodec.SCHEMA_VERSION), any()))
                .thenReturn(providerFailure(AiProviderException.Outcome.TIMEOUT, null));
        StructuredImageTranslationAdapter adapter = new StructuredImageTranslationAdapter(
                provider, new StructuredImageTranslationCodec(new ObjectMapper()));

        assertThatThrownBy(() -> adapter.translate(preferences,
                List.of(new ImageRegionTranslationInput("r1", "hello", "en", List.of())),
                "zh-TW", TranslationStylePreset.FAITHFUL))
                .isInstanceOfSatisfying(StructuredTranslationException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(StructuredTranslationException.PROVIDER_FAILED);
                    assertThat(failure.unexpectedFailure()).isNull();
                });
    }

    private static AiExecutionOutcome providerFailure(AiProviderException.Outcome outcome, Throwable cause) {
        return new AiExecutionOutcome.Failure(AiExecutionFailure.from(new AiProviderException(
                outcome, "openrouter", "model", outcome.name(), "correlation-1", -1, cause), List.of()));
    }

    private static AiExecutionOutcome success(String text) {
        return new AiExecutionOutcome.Success(new AiExecutionResult(text, "openrouter", "model"));
    }
}
