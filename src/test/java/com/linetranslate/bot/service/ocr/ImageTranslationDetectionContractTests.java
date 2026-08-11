package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import com.linecorp.bot.client.base.BlobContent;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.ai.AiExecutionFailure;
import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderException;
import com.linetranslate.bot.service.ai.AiProviderExecutionModule;
import com.linetranslate.bot.service.preference.UserPreferences;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.storage.ImageStorageResult;
import com.linetranslate.bot.service.storage.MinioStorageService;
import com.linetranslate.bot.service.translation.CachedTranslationAdapter;
import com.linetranslate.bot.service.translation.LanguageDetectionService;
import com.linetranslate.bot.service.translation.TranslationWorkflowModule;

@ExtendWith(MockitoExtension.class)
class ImageTranslationDetectionContractTests {

    @Mock
    private ObjectProvider<OcrService> ocrServiceProvider;
    @Mock
    private OcrService ocrService;
    @Mock
    private CachedTranslationAdapter translationAdapter;
    @Mock
    private LanguageDetectionService languageDetectionService;
    @Mock
    private AiProviderExecutionModule aiServiceFactory;
    @Mock
    private MessagingApiBlobClient messagingApiBlobClient;
    @Mock
    private TranslationRecordRepository translationRecordRepository;
    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private UserPreferencesModule userPreferencesModule;
    @Mock
    private MinioStorageService minioStorageService;
    @Mock
    private Result<BlobContent> blobResult;
    @Mock
    private BlobContent blobContent;

    private ImageTranslationService imageTranslationService;
    private UserProfile userProfile;

    @BeforeEach
    void setUp() throws Exception {
        when(ocrServiceProvider.getIfAvailable()).thenReturn(ocrService);
        UserPreferences preferences = com.linetranslate.bot.testing.UserPreferencesFixtures.preferences(
                "openai", "gpt-test", "gemini-test");
        when(userPreferencesModule.resolve(any(UserProfile.class))).thenReturn(preferences);
        TranslationWorkflowModule workflowModule = new TranslationWorkflowModule(
                languageDetectionService,
                translationAdapter,
                translationRecordRepository,
                userPreferencesModule);
        ImageTranslationPipeline pipeline = new ImageTranslationPipeline(
                ocrServiceProvider,
                workflowModule,
                aiServiceFactory,
                userPreferencesModule,
                messagingApiBlobClient,
                minioStorageService);
        imageTranslationService = new ImageTranslationService(pipeline, userProfileRepository);

        userProfile = UserProfile.builder()
                .userId("U-test")
                .preferredModel("gpt-test")
                .build();
        when(userProfileRepository.findByUserId("U-test")).thenReturn(Optional.of(userProfile));
        when(userProfileRepository.save(any(UserProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messagingApiBlobClient.getMessageContent("message-id"))
                .thenReturn(CompletableFuture.completedFuture(blobResult));
        when(blobResult.body()).thenReturn(blobContent);
        when(blobContent.byteStream()).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        lenient().when(minioStorageService.uploadImage(any(byte[].class), anyString()))
                .thenReturn(ImageStorageResult.stored("https://storage.example/image.jpg"));
        when(ocrService.recognizeText(any(ByteArrayInputStream.class))).thenReturn("hello");
        when(languageDetectionService.detectLanguage("hello")).thenReturn("en");
        lenient().when(translationAdapter.translate(any(UserPreferences.class), anyString(), anyString()))
                .thenReturn(new AiExecutionOutcome.Success(
                        new AiExecutionResult("翻譯結果", "openai", "gpt-test")));
    }

    @Test
    void imageTranslationDetectsRecognizedTextOnceAndReusesIt() {
        String response = imageTranslationService.processImageTranslation("U-test", "message-id");

        verify(languageDetectionService, times(1)).detectLanguage("hello");
        ArgumentCaptor<TranslationRecord> captor = ArgumentCaptor.forClass(TranslationRecord.class);
        verify(translationRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getSourceLanguage()).isEqualTo("en");
        assertThat(response).contains("偵測到:").contains("翻譯結果");
    }

    @Test
    void imageFallbackPersistsTheProviderThatActuallyTranslated() {
        when(translationAdapter.translate(any(UserPreferences.class), anyString(), anyString()))
                .thenReturn(new AiExecutionOutcome.Success(
                        new AiExecutionResult("翻譯結果", "gemini", "gemini-test")));

        imageTranslationService.processImageTranslation("U-test", "message-id");

        ArgumentCaptor<TranslationRecord> captor = ArgumentCaptor.forClass(TranslationRecord.class);
        verify(translationRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getAiProvider()).isEqualTo("gemini");
        assertThat(captor.getValue().getModelName()).isEqualTo("gemini-test");
    }

    @Test
    void storageOutageStillReturnsTranslationAndRecordsThatImageWasNotStored() {
        when(minioStorageService.uploadImage(any(byte[].class), anyString()))
                .thenReturn(ImageStorageResult.notStored());

        String response = imageTranslationService.processImageTranslation("U-test", "message-id");

        assertThat(response).contains("翻譯結果");
        ArgumentCaptor<TranslationRecord> captor = ArgumentCaptor.forClass(TranslationRecord.class);
        verify(translationRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getImageStored()).isFalse();
        assertThat(captor.getValue().getImageUrl()).isNull();
    }

    @Test
    void totalImageTranslationFailureDoesNotSaveOrCount() {
        AiProviderException error = new AiProviderException(
                        AiProviderException.Outcome.TRANSPORT_ERROR,
                        "gemini",
                        "gemini-test",
                        "IO_FAILURE",
                        "correlation-1",
                        -1,
                        null);
        when(translationAdapter.translate(any(UserPreferences.class), anyString(), anyString()))
                .thenReturn(new AiExecutionOutcome.Failure(
                        AiExecutionFailure.from(error, java.util.List.of())));

        String response = imageTranslationService.processImageTranslation("U-test", "message-id");

        assertThat(response).isEqualTo("圖片翻譯服務暫時無法使用，請稍後再試。");
        verify(translationRecordRepository, never()).save(any(TranslationRecord.class));
        assertThat(userProfile.getTotalTranslations()).isZero();
        assertThat(userProfile.getImageTranslations()).isZero();
    }

}
