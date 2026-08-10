package com.linetranslate.bot.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.linetranslate.bot.config.AppConfig;
import com.linetranslate.bot.config.GeminiConfig;
import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.translation.AiLanguageDetectionService;
import com.linetranslate.bot.service.translation.LanguageDetectionService;
import com.linetranslate.bot.service.translation.TranslationService;

class GeminiSafetyResponseTests {

    private GeminiService geminiService;

    @BeforeEach
    void setUp() {
        GeminiConfig config = Mockito.mock(GeminiConfig.class);
        when(config.getModelName()).thenReturn("gemini-test");
        when(config.getApiKey()).thenReturn("test-key");
        geminiService = new GeminiService(config);
    }

    @Test
    void promptSafetyBlockBecomesTypedOutcome() {
        String response = """
                {
                  "promptFeedback": { "blockReason": "PROHIBITED_CONTENT" },
                  "usageMetadata": { "promptTokenCount": 46 }
                }
                """;

        assertThatExceptionOfType(AiProviderException.class)
                .isThrownBy(() -> geminiService.parseGeneratedText(response, "correlation-1"))
                .satisfies(error -> {
                    assertThat(error.getOutcome())
                            .isEqualTo(AiProviderException.Outcome.SAFETY_BLOCKED);
                    assertThat(error.getReason()).isEqualTo("PROHIBITED_CONTENT");
                    assertThat(error.getProvider()).isEqualTo("gemini");
                    assertThat(error.getModel()).isEqualTo("gemini-test");
                    assertThat(error.getCorrelationId()).isEqualTo("correlation-1");
                    assertThat(error.getMessage()).doesNotContain(response);
                });
    }

    @Test
    void candidateSafetyFinishReasonIsCheckedBeforeText() {
        String response = """
                {
                  "candidates": [{
                    "finishReason": "SAFETY",
                    "content": { "parts": [{ "text": "must-not-be-returned" }] }
                  }]
                }
                """;

        assertThatExceptionOfType(AiProviderException.class)
                .isThrownBy(() -> geminiService.parseGeneratedText(response, "correlation-2"))
                .satisfies(error -> {
                    assertThat(error.getOutcome())
                            .isEqualTo(AiProviderException.Outcome.SAFETY_BLOCKED);
                    assertThat(error.getReason()).isEqualTo("SAFETY");
                });
    }

    @Test
    void emptyCandidatesBecomeTypedEmptyOutcome() {
        assertThatExceptionOfType(AiProviderException.class)
                .isThrownBy(() -> geminiService.parseGeneratedText(
                        "{ \"promptFeedback\": {}, \"candidates\": [] }",
                        "correlation-3"))
                .satisfies(error -> assertThat(error.getOutcome())
                        .isEqualTo(AiProviderException.Outcome.EMPTY_RESPONSE));
    }

    @Test
    void malformedJsonBecomesTypedMalformedOutcome() {
        assertThatExceptionOfType(AiProviderException.class)
                .isThrownBy(() -> geminiService.parseGeneratedText("not-json", "correlation-4"))
                .satisfies(error -> assertThat(error.getOutcome())
                        .isEqualTo(AiProviderException.Outcome.MALFORMED_RESPONSE));
    }

    @Test
    void normalCandidateReturnsText() {
        String response = """
                {
                  "candidates": [{
                    "finishReason": "STOP",
                    "content": { "parts": [{ "text": "en" }] }
                  }]
                }
                """;

        assertThat(geminiService.parseGeneratedText(response, "correlation-5")).isEqualTo("en");
    }

    @Test
    void resourceExhaustedHttpResponseBecomesQuotaOutcome() {
        AiProviderException failure = geminiService.providerHttpFailure(
                429,
                "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"billing details\"}}",
                "correlation-6");

        assertThat(failure.getOutcome()).isEqualTo(AiProviderException.Outcome.QUOTA_EXCEEDED);
        assertThat(failure.getReason()).isEqualTo("RESOURCE_EXHAUSTED");
        assertThat(failure.getHttpStatus()).isEqualTo(429);
        assertThat(failure.getMessage()).doesNotContain("billing details");
    }

    @Test
    void unauthorizedHttpResponseBecomesAuthenticationOutcome() {
        AiProviderException failure = geminiService.providerHttpFailure(
                401,
                "{\"error\":{\"status\":\"UNAUTHENTICATED\"}}",
                "correlation-7");

        assertThat(failure.getOutcome())
                .isEqualTo(AiProviderException.Outcome.AUTHENTICATION_FAILED);
        assertThat(failure.getReason()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void safetyBlockedDetectionFallsBackLocallyWithoutRetry() {
        AtomicInteger providerCalls = new AtomicInteger();
        LanguageDetectionService detector = languageDetector(blockedModule(providerCalls));

        assertThat(detector.detectLanguage("這是一段中文內容")).isEqualTo("zh-tw");
        assertThat(providerCalls).hasValue(1);
    }

    @Test
    void blockedDetectionStillAllowsTranslationThroughAllowedProvider() {
        AtomicInteger providerCalls = new AtomicInteger();
        AiProviderExecutionModule module = blockedModule(providerCalls);
        when(module.translateText(
                Mockito.any(UserProfile.class),
                Mockito.eq("這是一段中文內容"),
                Mockito.eq("en")))
                .thenReturn(new AiExecutionResult("translated", "openai", "gpt-test"));
        LanguageDetectionService detector = languageDetector(module);

        UserProfileRepository userRepository = Mockito.mock(UserProfileRepository.class);
        TranslationRecordRepository recordRepository = Mockito.mock(TranslationRecordRepository.class);
        AppConfig appConfig = Mockito.mock(AppConfig.class);
        UserProfile profile = UserProfile.builder()
                .userId("U-test")
                .preferredAiProvider("openai")
                .build();
        when(userRepository.findByUserId("U-test")).thenReturn(Optional.of(profile));
        when(userRepository.save(Mockito.any(UserProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(appConfig.getDefaultTargetLanguageForChinese()).thenReturn("en");

        TranslationService translationService = new TranslationService(
                detector,
                module,
                recordRepository,
                userRepository,
                appConfig);

        String translated = translationService.processTranslationRequest(
                "U-test", "這是一段中文內容");

        assertThat(translated).startsWith("translated");
        assertThat(providerCalls).hasValue(1);
        org.mockito.ArgumentCaptor<TranslationRecord> captor =
                org.mockito.ArgumentCaptor.forClass(TranslationRecord.class);
        Mockito.verify(recordRepository).save(captor.capture());
        assertThat(captor.getValue().getSourceLanguage()).isEqualTo("zh-tw");
        assertThat(captor.getValue().getAiProvider()).isEqualTo("openai");
    }

    @Test
    void providerErrorTextCannotBecomePlausibleLanguageCode() {
        AiProviderExecutionModule module = Mockito.mock(AiProviderExecutionModule.class);
        when(module.generateTextOutcome(anyString(), anyString(), anyString()))
                .thenReturn(new AiExecutionOutcome.Success(new AiExecutionResult(
                        "文本生成失敗: Gemini API 請求錯誤 404",
                        "gemini",
                        "gemini-test")));
        AiLanguageDetectionService detector = new AiLanguageDetectionService(module);
        ReflectionTestUtils.setField(detector, "aiProvider", "gemini");
        ReflectionTestUtils.setField(detector, "modelName", "gemini-test");
        ReflectionTestUtils.setField(detector, "defaultChineseType", "zh-tw");

        assertThat(detector.detectLanguage("prompt")).isEqualTo("unknown");
    }

    @Test
    void providerMetadataIsSafeForStructuredLogs() {
        AiProviderException error = new AiProviderException(
                AiProviderException.Outcome.SAFETY_BLOCKED,
                "gemini\nforged",
                "gemini-test",
                "PROHIBITED_CONTENT\nraw-response",
                "correlation-1",
                -1,
                null);

        assertThat(error.getProvider()).isEqualTo("gemini_forged");
        assertThat(error.getReason()).isEqualTo("PROHIBITED_CONTENT_raw-response");
        assertThat(error.getMessage()).doesNotContain("raw-response");
    }

    private LanguageDetectionService languageDetector(AiProviderExecutionModule module) {
        AiLanguageDetectionService aiDetector = new AiLanguageDetectionService(module);
        ReflectionTestUtils.setField(aiDetector, "aiProvider", "gemini");
        ReflectionTestUtils.setField(aiDetector, "modelName", "gemini-test");
        ReflectionTestUtils.setField(aiDetector, "defaultChineseType", "zh-tw");

        LanguageDetectionService detector = new LanguageDetectionService();
        ReflectionTestUtils.setField(detector, "aiLanguageDetectionService", aiDetector);
        ReflectionTestUtils.setField(detector, "useAiDetection", true);
        ReflectionTestUtils.setField(detector, "defaultChineseType", "zh-tw");
        detector.init();
        return detector;
    }

    private AiProviderExecutionModule blockedModule(AtomicInteger calls) {
        AiProviderExecutionModule module = Mockito.mock(AiProviderExecutionModule.class);
        when(module.generateTextOutcome(eq("gemini"), anyString(), anyString())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            AiProviderException error = new AiProviderException(
                    AiProviderException.Outcome.SAFETY_BLOCKED,
                    "gemini",
                    "gemini-test",
                    "PROHIBITED_CONTENT",
                    "correlation-blocked",
                    -1,
                    null);
            AiProviderAttempt attempt = AiProviderAttempt.failure(error, 1);
            return new AiExecutionOutcome.Failure(AiExecutionFailure.from(error, List.of(attempt)));
        });
        return module;
    }
}
