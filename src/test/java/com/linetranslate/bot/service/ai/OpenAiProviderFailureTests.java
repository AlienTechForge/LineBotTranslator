package com.linetranslate.bot.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.linetranslate.bot.config.OpenAiConfig;
import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.services.blocking.ResponseService;

class OpenAiProviderFailureTests {

    @Test
    void missingClientThrowsTypedConfigurationFailure() {
        OpenAiConfig config = config();
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAIClient> provider = mock(ObjectProvider.class);
        OpenAiService service = new OpenAiService(config, provider);

        assertThatExceptionOfType(AiProviderException.class)
                .isThrownBy(() -> service.translateText("hello", "zh-TW"))
                .satisfies(error -> assertThat(error.getOutcome())
                        .isEqualTo(AiProviderException.Outcome.CONFIGURATION_ERROR));
    }

    @Test
    void quotaFailureIsNotReturnedAsTranslationText() {
        OpenAIClient client = mock(OpenAIClient.class);
        ResponseService responses = mock(ResponseService.class);
        RateLimitException rateLimit = mock(RateLimitException.class);
        when(rateLimit.statusCode()).thenReturn(429);
        when(rateLimit.code()).thenReturn(Optional.of("insufficient_quota"));
        when(client.responses()).thenReturn(responses);
        when(responses.create(any(ResponseCreateParams.class))).thenThrow(rateLimit);

        OpenAiService service = service(client);

        assertThatExceptionOfType(AiProviderException.class)
                .isThrownBy(() -> service.translateText("hello", "zh-TW"))
                .satisfies(error -> {
                    assertThat(error.getOutcome())
                            .isEqualTo(AiProviderException.Outcome.QUOTA_EXCEEDED);
                    assertThat(error.getHttpStatus()).isEqualTo(429);
                });
    }

    @Test
    void timeoutFailureIsTypedForImageProcessing() {
        OpenAIClient client = mock(OpenAIClient.class);
        ResponseService responses = mock(ResponseService.class);
        when(client.responses()).thenReturn(responses);
        when(responses.create(any(ResponseCreateParams.class))).thenThrow(
                new OpenAIIoException("timed out", new SocketTimeoutException("secret host")));

        OpenAiService service = service(client);

        assertThatExceptionOfType(AiProviderException.class)
                .isThrownBy(() -> service.processImage("ocr", "image-data"))
                .satisfies(error -> assertThat(error.getOutcome())
                        .isEqualTo(AiProviderException.Outcome.TIMEOUT));
    }

    private static OpenAiService service(OpenAIClient client) {
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAIClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return new OpenAiService(config(), provider);
    }

    private static OpenAiConfig config() {
        OpenAiConfig config = mock(OpenAiConfig.class);
        when(config.getModelName()).thenReturn("gpt-test");
        return config;
    }
}
