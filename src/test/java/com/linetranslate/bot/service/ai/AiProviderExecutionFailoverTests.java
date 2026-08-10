package com.linetranslate.bot.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.linetranslate.bot.model.UserProfile;

class AiProviderExecutionFailoverTests {

    private AiProviderAdapter openAi;
    private AiProviderAdapter gemini;
    private AiProviderExecutionModule module;
    private UserProfile profile;

    @BeforeEach
    void setUp() {
        openAi = adapter("openai", "gpt-test");
        gemini = adapter("gemini", "gemini-test");
        module = new AiProviderExecutionModule(List.of(openAi, gemini), "openai");
        profile = UserProfile.builder()
                .userId("U-test")
                .preferredAiProvider("openai")
                .build();
    }

    @Test
    void primarySuccessDoesNotCallFallback() {
        when(openAi.execute(any())).thenReturn(response("你好", "gpt-test"));

        AiExecutionResult result = module.translateText(profile, "hello", "zh-TW");

        assertThat(result.text()).isEqualTo("你好");
        assertThat(result.providerName()).isEqualTo("openai");
        assertThat(result.modelName()).isEqualTo("gpt-test");
        verify(gemini, never()).execute(any());
    }

    @Test
    void primaryRuntimeFailureFallsBackOnceAndReportsActualProvider() {
        when(openAi.execute(any()))
                .thenThrow(failure(AiProviderException.Outcome.RATE_LIMITED, "openai", "gpt-test"));
        when(gemini.execute(any())).thenReturn(response("你好", "gemini-actual"));

        AiExecutionResult result = module.translateText(profile, "hello", "zh-TW");

        assertThat(result.providerName()).isEqualTo("gemini");
        assertThat(result.modelName()).isEqualTo("gemini-actual");
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.attempts()).hasSize(2);
    }

    @Test
    void bothProvidersFailAfterOneAttemptEach() {
        when(openAi.execute(any()))
                .thenThrow(failure(AiProviderException.Outcome.QUOTA_EXCEEDED, "openai", "gpt-test"));
        when(gemini.execute(any()))
                .thenThrow(failure(AiProviderException.Outcome.TRANSPORT_ERROR, "gemini", "gemini-test"));

        AiExecutionOutcome outcome = module.translateTextOutcome(profile, "hello", "zh-TW");

        assertThat(outcome).isInstanceOf(AiExecutionOutcome.Failure.class);
        AiExecutionFailure failure = ((AiExecutionOutcome.Failure) outcome).failure();
        assertThat(failure.provider()).isEqualTo("gemini");
        assertThat(failure.attempts()).hasSize(2);
        assertThatExceptionOfType(AiProviderException.class)
                .isThrownBy(outcome::resultOrThrow)
                .satisfies(error -> assertThat(error.getProvider()).isEqualTo("gemini"));
    }

    @Test
    void imageProcessingUsesTheSameBoundedFallback() {
        when(openAi.execute(any()))
                .thenThrow(failure(AiProviderException.Outcome.TIMEOUT, "openai", "gpt-test"));
        when(gemini.execute(any())).thenReturn(response("recognized", "gemini-test"));

        AiExecutionResult result = module.processImage(profile, "ocr", "image");

        assertThat(result.text()).isEqualTo("recognized");
        verify(openAi).execute(any());
        verify(gemini).execute(any());
    }

    @Test
    void safetyBlockDoesNotRetryAnotherProvider() {
        when(openAi.execute(any()))
                .thenThrow(failure(AiProviderException.Outcome.SAFETY_BLOCKED, "openai", "gpt-test"));

        AiExecutionOutcome outcome = module.translateTextOutcome(profile, "unsafe", "zh-TW");

        assertThat(((AiExecutionOutcome.Failure) outcome).failure().outcome())
                .isEqualTo(AiProviderException.Outcome.SAFETY_BLOCKED);
        verify(gemini, never()).execute(any());
    }

    private static AiProviderAdapter adapter(String provider, String model) {
        AiProviderAdapter adapter = Mockito.mock(AiProviderAdapter.class);
        when(adapter.providerName()).thenReturn(provider);
        when(adapter.defaultModel()).thenReturn(model);
        when(adapter.availableModels()).thenReturn(Set.of(model));
        when(adapter.capabilities()).thenReturn(Set.of(AiProviderOperation.values()));
        return adapter;
    }

    private static AiProviderResponse response(String text, String model) {
        return new AiProviderResponse(text, model, new AiTokenUsage(5, 2, 7));
    }

    private static AiProviderException failure(
            AiProviderException.Outcome outcome,
            String provider,
            String model) {
        return new AiProviderException(
                outcome,
                provider,
                model,
                outcome.name(),
                "correlation-1",
                -1,
                null);
    }
}
