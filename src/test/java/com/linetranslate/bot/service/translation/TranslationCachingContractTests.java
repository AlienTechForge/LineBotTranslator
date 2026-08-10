package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.service.ai.AiExecutionFailure;
import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderException;
import com.linetranslate.bot.service.ai.AiProviderExecutionModule;

class TranslationCachingContractTests {

    private AnnotationConfigApplicationContext context;
    private CachedTranslationAdapter adapter;
    private AiProviderExecutionModule providerModule;
    private UserProfile profile;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(CacheTestConfiguration.class);
        adapter = context.getBean(CachedTranslationAdapter.class);
        providerModule = context.getBean(AiProviderExecutionModule.class);
        profile = UserProfile.builder()
                .userId("U-test")
                .preferredAiProvider("openai")
                .build();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void successfulTranslationIsCached() {
        AiExecutionOutcome success = new AiExecutionOutcome.Success(
                new AiExecutionResult("你好", "openai", "gpt-test"));
        when(providerModule.translateTextOutcome(profile, "hello", "zh-TW"))
                .thenReturn(success);

        assertThat(adapter.translate(profile, "hello", "zh-TW")).isEqualTo(success);
        assertThat(adapter.translate(profile, "hello", "zh-TW")).isEqualTo(success);

        verify(providerModule).translateTextOutcome(profile, "hello", "zh-TW");
    }

    @Test
    void failedTranslationIsNotCached() {
        AiProviderException error = new AiProviderException(
                AiProviderException.Outcome.TRANSPORT_ERROR,
                "gemini",
                "gemini-test",
                "IO_FAILURE",
                "correlation-1",
                -1,
                null);
        AiExecutionOutcome failure = new AiExecutionOutcome.Failure(
                AiExecutionFailure.from(error, List.of()));
        AiExecutionOutcome recovered = new AiExecutionOutcome.Success(
                new AiExecutionResult("你好", "gemini", "gemini-test"));
        when(providerModule.translateTextOutcome(profile, "hello", "zh-TW"))
                .thenReturn(failure)
                .thenReturn(recovered);

        assertThat(adapter.translate(profile, "hello", "zh-TW")).isEqualTo(failure);
        assertThat(adapter.translate(profile, "hello", "zh-TW")).isEqualTo(recovered);

        verify(providerModule, times(2)).translateTextOutcome(profile, "hello", "zh-TW");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class CacheTestConfiguration {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("translations");
        }

        @Bean
        AiProviderExecutionModule providerModule() {
            return mock(AiProviderExecutionModule.class);
        }

        @Bean
        CachedTranslationAdapter cachedTranslationAdapter(
                AiProviderExecutionModule providerModule) {
            return new CachedTranslationAdapter(providerModule);
        }
    }
}
