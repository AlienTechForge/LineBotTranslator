package com.linetranslate.bot.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linetranslate.bot.config.OpenRouterConfig;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

class OpenRouterModelCatalogTests {

    @Test
    void catalogFiltersTextModelsSearchesAndCachesOfficialPricing() throws IOException {
        OpenRouterConfig config = mock(OpenRouterConfig.class);
        when(config.getApiKey()).thenReturn("test-key");
        when(config.getModelName()).thenReturn("openai/gpt-4o-mini");
        when(config.getApiUrl()).thenReturn("https://openrouter.ai/api/v1");
        when(config.normalizedApiUrl()).thenReturn("https://openrouter.ai/api/v1");
        when(config.getCatalogTtl()).thenReturn(java.time.Duration.ofMinutes(15));
        OkHttpClient client = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        when(client.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response("""
                {"data":[
                  {"id":"anthropic/claude-sonnet-4","name":"Claude Sonnet 4",
                   "architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},
                   "pricing":{"prompt":"0.000003","completion":"0.000015"}},
                  {"id":"openai/gpt-image-1","name":"GPT Image",
                   "architecture":{"input_modalities":["text"],"output_modalities":["image"]},
                   "pricing":{"prompt":"0.1","completion":"0.2"}}
                ]}
                """));
        OpenRouterModelCatalog catalog = new OpenRouterModelCatalog(
                config,
                client,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC));

        AiModelPage first = catalog.list("claude", 20);
        AiModelPage second = catalog.list("", 20);

        assertThat(first.total()).isEqualTo(1);
        assertThat(first.models()).singleElement().satisfies(model -> {
            assertThat(model.id()).isEqualTo("anthropic/claude-sonnet-4");
            assertThat(model.inputModalities()).containsExactlyInAnyOrder("text", "image");
            assertThat(model.promptPricePerToken()).isEqualByComparingTo(new BigDecimal("0.000003"));
        });
        assertThat(second.models()).extracting(AiModelDescriptor::id)
                .containsExactly("anthropic/claude-sonnet-4");
        assertThat(catalog.supports("anthropic/claude-sonnet-4", AiProviderOperation.PROCESS_IMAGE)).isTrue();
        assertThat(catalog.supports("anthropic/claude-sonnet-4", AiProviderOperation.TRANSLATE_TEXT)).isTrue();
        verify(client, times(1)).newCall(any(Request.class));
    }

    private static Response response(String body) {
        Request request = new Request.Builder().url("https://openrouter.ai/api/v1/models").build();
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
    }
}
