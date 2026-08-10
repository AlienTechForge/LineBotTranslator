package com.linetranslate.bot.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.linetranslate.bot.config.OpenAiConfig;
import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.ResponsesModel;
import com.openai.services.blocking.ResponseService;

class OpenAiProviderFailureTests {

    @Test
    void adapterSendsSelectedModelAndReturnsProviderUsage() {
        OpenAIClient client = mock(OpenAIClient.class);
        ResponseService responses = mock(ResponseService.class);
        com.openai.models.responses.Response response = successfulResponse();
        ArgumentCaptor<ResponseCreateParams> params = ArgumentCaptor.forClass(ResponseCreateParams.class);
        when(client.responses()).thenReturn(responses);
        when(responses.create(params.capture())).thenReturn(response);

        AiProviderResponse result = service(client).execute(
                AiProviderRequest.translate("gpt-selected", "hello", "zh-TW"));

        assertThat(params.getValue().model().orElseThrow().asString()).isEqualTo("gpt-selected");
        assertThat(result.text()).isEqualTo("你好");
        assertThat(result.model()).isEqualTo("gpt-actual");
        assertThat(result.tokenUsage()).isEqualTo(new AiTokenUsage(11, 5, 16));
    }

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
        when(config.getAvailableModels()).thenReturn(List.of("gpt-test", "gpt-selected"));
        return config;
    }

    private static com.openai.models.responses.Response successfulResponse() {
        com.openai.models.responses.Response response = mock(com.openai.models.responses.Response.class);
        ResponseOutputItem item = mock(ResponseOutputItem.class);
        ResponseOutputMessage message = mock(ResponseOutputMessage.class);
        ResponseOutputMessage.Content content = mock(ResponseOutputMessage.Content.class);
        ResponseOutputText outputText = mock(ResponseOutputText.class);
        ResponseUsage usage = mock(ResponseUsage.class);

        when(response.output()).thenReturn(List.of(item));
        when(item.message()).thenReturn(Optional.of(message));
        when(message.content()).thenReturn(List.of(content));
        when(content.outputText()).thenReturn(Optional.of(outputText));
        when(outputText.text()).thenReturn("你好");
        when(response.model()).thenReturn(ResponsesModel.ofString("gpt-actual"));
        when(response.usage()).thenReturn(Optional.of(usage));
        when(usage.inputTokens()).thenReturn(11L);
        when(usage.outputTokens()).thenReturn(5L);
        when(usage.totalTokens()).thenReturn(16L);
        return response;
    }
}
