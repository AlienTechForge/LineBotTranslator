package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderExecutionModule;
import com.linetranslate.bot.service.ai.AiProviderRoute;
import com.linetranslate.bot.service.preference.UserPreferences;
import com.linetranslate.bot.service.preference.UserPreferencesModule;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class LanguageDetectionCacheTests {

    private static final String DETECTION_MODEL = "openai/gpt-4o-mini";

    private SimpleMeterRegistry meterRegistry;
    private LanguageDetectionCache detectionCache;
    private AiLanguageDetectionService aiDetector;
    private LanguageDetectionService detectionService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        detectionCache = new LanguageDetectionCache(new TranslationCacheProperties(), meterRegistry);
        aiDetector = mock(AiLanguageDetectionService.class);
        detectionService = detectionService(aiDetector);
    }

    @Test
    void sameTextTwiceCallsTheAiDetectorOnce() {
        when(aiDetector.detectLanguage("hello")).thenReturn("en");

        assertThat(detectionService.detectLanguage("hello")).isEqualTo("en");
        assertThat(detectionService.detectLanguage("hello")).isEqualTo("en");

        verify(aiDetector, times(1)).detectLanguage("hello");
        assertThat(requests("hit")).isEqualTo(1);
        assertThat(requests("miss")).isEqualTo(1);
    }

    @Test
    void unknownAiAnswerIsNotRememberedSoTheNextRequestAsksAgain() {
        when(aiDetector.detectLanguage("hello")).thenReturn("unknown", "en");
        detectionService.init(); // the first answer falls back to the local detector

        detectionService.detectLanguage("hello");
        assertThat(detectionCache.keys()).isEmpty();
        assertThat(detectionService.detectLanguage("hello")).isEqualTo("en");

        verify(aiDetector, times(2)).detectLanguage("hello");
        assertThat(detectionCache.keys()).hasSize(1);
    }

    @Test
    void differentTextsAreRememberedSeparately() {
        when(aiDetector.detectLanguage("hello")).thenReturn("en");
        when(aiDetector.detectLanguage("bonjour")).thenReturn("fr");

        assertThat(detectionService.detectLanguage("hello")).isEqualTo("en");
        assertThat(detectionService.detectLanguage("bonjour")).isEqualTo("fr");
        assertThat(detectionService.detectLanguage("hello")).isEqualTo("en");
        assertThat(detectionService.detectLanguage("bonjour")).isEqualTo("fr");

        verify(aiDetector, times(1)).detectLanguage("hello");
        verify(aiDetector, times(1)).detectLanguage("bonjour");
        assertThat(detectionCache.keys()).hasSize(2);
    }

    @Test
    void keysAndMetricsNeverContainUserText() {
        String sensitiveText = "private-user-message-123";
        when(aiDetector.detectLanguage(sensitiveText)).thenReturn("en");

        detectionService.detectLanguage(sensitiveText);
        detectionService.detectLanguage(sensitiveText);

        assertThat(detectionCache.keys())
                .singleElement()
                .satisfies(key -> assertThat(key).doesNotContain(sensitiveText).hasSize(64));
        assertThat(meterRegistry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().toString()).doesNotContain(sensitiveText));
    }

    @Test
    void repeatedTextPaysNoProviderAttemptOnTheSecondMessage() {
        AiProviderExecutionModule providerModule = mock(AiProviderExecutionModule.class);
        UserPreferences preferences = new UserPreferences(
                "zh-TW", "en", "zh-TW", DETECTION_MODEL, List.of());
        when(providerModule.generateTextOutcome(eq(DETECTION_MODEL), anyString()))
                .thenReturn(new AiExecutionOutcome.Success(
                        new AiExecutionResult("en", "openai", "gpt-4o-mini")));
        when(providerModule.planText(preferences)).thenReturn(new AiProviderRoute("openai", "gpt-a"));
        when(providerModule.translateTextOutcome(
                preferences, "hello", "zh-TW", TranslationStylePreset.FAITHFUL))
                .thenReturn(new AiExecutionOutcome.Success(
                        new AiExecutionResult("你好", "openai", "gpt-a")));
        AiLanguageDetectionService realAiDetector = new AiLanguageDetectionService(
                providerModule, new TranslationPromptFactory());
        ReflectionTestUtils.setField(realAiDetector, "modelName", DETECTION_MODEL);
        ReflectionTestUtils.setField(realAiDetector, "defaultChineseType", "zh-tw");
        UserPreferencesModule preferencesModule = mock(UserPreferencesModule.class);
        when(preferencesModule.resolve(any(UserProfile.class))).thenReturn(preferences);
        TranslationRecordRepository recordRepository = mock(TranslationRecordRepository.class);
        when(recordRepository.save(any(TranslationRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        TranslationCacheProperties properties = new TranslationCacheProperties();
        TranslationWorkflowModule workflow = new TranslationWorkflowModule(
                detectionService(realAiDetector),
                new CachedTranslationAdapter(providerModule,
                        new CaffeineTranslationCacheStore(properties, meterRegistry), properties),
                recordRepository,
                preferencesModule);

        TranslationWorkflowOutcome first = workflow.execute(request());
        TranslationWorkflowOutcome second = workflow.execute(request());

        verify(providerModule, times(1)).generateTextOutcome(eq(DETECTION_MODEL), anyString());
        verify(providerModule, times(1)).translateTextOutcome(
                preferences, "hello", "zh-TW", TranslationStylePreset.FAITHFUL);
        assertThat(List.of(first, second)).allSatisfy(outcome -> {
            TranslationWorkflowResult result = ((TranslationWorkflowOutcome.Success) outcome).result();
            assertThat(result.sourceLanguage()).isEqualTo("en");
            assertThat(result.translatedText()).isEqualTo("你好");
        });
    }

    private LanguageDetectionService detectionService(AiLanguageDetectionService ai) {
        LanguageDetectionService service = new LanguageDetectionService();
        ReflectionTestUtils.setField(service, "aiLanguageDetectionService", ai);
        ReflectionTestUtils.setField(service, "detectionCache", detectionCache);
        ReflectionTestUtils.setField(service, "useAiDetection", true);
        ReflectionTestUtils.setField(service, "defaultChineseType", "zh-tw");
        return service;
    }

    private double requests(String result) {
        return meterRegistry.get("language.detection.cache.requests")
                .tag("result", result)
                .counter()
                .count();
    }

    private static TranslationWorkflowRequest request() {
        return new TranslationWorkflowRequest(
                UserProfile.builder().userId("U-test").build(),
                "hello",
                null,
                TranslationRequestKind.STANDARD_TEXT,
                null,
                null,
                Instant.now());
    }
}
