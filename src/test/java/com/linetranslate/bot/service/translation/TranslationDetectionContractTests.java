package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.linetranslate.bot.config.AppConfig;
import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderException;
import com.linetranslate.bot.service.ai.AiServiceFactory;
import com.linetranslate.bot.util.LanguageUtils;

@ExtendWith(MockitoExtension.class)
class TranslationDetectionContractTests {

    private static final String USER_ID = "U-test";

    @Mock
    private LanguageDetectionService languageDetectionService;
    @Mock
    private AiServiceFactory aiServiceFactory;
    @Mock
    private TranslationRecordRepository translationRecordRepository;
    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private AppConfig appConfig;
    private TranslationService translationService;

    @BeforeEach
    void setUp() {
        translationService = createService(languageDetectionService);
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile()));
        when(userProfileRepository.save(any(UserProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(aiServiceFactory.translateText(any(UserProfile.class), anyString(), anyString()))
                .thenReturn(new AiExecutionResult("翻譯結果", "openai", "gpt-test"));
        lenient().when(appConfig.getDefaultTargetLanguageForOthers()).thenReturn("zh-TW");
    }

    @Test
    void defaultTranslationDetectsOnceAndReusesLanguageEverywhere() {
        when(languageDetectionService.detectLanguage("hello")).thenReturn("en");

        String response = translationService.processTranslationRequest(USER_ID, "hello");

        verify(languageDetectionService, times(1)).detectLanguage("hello");
        TranslationRecord saved = savedRecord();
        assertThat(saved.getSourceLanguage()).isEqualTo("en");
        assertThat(saved.getTargetLanguage()).isEqualTo("zh-TW");
        assertThat(response)
                .contains("偵測到: " + LanguageUtils.toChineseName("en"))
                .contains("翻譯成: " + LanguageUtils.toChineseName("zh-TW"));
    }

    @ParameterizedTest(name = "explicit format: {0}")
    @MethodSource("explicitTargetRequests")
    void explicitTargetFormatsDetectExactlyOnce(String request, String expectedTarget) {
        when(languageDetectionService.detectLanguage("hello")).thenReturn("en");

        translationService.processTranslationRequest(USER_ID, request);

        verify(languageDetectionService, times(1)).detectLanguage("hello");
        TranslationRecord saved = savedRecord();
        assertThat(saved.getSourceLanguage()).isEqualTo("en");
        assertThat(saved.getTargetLanguage()).isEqualTo(expectedTarget);
    }

    @Test
    void quickTranslationDetectsExactlyOnceAndPersistsIt() {
        when(languageDetectionService.detectLanguage("hello")).thenReturn("en");

        translationService.quickTranslate(USER_ID, "hello", "ja");

        verify(languageDetectionService, times(1)).detectLanguage("hello");
        assertThat(savedRecord().getSourceLanguage()).isEqualTo("en");
    }

    @Test
    void batchTranslationDetectsWholeBatchExactlyOnceAndPersistsIt() {
        String batch = "hello\nworld";
        when(languageDetectionService.detectLanguage(batch)).thenReturn("en");

        translationService.processBatchTranslation(USER_ID, batch);

        verify(languageDetectionService, times(1)).detectLanguage(batch);
        assertThat(savedRecord().getSourceLanguage()).isEqualTo("en");
    }

    @Test
    void singleLineBatchDelegatesWithoutAddingAnotherDetection() {
        when(languageDetectionService.detectLanguage("hello")).thenReturn("en");

        translationService.processBatchTranslation(USER_ID, "hello");

        verify(languageDetectionService, times(1)).detectLanguage("hello");
    }

    @Test
    void aiDetectionProviderIsCalledOnceForOrdinaryRequest() {
        AiLanguageDetectionService aiDetector = org.mockito.Mockito.mock(AiLanguageDetectionService.class);
        when(aiDetector.detectLanguage("hello")).thenReturn("en");

        LanguageDetectionService realDetectionService = new LanguageDetectionService();
        ReflectionTestUtils.setField(realDetectionService, "aiLanguageDetectionService", aiDetector);
        ReflectionTestUtils.setField(realDetectionService, "useAiDetection", true);
        TranslationService service = createService(realDetectionService);

        service.processTranslationRequest(USER_ID, "hello");

        verify(aiDetector, times(1)).detectLanguage("hello");
    }

    @Test
    void fallbackSuccessPersistsTheProviderThatActuallyTranslated() {
        when(languageDetectionService.detectLanguage("hello")).thenReturn("en");
        when(aiServiceFactory.translateText(any(UserProfile.class), anyString(), anyString()))
                .thenReturn(new AiExecutionResult("翻譯結果", "gemini", "gemini-test"));

        translationService.processTranslationRequest(USER_ID, "hello");

        TranslationRecord saved = savedRecord();
        assertThat(saved.getAiProvider()).isEqualTo("gemini");
        assertThat(saved.getModelName()).isEqualTo("gemini-test");
    }

    @Test
    void totalProviderFailureReturnsStableMessageWithoutSavingOrCounting() {
        UserProfile profile = profile();
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(languageDetectionService.detectLanguage("hello")).thenReturn("en");
        when(aiServiceFactory.translateText(any(UserProfile.class), anyString(), anyString()))
                .thenThrow(providerFailure());

        String response = translationService.processTranslationRequest(USER_ID, "hello");

        assertThat(response).isEqualTo("翻譯服務暫時無法使用，請稍後再試。");
        verify(translationRecordRepository, never()).save(any(TranslationRecord.class));
        assertThat(profile.getTotalTranslations()).isZero();
        assertThat(profile.getTextTranslations()).isZero();
        assertThat(profile.getRecentLanguagesList()).isEmpty();
    }

    private TranslationService createService(LanguageDetectionService detector) {
        return new TranslationService(
                detector,
                aiServiceFactory,
                translationRecordRepository,
                userProfileRepository,
                appConfig);
    }

    private TranslationRecord savedRecord() {
        ArgumentCaptor<TranslationRecord> captor = ArgumentCaptor.forClass(TranslationRecord.class);
        verify(translationRecordRepository).save(captor.capture());
        return captor.getValue();
    }

    private static UserProfile profile() {
        return UserProfile.builder()
                .userId(USER_ID)
                .preferredAiProvider("openai")
                .build();
    }

    private static AiProviderException providerFailure() {
        return new AiProviderException(
                AiProviderException.Outcome.TRANSPORT_ERROR,
                "gemini",
                "gemini-test",
                "IO_FAILURE",
                "correlation-1",
                -1,
                null);
    }

    private static Stream<Arguments> explicitTargetRequests() {
        return Stream.of(
                Arguments.of("翻譯成日文 hello", "ja"),
                Arguments.of("hello 翻譯成日文", "ja"),
                Arguments.of("hello\n翻譯成日文", "ja"),
                Arguments.of("翻譯成ja hello", "ja"),
                Arguments.of("hello 翻譯成ja", "ja"),
                Arguments.of("hello\n翻譯成ja", "ja"));
    }
}
