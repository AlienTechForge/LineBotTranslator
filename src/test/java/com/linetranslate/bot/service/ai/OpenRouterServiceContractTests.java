package com.linetranslate.bot.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linetranslate.bot.config.OpenRouterConfig;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

class OpenRouterServiceContractTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void adapterSendsSelectedModelWithBearerAuthAndReturnsUsage() throws Exception {
        OpenRouterConfig config = config();
        OkHttpClient httpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        AtomicReference<Request> captured = new AtomicReference<>();
        when(httpClient.newCall(any(Request.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return call;
        });
        when(call.execute()).thenReturn(response(200, """
                {
                  "model": "anthropic/claude-sonnet-4",
                  "choices": [{"message": {"content": "你好"}, "finish_reason": "stop"}],
                  "usage": {"prompt_tokens": 8, "completion_tokens": 3, "total_tokens": 11}
                }
                """));
        AiModelCatalog catalog = catalog("anthropic/claude-sonnet-4", Set.of("text"));
        OpenRouterService adapter = new OpenRouterService(config, httpClient, objectMapper, catalog);

        AiProviderResponse result = adapter.execute(AiProviderRequest.translate(
                "anthropic/claude-sonnet-4", "hello", "zh-TW",
                "business", "business-v1", "Use concise business terminology."));

        assertThat(captured.get().url().encodedPath()).isEqualTo("/api/v1/chat/completions");
        assertThat(captured.get().header("Authorization")).isEqualTo("Bearer test-key");
        JsonNode body = objectMapper.readTree(requestBody(captured.get()));
        assertThat(body.path("model").asText()).isEqualTo("anthropic/claude-sonnet-4");
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(0).path("content").asText())
                .contains("business-v1", "Use concise business terminology.");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("hello");
        assertThat(result.text()).isEqualTo("你好");
        assertThat(result.model()).isEqualTo("anthropic/claude-sonnet-4");
        assertThat(result.tokenUsage()).isEqualTo(new AiTokenUsage(8, 3, 11));
    }

    @Test
    void imageRequestUsesOpenRouterMultimodalContentContract() throws Exception {
        OpenRouterConfig config = config();
        OkHttpClient httpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        AtomicReference<Request> captured = new AtomicReference<>();
        when(httpClient.newCall(any(Request.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return call;
        });
        when(call.execute()).thenReturn(response(200, """
                {"model":"openai/gpt-4o-mini","choices":[{"message":{"content":"文字"}}]}
                """));
        OpenRouterService adapter = new OpenRouterService(
                config, httpClient, objectMapper, catalog("openai/gpt-4o-mini", Set.of("text", "image")));

        adapter.execute(AiProviderRequest.image(
                "openai/gpt-4o-mini", "讀取圖片", "data:image/jpeg;base64,AA=="));

        JsonNode content = objectMapper.readTree(requestBody(captured.get()))
                .path("messages").get(1).path("content");
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).path("image_url").path("url").asText())
                .isEqualTo("data:image/jpeg;base64,AA==");
    }

    @Test
    void structuredImageTranslationUsesStrictJsonSchemaResponseFormat() throws Exception {
        OpenRouterConfig config = config();
        OkHttpClient httpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        AtomicReference<Request> captured = new AtomicReference<>();
        when(httpClient.newCall(any(Request.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return call;
        });
        when(call.execute()).thenReturn(response(200, """
                {"model":"openai/gpt-4o-mini","choices":[{"message":{"content":"{\\\"schemaVersion\\\":\\\"image-regions-v1\\\",\\\"regions\\\":[{\\\"regionId\\\":\\\"r1\\\",\\\"translatedText\\\":\\\"你好\\\"}]}"}}]}
                """));
        OpenRouterService adapter = new OpenRouterService(
                config, httpClient, objectMapper, catalog("openai/gpt-4o-mini", Set.of("text")));
        String wire = "{\"schemaVersion\":\"image-regions-v1\",\"targetLanguage\":\"zh-TW\",\"regions\":[]}";

        adapter.execute(AiProviderRequest.translate("openai/gpt-4o-mini", wire, "zh-TW"));

        JsonNode body = objectMapper.readTree(requestBody(captured.get()));
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_schema");
        JsonNode schema = body.path("response_format").path("json_schema");
        assertThat(schema.path("strict").asBoolean()).isTrue();
        assertThat(schema.path("schema").path("properties").path("regions").path("type").asText())
                .isEqualTo("array");
        assertThat(body.path("temperature").asDouble()).isZero();
        assertThat(body.path("messages").get(0).path("content").asText())
                .contains("regionId", "protectedTokens");
    }

    @Test
    void providerErrorsBecomeTypedFailuresWithoutLeakingResponseText() throws IOException {
        OpenRouterConfig config = config();
        OkHttpClient httpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        when(httpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response(402, """
                {"error":{"code":402,"message":"secret billing detail",
                "metadata":{"error_type":"payment_required"}}}
                """));
        OpenRouterService adapter = new OpenRouterService(
                config, httpClient, objectMapper, catalog("openai/gpt-4o-mini", Set.of("text")));

        assertThatThrownBy(() -> adapter.execute(
                AiProviderRequest.generate("openai/gpt-4o-mini", "hello")))
                .isInstanceOfSatisfying(AiProviderException.class, failure -> {
                    assertThat(failure.getOutcome()).isEqualTo(AiProviderException.Outcome.QUOTA_EXCEEDED);
                    assertThat(failure.getHttpStatus()).isEqualTo(402);
                    assertThat(failure.getReason()).isEqualTo("payment_required");
                    assertThat(failure.getMessage()).doesNotContain("secret billing detail");
                });
    }

    @Test
    void authenticationAndRateLimitStatusesRemainDistinct() throws IOException {
        assertFailure(401, "authentication", AiProviderException.Outcome.AUTHENTICATION_FAILED);
        assertFailure(429, "rate_limit_exceeded", AiProviderException.Outcome.RATE_LIMITED);
    }

    @Test
    void malformedSuccessfulPayloadIsRejectedAsProviderDataFailure() throws IOException {
        OpenRouterConfig config = config();
        OkHttpClient httpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        when(httpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response(200, "not-json"));
        OpenRouterService adapter = new OpenRouterService(
                config, httpClient, objectMapper, catalog("openai/gpt-4o-mini", Set.of("text")));

        assertThatThrownBy(() -> adapter.execute(
                AiProviderRequest.generate("openai/gpt-4o-mini", "hello")))
                .isInstanceOfSatisfying(AiProviderException.class,
                        failure -> assertThat(failure.getOutcome())
                                .isEqualTo(AiProviderException.Outcome.MALFORMED_RESPONSE));
    }

    private void assertFailure(
            int status,
            String errorType,
            AiProviderException.Outcome expected) throws IOException {
        OpenRouterConfig config = config();
        OkHttpClient httpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        when(httpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response(status,
                "{\"error\":{\"code\":" + status
                        + ",\"metadata\":{\"error_type\":\"" + errorType + "\"}}}"));
        OpenRouterService adapter = new OpenRouterService(
                config, httpClient, objectMapper, catalog("openai/gpt-4o-mini", Set.of("text")));

        assertThatThrownBy(() -> adapter.execute(
                AiProviderRequest.generate("openai/gpt-4o-mini", "hello")))
                .isInstanceOfSatisfying(AiProviderException.class,
                        failure -> assertThat(failure.getOutcome()).isEqualTo(expected));
    }

    private OpenRouterConfig config() {
        OpenRouterConfig config = mock(OpenRouterConfig.class);
        when(config.getApiKey()).thenReturn("test-key");
        when(config.getModelName()).thenReturn("openai/gpt-4o-mini");
        when(config.getApiUrl()).thenReturn("https://openrouter.ai/api/v1");
        when(config.normalizedApiUrl()).thenReturn("https://openrouter.ai/api/v1");
        when(config.getHttpReferer()).thenReturn("https://github.com/AlienTechForge/LineBotTranslator");
        when(config.getAppTitle()).thenReturn("LineBotTranslator");
        return config;
    }

    private static AiModelCatalog catalog(String model, Set<String> inputModalities) {
        AiModelCatalog catalog = mock(AiModelCatalog.class);
        AiModelDescriptor descriptor = new AiModelDescriptor(
                model, model, inputModalities, Set.of("text"), null, null);
        when(catalog.find(model)).thenReturn(Optional.of(descriptor));
        when(catalog.contains(model)).thenReturn(true);
        return catalog;
    }

    private static Response response(int code, String body) {
        Request request = new Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").build();
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code >= 200 && code < 300 ? "OK" : "Error")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
    }

    private static String requestBody(Request request) throws IOException {
        okio.Buffer buffer = new okio.Buffer();
        request.body().writeTo(buffer);
        return buffer.readUtf8();
    }
}
