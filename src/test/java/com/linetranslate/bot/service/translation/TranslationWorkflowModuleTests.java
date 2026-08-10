package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.linetranslate.bot.config.AppConfig;
import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiExecutionFailure;
import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderException;

class TranslationWorkflowModuleTests {

    private LanguageDetectionService languageDetection;
    private CachedTranslationAdapter translationAdapter;
    private TranslationRecordRepository recordRepository;
    private UserProfileRepository userProfileRepository;
    private AppConfig appConfig;
    private TranslationWorkflowModule module;

    @BeforeEach
    void setUp() {
        languageDetection = Mockito.mock(LanguageDetectionService.class);
        translationAdapter = Mockito.mock(CachedTranslationAdapter.class);
        recordRepository = Mockito.mock(TranslationRecordRepository.class);
        userProfileRepository = Mockito.mock(UserProfileRepository.class);
        appConfig = Mockito.mock(AppConfig.class);
        when(appConfig.getDefaultTargetLanguageForChinese()).thenReturn("en");
        when(appConfig.getDefaultTargetLanguageForOthers()).thenReturn("zh-TW");
        module = new TranslationWorkflowModule(
                languageDetection,
                translationAdapter,
                recordRepository,
                userProfileRepository,
                appConfig);
    }

    @ParameterizedTest
    @MethodSource("translationKinds")
    void everyTranslationKindUsesTheSameSinglePassWorkflow(
            TranslationRequestKind kind,
            int expectedTextCount,
            int expectedImageCount) {
        UserProfile profile = profile();
        when(languageDetection.detectLanguage("hello")).thenReturn("en");
        when(translationAdapter.translate(profile, "hello", "zh-TW"))
                .thenReturn(success("你好"));

        TranslationWorkflowOutcome outcome = module.execute(new TranslationWorkflowRequest(
                profile,
                "hello",
                null,
                kind,
                kind.isImage() ? "https://storage.example/image.jpg" : null,
                kind.isImage() ? Boolean.TRUE : null,
                Instant.now()));

        TranslationWorkflowResult result = ((TranslationWorkflowOutcome.Success) outcome).result();
        assertThat(result.translatedText()).isEqualTo("你好");
        assertThat(result.sourceLanguage()).isEqualTo("en");
        assertThat(result.targetLanguage()).isEqualTo("zh-TW");
        verify(languageDetection).detectLanguage("hello");
        verify(translationAdapter).translate(profile, "hello", "zh-TW");
        verify(recordRepository).save(any(TranslationRecord.class));
        verify(userProfileRepository).save(profile);
        assertThat(profile.getTotalTranslations()).isEqualTo(1);
        assertThat(profile.getTextTranslations()).isEqualTo(expectedTextCount);
        assertThat(profile.getImageTranslations()).isEqualTo(expectedImageCount);
        assertThat(profile.getRecentTranslations()).containsExactly("你好");
        assertThat(profile.getRecentLanguagesList()).containsExactly("zh-TW");
    }

    @Test
    void explicitTargetStillDetectsSourceExactlyOnceAndPersistsActualProvider() {
        UserProfile profile = profile();
        when(languageDetection.detectLanguage("hello")).thenReturn("en");
        when(translationAdapter.translate(profile, "hello", "ja"))
                .thenReturn(success("こんにちは"));

        TranslationWorkflowOutcome outcome = module.execute(new TranslationWorkflowRequest(
                profile,
                "hello",
                "ja",
                TranslationRequestKind.QUICK_TEXT,
                null,
                null,
                Instant.now()));

        assertThat(outcome).isInstanceOf(TranslationWorkflowOutcome.Success.class);
        ArgumentCaptor<TranslationRecord> captor = ArgumentCaptor.forClass(TranslationRecord.class);
        verify(recordRepository).save(captor.capture());
        assertThat(captor.getValue().getSourceLanguage()).isEqualTo("en");
        assertThat(captor.getValue().getTargetLanguage()).isEqualTo("ja");
        assertThat(captor.getValue().getAiProvider()).isEqualTo("openai");
        assertThat(captor.getValue().getModelName()).isEqualTo("gpt-actual");
        verify(languageDetection, times(1)).detectLanguage("hello");
    }

    @Test
    void providerFailureNeverWritesHistoryOrCounters() {
        UserProfile profile = profile();
        when(languageDetection.detectLanguage("hello")).thenReturn("en");
        AiProviderException error = new AiProviderException(
                AiProviderException.Outcome.QUOTA_EXCEEDED,
                "openai",
                "gpt-test",
                "QUOTA",
                "correlation-1",
                429,
                null);
        when(translationAdapter.translate(profile, "hello", "zh-TW"))
                .thenReturn(new AiExecutionOutcome.Failure(AiExecutionFailure.from(error, java.util.List.of())));

        TranslationWorkflowOutcome outcome = module.execute(new TranslationWorkflowRequest(
                profile,
                "hello",
                null,
                TranslationRequestKind.STANDARD_TEXT,
                null,
                null,
                Instant.now()));

        assertThat(outcome).isInstanceOf(TranslationWorkflowOutcome.Failure.class);
        assertThat(((TranslationWorkflowOutcome.Failure) outcome).failure().outcome())
                .isEqualTo(AiProviderException.Outcome.QUOTA_EXCEEDED);
        verify(recordRepository, never()).save(any());
        verify(userProfileRepository, never()).save(any());
        assertThat(profile.getTotalTranslations()).isZero();
        assertThat(profile.getRecentTranslations()).isEmpty();
    }

    private static Stream<Arguments> translationKinds() {
        return Stream.of(
                Arguments.of(TranslationRequestKind.STANDARD_TEXT, 1, 0),
                Arguments.of(TranslationRequestKind.QUICK_TEXT, 1, 0),
                Arguments.of(TranslationRequestKind.BATCH_TEXT, 1, 0),
                Arguments.of(TranslationRequestKind.IMAGE_OCR, 0, 1));
    }

    private static UserProfile profile() {
        return UserProfile.builder()
                .userId("U-test")
                .preferredAiProvider("openai")
                .build();
    }

    private static AiExecutionOutcome success(String text) {
        return new AiExecutionOutcome.Success(new AiExecutionResult(
                text,
                "openai",
                "gpt-actual"));
    }
}
