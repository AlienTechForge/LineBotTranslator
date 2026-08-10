package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linetranslate.bot.config.AppConfig;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderException;
import com.linetranslate.bot.service.ai.AiServiceFactory;

class TranslationCachingContractTests {

    private AnnotationConfigApplicationContext context;
    private TranslationService translationService;
    private AiServiceFactory aiServiceFactory;
    private UserProfile profile;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(CacheTestConfiguration.class);
        translationService = context.getBean(TranslationService.class);
        aiServiceFactory = context.getBean(AiServiceFactory.class);
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
        AiExecutionResult success = new AiExecutionResult("你好", "openai", "gpt-test");
        when(aiServiceFactory.translateText(profile, "hello", "zh-TW")).thenReturn(success);

        assertThat(translationService.translateWithService(profile, "hello", "zh-TW"))
                .isEqualTo(success);
        assertThat(translationService.translateWithService(profile, "hello", "zh-TW"))
                .isEqualTo(success);

        verify(aiServiceFactory).translateText(profile, "hello", "zh-TW");
    }

    @Test
    void failedTranslationIsNotCached() {
        AiProviderException failure = new AiProviderException(
                AiProviderException.Outcome.TRANSPORT_ERROR,
                "gemini",
                "gemini-test",
                "IO_FAILURE",
                "correlation-1",
                -1,
                null);
        AiExecutionResult recovered = new AiExecutionResult("你好", "gemini", "gemini-test");
        when(aiServiceFactory.translateText(profile, "hello", "zh-TW"))
                .thenThrow(failure)
                .thenReturn(recovered);

        assertThatExceptionOfType(AiProviderException.class)
                .isThrownBy(() -> translationService.translateWithService(profile, "hello", "zh-TW"));
        assertThat(translationService.translateWithService(profile, "hello", "zh-TW"))
                .isEqualTo(recovered);

        verify(aiServiceFactory, times(2)).translateText(profile, "hello", "zh-TW");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class CacheTestConfiguration {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("translations");
        }

        @Bean
        AiServiceFactory aiServiceFactory() {
            return mock(AiServiceFactory.class);
        }

        @Bean
        TranslationService translationService(AiServiceFactory aiServiceFactory) {
            return new TranslationService(
                    mock(LanguageDetectionService.class),
                    aiServiceFactory,
                    mock(TranslationRecordRepository.class),
                    mock(UserProfileRepository.class),
                    mock(AppConfig.class));
        }
    }
}
