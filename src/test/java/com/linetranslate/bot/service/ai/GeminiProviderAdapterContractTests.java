package com.linetranslate.bot.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linetranslate.bot.config.GeminiConfig;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

class GeminiProviderAdapterContractTests {

    @Test
    void adapterSendsSelectedModelAndReturnsProviderUsage() throws IOException {
        GeminiConfig config = mock(GeminiConfig.class);
        when(config.getModelName()).thenReturn("gemini-default");
        when(config.getApiKey()).thenReturn("test-key");
        when(config.getAvailableModels()).thenReturn(List.of("gemini-default", "gemini-selected"));

        OkHttpClient httpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        AtomicReference<Request> capturedRequest = new AtomicReference<>();
        when(httpClient.newCall(any(Request.class))).thenAnswer(invocation -> {
            capturedRequest.set(invocation.getArgument(0));
            return call;
        });
        when(call.execute()).thenReturn(successfulResponse());
        GeminiService adapter = new GeminiService(config, httpClient, new ObjectMapper());

        AiProviderResponse result = adapter.execute(
                AiProviderRequest.translate("gemini-selected", "hello", "zh-TW"));

        assertThat(capturedRequest.get().url().encodedPath())
                .contains("/models/gemini-selected:generateContent");
        assertThat(result.text()).isEqualTo("你好");
        assertThat(result.model()).isEqualTo("gemini-actual");
        assertThat(result.tokenUsage()).isEqualTo(new AiTokenUsage(8, 3, 11));
    }

    private static Response successfulResponse() {
        String body = """
                {
                  "modelVersion": "gemini-actual",
                  "candidates": [{
                    "finishReason": "STOP",
                    "content": { "parts": [{ "text": "你好" }] }
                  }],
                  "usageMetadata": {
                    "promptTokenCount": 8,
                    "candidatesTokenCount": 3,
                    "totalTokenCount": 11
                  }
                }
                """;
        Request request = new Request.Builder()
                .url("https://example.test")
                .build();
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
    }
}
