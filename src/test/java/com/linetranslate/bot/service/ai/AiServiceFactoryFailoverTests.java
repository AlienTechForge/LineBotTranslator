package com.linetranslate.bot.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import com.linetranslate.bot.model.UserProfile;

class AiServiceFactoryFailoverTests {

    private OpenAiService openAiService;
    private GeminiService geminiService;
    private AiServiceFactory factory;
    private UserProfile profile;

    @BeforeEach
    void setUp() {
        openAiService = Mockito.mock(OpenAiService.class);
        geminiService = Mockito.mock(GeminiService.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAiService> openAiProvider = Mockito.mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<GeminiService> geminiProvider = Mockito.mock(ObjectProvider.class);
        when(openAiProvider.getIfAvailable()).thenReturn(openAiService);
        when(geminiProvider.getIfAvailable()).thenReturn(geminiService);
        when(openAiService.getProviderName()).thenReturn("openai");
        when(openAiService.getModelName()).thenReturn("gpt-test");
        when(openAiService.getModelName(org.mockito.ArgumentMatchers.any(UserProfile.class)))
                .thenReturn("gpt-test");
        when(geminiService.getProviderName()).thenReturn("gemini");
        when(geminiService.getModelName()).thenReturn("gemini-test");
        when(geminiService.getModelName(org.mockito.ArgumentMatchers.any(UserProfile.class)))
                .thenReturn("gemini-test");

        factory = new AiServiceFactory(openAiProvider, geminiProvider, "openai");
        profile = UserProfile.builder()
                .userId("U-test")
                .preferredAiProvider("openai")
                .build();
    }

    @Test
    void primarySuccessDoesNotCallFallback() {
        when(openAiService.translateText("hello", "zh-TW")).thenReturn("你好");

        AiExecutionResult result = factory.translateText(profile, "hello", "zh-TW");

        assertThat(result.text()).isEqualTo("你好");
        assertThat(result.providerName()).isEqualTo("openai");
        assertThat(result.modelName()).isEqualTo("gpt-test");
        verify(geminiService, never()).translateText("hello", "zh-TW");
    }

    @Test
    void primaryRuntimeFailureFallsBackOnceAndReportsActualProvider() {
        when(openAiService.translateText("hello", "zh-TW"))
                .thenThrow(failure(AiProviderException.Outcome.RATE_LIMITED, "openai"));
        when(geminiService.translateText("hello", "zh-TW")).thenReturn("你好");

        AiExecutionResult result = factory.translateText(profile, "hello", "zh-TW");

        assertThat(result.text()).isEqualTo("你好");
        assertThat(result.providerName()).isEqualTo("gemini");
        assertThat(result.modelName()).isEqualTo("gemini-test");
        verify(openAiService).translateText("hello", "zh-TW");
        verify(geminiService).translateText("hello", "zh-TW");
    }

    @Test
    void bothProvidersFailAfterOneAttemptEach() {
        when(openAiService.translateText("hello", "zh-TW"))
                .thenThrow(failure(AiProviderException.Outcome.QUOTA_EXCEEDED, "openai"));
        when(geminiService.translateText("hello", "zh-TW"))
                .thenThrow(failure(AiProviderException.Outcome.TRANSPORT_ERROR, "gemini"));

        assertThatExceptionOfType(AiProviderException.class)
                .isThrownBy(() -> factory.translateText(profile, "hello", "zh-TW"))
                .satisfies(error -> assertThat(error.getProvider()).isEqualTo("gemini"));

        verify(openAiService).translateText("hello", "zh-TW");
        verify(geminiService).translateText("hello", "zh-TW");
    }

    @Test
    void imageProcessingUsesTheSameBoundedFallback() {
        when(openAiService.processImage("ocr", "image"))
                .thenThrow(failure(AiProviderException.Outcome.TIMEOUT, "openai"));
        when(geminiService.processImage("ocr", "image")).thenReturn("recognized");

        AiExecutionResult result = factory.processImage(profile, "ocr", "image");

        assertThat(result.text()).isEqualTo("recognized");
        assertThat(result.providerName()).isEqualTo("gemini");
        verify(openAiService).processImage("ocr", "image");
        verify(geminiService).processImage("ocr", "image");
    }

    @Test
    void safetyBlockDoesNotRetryAnotherProvider() {
        when(openAiService.translateText("unsafe", "zh-TW"))
                .thenThrow(failure(AiProviderException.Outcome.SAFETY_BLOCKED, "openai"));

        assertThatExceptionOfType(AiProviderException.class)
                .isThrownBy(() -> factory.translateText(profile, "unsafe", "zh-TW"))
                .satisfies(error -> assertThat(error.getOutcome())
                        .isEqualTo(AiProviderException.Outcome.SAFETY_BLOCKED));

        verify(geminiService, never()).translateText("unsafe", "zh-TW");
    }

    private static AiProviderException failure(AiProviderException.Outcome outcome, String provider) {
        return new AiProviderException(
                outcome,
                provider,
                provider + "-model",
                outcome.name(),
                "correlation-1",
                -1,
                null);
    }
}
