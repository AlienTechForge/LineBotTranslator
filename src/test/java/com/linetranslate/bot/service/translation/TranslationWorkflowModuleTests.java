package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.service.ai.AiExecutionFailure;
import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderException;
import com.linetranslate.bot.service.preference.UserPreferences;
import com.linetranslate.bot.service.preference.UserPreferencesModule;

class TranslationWorkflowModuleTests {

    private LanguageDetectionService languageDetection;
    private CachedTranslationAdapter translationAdapter;
    private TranslationRecordRepository recordRepository;
    private UserPreferencesModule userPreferencesModule;
    private UserPreferences preferences;
    private TranslationWorkflowModule module;

    @BeforeEach
    void setUp() {
        languageDetection = Mockito.mock(LanguageDetectionService.class);
        translationAdapter = Mockito.mock(CachedTranslationAdapter.class);
        recordRepository = Mockito.mock(TranslationRecordRepository.class);
        userPreferencesModule = Mockito.mock(UserPreferencesModule.class);
        preferences = new UserPreferences(
                "zh-TW", "en", "zh-TW", "openai/gpt-4o-mini", List.of());
        when(userPreferencesModule.resolve(any(UserProfile.class))).thenReturn(preferences);
        when(recordRepository.save(any(TranslationRecord.class))).thenAnswer(invocation -> {
            TranslationRecord record = invocation.getArgument(0);
            record.setId("record-1");
            return record;
        });
        module = new TranslationWorkflowModule(
                languageDetection,
                translationAdapter,
                recordRepository,
                userPreferencesModule);
    }

    @ParameterizedTest
    @MethodSource("translationKinds")
    void everyTranslationKindUsesTheSameSinglePassWorkflow(
            TranslationRequestKind kind,
            int expectedTextCount,
            int expectedImageCount) {
        UserProfile profile = profile();
        when(languageDetection.detectLanguage("hello")).thenReturn("en");
        when(translationAdapter.translate(preferences, "hello", "zh-TW"))
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
        assertThat(result.recordId()).isEqualTo("record-1");
        verify(languageDetection).detectLanguage("hello");
        verify(translationAdapter).translate(preferences, "hello", "zh-TW");
        verify(recordRepository).save(any(TranslationRecord.class));
        verify(userPreferencesModule).persistTranslationActivity(profile, "zh-TW");
        assertThat(profile.getTotalTranslations()).isEqualTo(1);
        assertThat(profile.getTextTranslations()).isEqualTo(expectedTextCount);
        assertThat(profile.getImageTranslations()).isEqualTo(expectedImageCount);
        assertThat(profile.getRecentTranslations()).containsExactly("你好");
    }

    @Test
    void explicitTargetStillDetectsSourceExactlyOnceAndPersistsActualProvider() {
        UserProfile profile = profile();
        when(languageDetection.detectLanguage("hello")).thenReturn("en");
        when(translationAdapter.translate(preferences, "hello", "ja"))
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
    void oneTimeStyleReachesAdapterAndHistoryWithoutChangingUserDefault() {
        UserProfile profile = profile();
        profile.setPreferredTranslationStyle("faithful");
        when(languageDetection.detectLanguage("hello")).thenReturn("en");
        when(translationAdapter.translate(
                preferences, "hello", "zh-TW", TranslationStylePreset.FORMAL))
                .thenReturn(success("您好"));

        TranslationWorkflowOutcome outcome = module.execute(new TranslationWorkflowRequest(
                profile,
                "hello",
                null,
                TranslationRequestKind.STANDARD_TEXT,
                null,
                null,
                Instant.now(),
                "formal"));

        assertThat(outcome).isInstanceOf(TranslationWorkflowOutcome.Success.class);
        ArgumentCaptor<TranslationRecord> captor = ArgumentCaptor.forClass(TranslationRecord.class);
        verify(recordRepository).save(captor.capture());
        assertThat(captor.getValue().getStylePresetId()).isEqualTo("formal");
        assertThat(captor.getValue().getStylePromptVersion()).isEqualTo("formal-v1");
        assertThat(profile.getPreferredTranslationStyle()).isEqualTo("faithful");
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
        when(translationAdapter.translate(preferences, "hello", "zh-TW"))
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
        verify(userPreferencesModule, never()).persistTranslationActivity(any(), any());
        assertThat(profile.getTotalTranslations()).isZero();
        assertThat(profile.getRecentTranslations()).isEmpty();
    }

    @Test
    void explicitImageSourceLanguageSkipsIndependentDetection() {
        UserProfile profile = profile();
        when(translationAdapter.translate(preferences, "BẢO VỆ", "zh-TW"))
                .thenReturn(success("保護"));

        TranslationWorkflowOutcome outcome = module.execute(new TranslationWorkflowRequest(
                profile, "BẢO VỆ", null, TranslationRequestKind.IMAGE_OCR,
                null, false, Instant.now(), null, "vi"));

        assertThat(((TranslationWorkflowOutcome.Success) outcome).result().sourceLanguage()).isEqualTo("vi");
        verify(languageDetection, never()).detectLanguage(any());
        ArgumentCaptor<TranslationRecord> record = ArgumentCaptor.forClass(TranslationRecord.class);
        verify(recordRepository).save(record.capture());
        assertThat(record.getValue().getSourceLanguage()).isEqualTo("vi");
    }

    @Test
    void structuredImagePersistsReadableContentAndExactRegionMappings() {
        UserProfile profile = profile();
        StructuredImageTranslationAdapter structured = Mockito.mock(StructuredImageTranslationAdapter.class);
        module = new TranslationWorkflowModule(languageDetection, translationAdapter, recordRepository,
                userPreferencesModule, structured);
        List<ImageRegionTranslationInput> inputs = List.of(
                new ImageRegionTranslationInput("r1", "xin chào", "vi", List.of()),
                new ImageRegionTranslationInput("r2", "thế giới", "vi", List.of()));
        when(structured.translate(preferences, inputs, "zh-TW", TranslationStylePreset.FAITHFUL))
                .thenReturn(new StructuredImageTranslationAdapter.Result(
                        new AiExecutionResult("你好\n世界", "openrouter", "model"),
                        List.of(new ImageRegionTranslation("r2", "世界"),
                                new ImageRegionTranslation("r1", "你好"))));

        TranslationWorkflowOutcome outcome = module.execute(new TranslationWorkflowRequest(
                profile, "xin chào\nthế giới", null, TranslationRequestKind.IMAGE_OCR,
                null, false, Instant.now(), null, "vi", inputs));

        TranslationWorkflowResult result = ((TranslationWorkflowOutcome.Success) outcome).result();
        assertThat(result.imageRegionTranslations()).extracting(ImageRegionTranslation::regionId)
                .containsExactly("r2", "r1");
        ArgumentCaptor<TranslationRecord> record = ArgumentCaptor.forClass(TranslationRecord.class);
        verify(recordRepository).save(record.capture());
        assertThat(record.getValue().getSourceText()).isEqualTo("xin chào\nthế giới");
        assertThat(record.getValue().getTranslatedText()).isEqualTo("你好\n世界");
        assertThat(record.getValue().getTranslatedText()).doesNotContain("schemaVersion", "regionId");
        verify(languageDetection, never()).detectLanguage(any());
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
                .preferredModel("openai/gpt-4o-mini")
                .build();
    }

    private static AiExecutionOutcome success(String text) {
        return new AiExecutionOutcome.Success(new AiExecutionResult(
                text,
                "openai",
                "gpt-actual"));
    }
}
