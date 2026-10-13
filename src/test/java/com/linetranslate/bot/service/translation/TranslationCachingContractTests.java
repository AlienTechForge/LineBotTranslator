package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Ticker;

import com.linetranslate.bot.config.OpenRouterConfig;
import com.linetranslate.bot.service.preference.UserPreferences;
import static com.linetranslate.bot.testing.UserPreferencesFixtures.preferences;
import com.linetranslate.bot.service.ai.AiExecutionFailure;
import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiModelCatalog;
import com.linetranslate.bot.service.ai.AiProviderException;
import com.linetranslate.bot.service.ai.AiProviderExecutionModule;
import com.linetranslate.bot.service.ai.AiProviderOperation;
import com.linetranslate.bot.service.ai.AiProviderRoute;
import com.linetranslate.bot.service.ai.AiTokenUsage;
import com.linetranslate.bot.service.ai.OpenRouterService;
import com.linetranslate.bot.service.settings.RuntimeSettings;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

class TranslationCachingContractTests {

    private AiProviderExecutionModule providerModule;
    private MutableTicker ticker;
    private SimpleMeterRegistry meterRegistry;
    private TranslationCacheProperties properties;
    private CaffeineTranslationCacheStore cacheStore;
    private CachedTranslationAdapter adapter;
    private UserPreferences profile;

    @BeforeEach
    void setUp() {
        providerModule = mock(AiProviderExecutionModule.class);
        ticker = new MutableTicker();
        meterRegistry = new SimpleMeterRegistry();
        properties = new TranslationCacheProperties();
        properties.setTtl(Duration.ofMinutes(10));
        properties.setMaxEntries(100);
        properties.setGlossaryVersion("none");
        cacheStore = new CaffeineTranslationCacheStore(properties, meterRegistry, ticker);
        adapter = new CachedTranslationAdapter(providerModule, cacheStore, properties);
        profile = preferences("openai", "gpt-a", "gemini-a");
        when(providerModule.planText(profile)).thenReturn(new AiProviderRoute("openai", "gpt-a"));
    }

    @Test
    void successfulTranslationHitAvoidsASecondProviderCall() {
        AiExecutionOutcome success = success("你好", "openai", "gpt-a", false);
        when(providerModule.translateTextOutcome(
                profile, "hello", "zh-TW", TranslationStylePreset.FAITHFUL))
                .thenReturn(success);

        assertThat(adapter.translate(profile, "hello", "zh-TW")).isEqualTo(success);
        assertThat(adapter.translate(profile, "hello", "zh-TW")).isEqualTo(success);

        verify(providerModule).translateTextOutcome(
                profile, "hello", "zh-TW", TranslationStylePreset.FAITHFUL);
        assertThat(cacheStore.stats().hitCount()).isEqualTo(1);
        assertThat(cacheStore.stats().missCount()).isEqualTo(1);
    }

    @Test
    void targetLocaleMismatchIsNeverCached() {
        AiExecutionOutcome simplified = success("保护自己免受热伤害", "openai", "gpt-a", false);
        AiExecutionOutcome traditional = success("保護自己免受熱傷害", "openai", "gpt-a", false);
        when(providerModule.translateTextOutcome(
                profile, "protect yourself", "zh-TW", TranslationStylePreset.FAITHFUL))
                .thenReturn(simplified, traditional);

        assertThat(adapter.translate(profile, "protect yourself", "zh-TW")).isEqualTo(simplified);
        assertThat(adapter.translate(profile, "protect yourself", "zh-TW")).isEqualTo(traditional);

        verify(providerModule, times(2)).translateTextOutcome(
                profile, "protect yourself", "zh-TW", TranslationStylePreset.FAITHFUL);
        assertThat(cacheStore.stats().missCount()).isEqualTo(2);
    }

    @Test
    void providerModelStyleGlossaryAndPromptVersionsAreIsolated() {
        when(providerModule.translateTextOutcome(
                org.mockito.ArgumentMatchers.eq(profile),
                org.mockito.ArgumentMatchers.eq("hello"),
                org.mockito.ArgumentMatchers.eq("zh-TW"),
                any(TranslationStylePreset.class)))
                .thenReturn(success("v1", "openai", "gpt-a", false))
                .thenReturn(success("v2", "gemini", "gemini-a", false))
                .thenReturn(success("v3", "openai", "gpt-b", false))
                .thenReturn(success("v4", "openai", "gpt-b", false))
                .thenReturn(success("v5", "openai", "gpt-b", false))
                .thenReturn(success("v6", "openai", "gpt-b", false));

        assertThat(adapter.translate(profile, "hello", "zh-TW",
                new TranslationCacheVariant("faithful", "none", "faithful-v1")))
                .isEqualTo(success("v1", "openai", "gpt-a", false));

        when(providerModule.planText(profile)).thenReturn(new AiProviderRoute("gemini", "gemini-a"));
        assertThat(adapter.translate(profile, "hello", "zh-TW",
                new TranslationCacheVariant("faithful", "none", "faithful-v1")))
                .isEqualTo(success("v2", "gemini", "gemini-a", false));

        when(providerModule.planText(profile)).thenReturn(new AiProviderRoute("openai", "gpt-b"));
        assertThat(adapter.translate(profile, "hello", "zh-TW",
                new TranslationCacheVariant("faithful", "none", "faithful-v1")))
                .isEqualTo(success("v3", "openai", "gpt-b", false));
        assertThat(adapter.translate(profile, "hello", "zh-TW",
                new TranslationCacheVariant("formal", "none", "formal-v1")))
                .isEqualTo(success("v4", "openai", "gpt-b", false));
        assertThat(adapter.translate(profile, "hello", "zh-TW",
                new TranslationCacheVariant("formal", "glossary-v2", "formal-v1")))
                .isEqualTo(success("v5", "openai", "gpt-b", false));
        assertThat(adapter.translate(profile, "hello", "zh-TW",
                new TranslationCacheVariant("formal", "glossary-v2", "formal-v2")))
                .isEqualTo(success("v6", "openai", "gpt-b", false));

        verify(providerModule, times(6)).translateTextOutcome(
                org.mockito.ArgumentMatchers.eq(profile),
                org.mockito.ArgumentMatchers.eq("hello"),
                org.mockito.ArgumentMatchers.eq("zh-TW"),
                any(TranslationStylePreset.class));
    }

    @Test
    void entryExpiresAfterConfiguredTtl() {
        when(providerModule.translateTextOutcome(
                profile, "hello", "zh-TW", TranslationStylePreset.FAITHFUL))
                .thenReturn(success("first", "openai", "gpt-a", false))
                .thenReturn(success("second", "openai", "gpt-a", false));

        assertThat(adapter.translate(profile, "hello", "zh-TW"))
                .isEqualTo(success("first", "openai", "gpt-a", false));
        ticker.advance(Duration.ofMinutes(11));
        assertThat(adapter.translate(profile, "hello", "zh-TW"))
                .isEqualTo(success("second", "openai", "gpt-a", false));

        verify(providerModule, times(2)).translateTextOutcome(
                profile, "hello", "zh-TW", TranslationStylePreset.FAITHFUL);
    }

    @Test
    void maximumCapacityEvictsEntries() {
        properties.setMaxEntries(2);
        meterRegistry = new SimpleMeterRegistry();
        cacheStore = new CaffeineTranslationCacheStore(properties, meterRegistry, ticker);
        adapter = new CachedTranslationAdapter(providerModule, cacheStore, properties);
        when(providerModule.translateTextOutcome(
                profile, "one", "zh-TW", TranslationStylePreset.FAITHFUL))
                .thenReturn(success("一", "openai", "gpt-a", false));
        when(providerModule.translateTextOutcome(
                profile, "two", "zh-TW", TranslationStylePreset.FAITHFUL))
                .thenReturn(success("二", "openai", "gpt-a", false));
        when(providerModule.translateTextOutcome(
                profile, "three", "zh-TW", TranslationStylePreset.FAITHFUL))
                .thenReturn(success("三", "openai", "gpt-a", false));

        adapter.translate(profile, "one", "zh-TW");
        adapter.translate(profile, "two", "zh-TW");
        adapter.translate(profile, "three", "zh-TW");
        cacheStore.cleanUp();

        assertThat(cacheStore.estimatedSize()).isLessThanOrEqualTo(2);
        assertThat(cacheStore.stats().evictionCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void failuresSafetyBlocksAndFallbackResultsAreNeverCached() {
        AiExecutionOutcome failure = failure(AiProviderException.Outcome.TRANSPORT_ERROR);
        AiExecutionOutcome blocked = failure(AiProviderException.Outcome.SAFETY_BLOCKED);
        AiExecutionOutcome fallback = success("fallback", "gemini", "gemini-a", true);
        when(providerModule.translateTextOutcome(
                profile, "failure", "zh-TW", TranslationStylePreset.FAITHFUL))
                .thenReturn(failure);
        when(providerModule.translateTextOutcome(
                profile, "blocked", "zh-TW", TranslationStylePreset.FAITHFUL))
                .thenReturn(blocked);
        when(providerModule.translateTextOutcome(
                profile, "fallback", "zh-TW", TranslationStylePreset.FAITHFUL))
                .thenReturn(fallback);

        adapter.translate(profile, "failure", "zh-TW");
        adapter.translate(profile, "failure", "zh-TW");
        adapter.translate(profile, "blocked", "zh-TW");
        adapter.translate(profile, "blocked", "zh-TW");
        adapter.translate(profile, "fallback", "zh-TW");
        adapter.translate(profile, "fallback", "zh-TW");

        verify(providerModule, times(2)).translateTextOutcome(
                profile, "failure", "zh-TW", TranslationStylePreset.FAITHFUL);
        verify(providerModule, times(2)).translateTextOutcome(
                profile, "blocked", "zh-TW", TranslationStylePreset.FAITHFUL);
        verify(providerModule, times(2)).translateTextOutcome(
                profile, "fallback", "zh-TW", TranslationStylePreset.FAITHFUL);
        assertThat(cacheStore.estimatedSize()).isZero();
    }

    @Test
    void completionTruncatedByMaxTokensIsANormalizedFailureAndIsNeverCached() throws Exception {
        String model = "openai/gpt-4o-mini";
        OpenRouterConfig config = mock(OpenRouterConfig.class);
        when(config.getApiKey()).thenReturn("test-key");
        when(config.getModelName()).thenReturn(model);
        when(config.normalizedApiUrl()).thenReturn("https://openrouter.ai/api/v1");
        AiModelCatalog catalog = mock(AiModelCatalog.class);
        when(catalog.modelIds()).thenReturn(Set.of(model));
        when(catalog.supports(model, AiProviderOperation.TRANSLATE_TEXT)).thenReturn(true);
        OkHttpClient httpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        when(httpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenAnswer(invocation -> openRouterResponse("""
                {"model":"openai/gpt-4o-mini",
                 "choices":[{"message":{"content":"你好，這段譯文在中途被截"},"finish_reason":"length"}],
                 "usage":{"prompt_tokens":12,"completion_tokens":1024,"total_tokens":1036}}
                """));
        AiProviderExecutionModule executionModule = new AiProviderExecutionModule(
                List.of(new OpenRouterService(config, httpClient, new ObjectMapper(), catalog)),
                () -> new RuntimeSettings("en", "zh-TW", model, true,
                        2, 1, null, "U-admin", RuntimeSettings.Source.PERSISTED),
                (operation, outcome) -> { });
        CachedTranslationAdapter realChain = new CachedTranslationAdapter(executionModule, cacheStore, properties);
        UserPreferences preferences = new UserPreferences("en", "en", "en", model, List.of());

        AiExecutionOutcome first = realChain.translate(preferences, "hello", "zh-TW");
        AiExecutionOutcome second = realChain.translate(preferences, "hello", "zh-TW");

        assertThat(List.of(first, second)).allSatisfy(outcome -> assertThat(outcome)
                .isInstanceOfSatisfying(AiExecutionOutcome.Failure.class, failure -> {
                    assertThat(failure.failure().outcome())
                            .isEqualTo(AiProviderException.Outcome.MALFORMED_RESPONSE);
                    assertThat(failure.failure().reason()).isEqualTo("TRUNCATED_OUTPUT");
                }));
        verify(httpClient, times(2)).newCall(any(Request.class));
        assertThat(cacheStore.estimatedSize()).isZero();
        assertThat(cacheStore.stats().hitCount()).isZero();
    }

    @Test
    void nonFallbackRouteMismatchIsNeverCached() {
        AiExecutionOutcome unexpectedRoute = success("unexpected", "gemini", "gemini-a", false);
        when(providerModule.translateTextOutcome(
                profile, "hello", "zh-TW", TranslationStylePreset.FAITHFUL))
                .thenReturn(unexpectedRoute);

        adapter.translate(profile, "hello", "zh-TW");
        adapter.translate(profile, "hello", "zh-TW");

        verify(providerModule, times(2)).translateTextOutcome(
                profile, "hello", "zh-TW", TranslationStylePreset.FAITHFUL);
        assertThat(cacheStore.estimatedSize()).isZero();
    }

    @Test
    void providerResolvedModelIsTheStoredKeyAndCanBeReadThroughThePlannedAlias() {
        when(providerModule.planText(profile)).thenReturn(new AiProviderRoute("openai", "gpt-alias"));
        AiExecutionOutcome resolved = success("resolved", "openai", "gpt-versioned", false);
        when(providerModule.translateTextOutcome(
                profile, "hello", "zh-TW", TranslationStylePreset.FAITHFUL))
                .thenReturn(resolved);

        assertThat(adapter.translate(profile, "hello", "zh-TW")).isEqualTo(resolved);
        assertThat(adapter.translate(profile, "hello", "zh-TW")).isEqualTo(resolved);

        verify(providerModule).translateTextOutcome(
                profile, "hello", "zh-TW", TranslationStylePreset.FAITHFUL);
        assertThat(cacheStore.keys())
                .singleElement()
                .extracting(TranslationCacheKey::model)
                .isEqualTo("gpt-versioned");
    }

    @Test
    void keysAndMetricsNeverContainUserText() {
        String sensitiveText = "private-user-message-123";
        when(providerModule.translateTextOutcome(
                profile, sensitiveText, "zh-TW", TranslationStylePreset.FAITHFUL))
                .thenReturn(success("安全", "openai", "gpt-a", false));

        adapter.translate(profile, sensitiveText, "zh-TW");
        adapter.translate(profile, sensitiveText, "zh-TW");

        assertThat(cacheStore.keys())
                .allSatisfy(key -> assertThat(key.toString()).doesNotContain(sensitiveText));
        assertThat(meterRegistry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().toString()).doesNotContain(sensitiveText));
    }

    private static AiExecutionOutcome success(
            String text,
            String provider,
            String model,
            boolean fallbackUsed) {
        return new AiExecutionOutcome.Success(new AiExecutionResult(
                text,
                provider,
                model,
                AiTokenUsage.UNKNOWN,
                1,
                fallbackUsed,
                List.of()));
    }

    private static AiExecutionOutcome failure(AiProviderException.Outcome outcome) {
        AiProviderException error = new AiProviderException(
                outcome,
                "openai",
                "gpt-a",
                outcome.name(),
                "correlation-1",
                -1,
                null);
        return new AiExecutionOutcome.Failure(AiExecutionFailure.from(error, List.of()));
    }

    private static Response openRouterResponse(String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
    }

    private static final class MutableTicker implements Ticker {

        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
