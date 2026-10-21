package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import com.linecorp.bot.client.base.BlobContent;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.service.imagetranslator.ImageTranslatorClient;
import com.linetranslate.bot.service.imagetranslator.ImageTranslatorException;
import com.linetranslate.bot.service.imagetranslator.ImageTranslatorException.Kind;
import com.linetranslate.bot.service.imagetranslator.ImageTranslatorResult;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.storage.ImageStorageResult;
import com.linetranslate.bot.service.storage.MinioStorageService;
import com.linetranslate.bot.testing.ImageTranslatorFixtures;
import com.linetranslate.bot.testing.UserPreferencesFixtures;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** The image flow with the service stubbed: what each answer of the service becomes. */
@SuppressWarnings("unchecked")
class ImageTranslationPipelineTests {

    private static final String SIGNED = "https://storage.example/translated.png?signature=abc";
    private static final String PREVIEW = "https://storage.example/translated-preview.jpg?signature=abc";

    private ImageTranslatorClient client;
    private TranslationRecordRepository records;
    private UserPreferencesModule preferencesModule;
    private MessagingApiBlobClient blobClient;
    private MinioStorageService minio;
    private SimpleMeterRegistry meters;
    private UserProfile profile;
    private ImageTranslationPipeline pipeline;
    private final byte[] translatedPng = ImageTranslatorFixtures.png(32, 32);

    @BeforeEach
    void setUp() {
        client = mock(ImageTranslatorClient.class);
        records = mock(TranslationRecordRepository.class);
        preferencesModule = mock(UserPreferencesModule.class);
        blobClient = mock(MessagingApiBlobClient.class);
        minio = mock(MinioStorageService.class);
        meters = new SimpleMeterRegistry();
        profile = UserProfile.builder().userId("U-image").preferredLanguage("zh-TW").build();
        when(preferencesModule.resolve(profile)).thenReturn(UserPreferencesFixtures.preferences(profile));
        when(records.save(any(TranslationRecord.class))).thenAnswer(invocation -> {
            TranslationRecord record = invocation.getArgument(0);
            record.setId("record-1");
            return record;
        });
        when(minio.uploadImage(any(byte[].class), anyString()))
                .thenReturn(ImageStorageResult.stored("https://storage.example/original.jpg?signature=orig"));
        when(minio.uploadTranslatedImage(any(byte[].class), anyString()))
                .thenReturn(ImageStorageResult.stored(SIGNED, PREVIEW));
        when(client.fetchImage(any())).thenReturn(translatedPng);
        pipeline = pipeline(ImageTranslationProperties.defaults());
    }

    private ImageTranslationPipeline pipeline(ImageTranslationProperties properties) {
        return new ImageTranslationPipeline(client, records, preferencesModule, blobClient, minio,
                ImageInputValidator.trustedTestSeam(properties), properties, meters);
    }

    // ------------------------------------------------------------------ the picture

    @Test
    void aCleanPictureIsFetchedStoredAndAnsweredWithItsSignedUrl() {
        byte[] upload = {1, 2, 3};
        lineImage("message-1", upload, "image/jpeg");
        ImageTranslatorResult answer = ImageTranslatorFixtures.imageResult("message-1", "clean", "A");
        when(client.translate(any(), anyString(), anyString(), anyString())).thenReturn(answer);

        ImageTranslationPipelineResult result = success(pipeline.execute(request("message-1")));

        verify(client).translate(upload, "image/jpeg", "zh-TW", "message-1");
        verify(client).fetchImage(answer.image());
        verify(minio).uploadImage(upload, "image/jpeg");
        verify(minio).uploadTranslatedImage(translatedPng, "image/png");
        assertThat(result.renderedImage().url()).contains(SIGNED);
        assertThat(result.renderedImage().previewUrl()).contains(PREVIEW);
        assertThat(result.warned()).isFalse();
        assertThat(result.context().storage().url()).contains("https://storage.example/original.jpg?signature=orig");
        assertThat(result.context().image().bytes()).containsExactly(1, 2, 3);
        assertThat(result.text().recognizedText()).isEqualTo("TONKATSU SET MENU NT$120");
        assertThat(result.text().translatedText()).isEqualTo("炸豬排套餐\nNT$120");
        assertThat(result.text().sourceLanguage()).isEqualTo("en");
        assertThat(result.text().targetLanguage()).isEqualTo("zh-TW");
        assertThat(result.text().recordId()).isEqualTo("record-1");
    }

    @Test
    void theRecordCarriesTheTextsTheStorageAndTheNewImageFields() {
        lineImage("message-2", new byte[] {4, 5}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.imageResult("message-2", "clean", "A"));

        pipeline.execute(request("message-2"));

        ArgumentCaptor<TranslationRecord> saved = ArgumentCaptor.forClass(TranslationRecord.class);
        verify(records).save(saved.capture());
        TranslationRecord record = saved.getValue();
        assertThat(record.getUserId()).isEqualTo("U-image");
        assertThat(record.getSourceText()).isEqualTo("TONKATSU SET MENU NT$120");
        assertThat(record.getTranslatedText()).isEqualTo("炸豬排套餐\nNT$120");
        assertThat(record.getSourceLanguage()).isEqualTo("en");
        assertThat(record.getTargetLanguage()).isEqualTo("zh-TW");
        assertThat(record.isImageTranslation()).isTrue();
        assertThat(record.getAiProvider()).isEqualTo("image-translator");
        assertThat(record.getModelName()).isEqualTo("A");
        assertThat(record.getImageUrl()).isEqualTo("https://storage.example/original.jpg?signature=orig");
        assertThat(record.getImageStored()).isTrue();
        assertThat(record.getImageMode()).isEqualTo("A");
        assertThat(record.getImageTier()).isEqualTo("clean");
        assertThat(record.getImageVerdict()).isEqualTo("pass");
        assertThat(record.getImageUsability()).isEqualTo(0.82);
        assertThat(record.getImageCostUsd()).isEqualTo(0.0523);
        assertThat(record.getProcessingTimeMs()).isGreaterThan(0);
        assertThat(record.getStylePresetId()).isNotBlank();
        verify(preferencesModule).recordTranslationActivity("U-image", "zh-TW", "炸豬排套餐\nNT$120", true);
    }

    @Test
    void aWarnedPictureIsMarkedSoThatTheReplyCanAddItsWarning() {
        lineImage("message-3", new byte[] {6}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.imageResult("message-3", "warned", "B"));

        ImageTranslationPipelineResult result = success(pipeline.execute(request("message-3")));

        assertThat(result.warned()).isTrue();
        assertThat(result.renderedImage().url()).contains(SIGNED);
        ArgumentCaptor<TranslationRecord> saved = ArgumentCaptor.forClass(TranslationRecord.class);
        verify(records).save(saved.capture());
        assertThat(saved.getValue().getImageMode()).isEqualTo("B");
        assertThat(saved.getValue().getModelName()).isEqualTo("B");
        assertThat(saved.getValue().getImageTier()).isEqualTo("warned");
        assertThat(saved.getValue().getImageVerdict()).isEqualTo("numbers+score");
    }

    @ParameterizedTest
    @CsvSource({"ja, ja", "zh, zh-TW", "en, en", "zh-CN, zh-CN"})
    void theTargetLanguageIsTheUsersPreferredOneAndTheRequestIdIsTheLineMessageId(String preferred, String sent) {
        UserProfile other = UserProfile.builder().userId("U-pref").preferredLanguage(preferred).build();
        when(preferencesModule.resolve(other)).thenReturn(UserPreferencesFixtures.preferences(other));
        lineImage("line-message-77", new byte[] {7}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.imageResult("line-message-77", "clean", "A"));

        pipeline.execute(new ImageTranslationRequest(other, "line-message-77", Instant.now()));

        verify(client).translate(any(), eq("image/jpeg"), eq(sent), eq("line-message-77"));
    }

    // ------------------------------------------------------------------ text only and the rest of the statuses

    @Test
    void textOnlyAnswersWithTheTextAndFetchesNoPicture() {
        lineImage("message-4", new byte[] {8}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.parse(ImageTranslatorFixtures.textOnlyJson("message-4", "你好")));

        ImageTranslationPipelineResult result = success(pipeline.execute(request("message-4")));

        verify(client, never()).fetchImage(any());
        verify(minio, never()).uploadTranslatedImage(any(byte[].class), anyString());
        assertThat(result.renderedImage().url()).isEmpty();
        assertThat(result.warned()).isFalse();
        assertThat(result.text().translatedText()).isEqualTo("你好");
        assertThat(result.text().sourceLanguage()).isEqualTo("ja");
        assertThat(result.text().recordId()).isEqualTo("record-1");
        ArgumentCaptor<TranslationRecord> saved = ArgumentCaptor.forClass(TranslationRecord.class);
        verify(records).save(saved.capture());
        assertThat(saved.getValue().getImageTier()).isNull();
        assertThat(saved.getValue().getImageMode()).isEqualTo("B");
        assertThat(saved.getValue().getImageCostUsd()).isEqualTo(0.07);
        assertThat(saved.getValue().isImageTranslation()).isTrue();
    }

    @Test
    void textOnlyWithoutATranslationLeavesNothingToSend() {
        lineImage("message-5", new byte[] {9}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.parse(ImageTranslatorFixtures.textOnlyJson("message-5", null)));

        assertThat(pipeline.execute(request("message-5")))
                .isEqualTo(new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.TRANSLATION));
        verify(records, never()).save(any());
    }

    @Test
    void noTextIsItsOwnFailureAndSavesNothing() {
        lineImage("message-6", new byte[] {10}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.parse(ImageTranslatorFixtures.noTextJson("message-6")));

        assertThat(pipeline.execute(request("message-6")))
                .isEqualTo(new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.NO_TEXT));
        verify(client, never()).fetchImage(any());
        verify(records, never()).save(any());
        verify(preferencesModule, never()).recordTranslationActivity(anyString(), anyString(), anyString(), eq(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic_budget", "openrouter_budget"})
    void aBudgetErrorIsTheBudgetFailure(String reason) {
        lineImage("message-7", new byte[] {11}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.parse(ImageTranslatorFixtures.errorJson("message-7", reason)));

        assertThat(pipeline.execute(request("message-7")))
                .isEqualTo(new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.BUDGET));
        verify(records, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"both_failed", "a_failed", "b_failed", "deadline", "internal_error"})
    void anyOtherErrorIsTheGenericTranslationFailure(String reason) {
        lineImage("message-8", new byte[] {12}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.parse(ImageTranslatorFixtures.errorJson("message-8", reason)));

        assertThat(pipeline.execute(request("message-8")))
                .isEqualTo(new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.TRANSLATION));
    }

    @Test
    void aStatusThisClientDoesNotKnowIsAFailureNotACrash() {
        lineImage("message-9", new byte[] {13}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.parse("{\"status\": \"teleported\"}"));

        assertThat(pipeline.execute(request("message-9")))
                .isEqualTo(new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.TRANSLATION));
    }

    // ------------------------------------------------------------------ the service cannot be used

    @ParameterizedTest
    @CsvSource({"HTTP, 401, unauthorized", "HTTP, 403, forbidden", "HTTP, 503, service_token_missing",
            "HTTP, 502, ''", "HTTP, 400, bad_request", "TIMEOUT, 0, ''", "UNREACHABLE, 0, ''", "MALFORMED, 200, ''"})
    void anHttpFailureATimeoutOrAnUnusableAnswerIsTheServiceBeingUnavailable(Kind kind, int status, String reason) {
        lineImage("message-10", new byte[] {14}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenThrow(new ImageTranslatorException(kind, status, reason.isEmpty() ? null : reason, null, null));

        assertThat(pipeline.execute(request("message-10")))
                .isEqualTo(new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.SERVICE_UNAVAILABLE));
        verify(records, never()).save(any());
        verify(client, never()).fetchImage(any());
    }

    @Test
    void aRedeliveredEventGetsTheGenericFailureAndIsNeverTranslatedTwice() {
        lineImage("message-11", new byte[] {15}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenThrow(new ImageTranslatorException(Kind.HTTP, 409, "request_id_in_use", "in use", null));

        assertThat(pipeline.execute(request("message-11")))
                .isEqualTo(new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.TRANSLATION));
        verify(client, times(1)).translate(any(), anyString(), anyString(), eq("message-11"));
        verify(client, never()).fetchImage(any());
        verify(records, never()).save(any());
    }

    @Test
    void anUnexpectedDefectInTheClientIsCountedAndTheServiceIsReportedUnavailable() {
        lineImage("message-12", new byte[] {16}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("a bug with a secret message"));

        assertThat(pipeline.execute(request("message-12")))
                .isEqualTo(new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.SERVICE_UNAVAILABLE));
        assertThat(unexpected("translation")).isEqualTo(1.0);
    }

    @Test
    void aFailureOfTheCallOfAnInterruptedThreadIsCancellationAndKeepsTheFlag() {
        lineImage("message-13", new byte[] {17}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    Thread.currentThread().interrupt();
                    throw new ImageTranslatorException(Kind.UNREACHABLE, 0, null, null, null);
                });
        try {
            assertThat(pipeline.execute(request("message-13")))
                    .isEqualTo(new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.CANCELLED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    // ------------------------------------------------------------------ the picture cannot be delivered

    @Test
    void aPictureThatCannotBeFetchedFallsBackToTheTranslatedText() {
        lineImage("message-14", new byte[] {18}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.imageResult("message-14", "clean", "A"));
        when(client.fetchImage(any())).thenThrow(new ImageTranslatorException(Kind.HTTP, 404, "not_found", null, null));

        ImageTranslationPipelineResult result = success(pipeline.execute(request("message-14")));

        assertThat(result.renderedImage().url()).isEmpty();
        assertThat(result.warned()).isFalse();
        assertThat(result.text().translatedText()).isEqualTo("炸豬排套餐\nNT$120");
        verify(minio, never()).uploadTranslatedImage(any(byte[].class), anyString());
        verify(records).save(any(TranslationRecord.class));
    }

    @Test
    void aPictureThatCannotBeFetchedAndHasNoTranslationLeavesTheServiceUnavailable() {
        lineImage("message-15", new byte[] {19}, "image/jpeg");
        String noText = ImageTranslatorFixtures.imageJson("message-15", "clean", "a_pass", "A", 10)
                .replace("\"translated_text\": \"炸豬排套餐\\nNT$120\"", "\"translated_text\": null");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.parse(noText));
        when(client.fetchImage(any())).thenThrow(new ImageTranslatorException(Kind.TIMEOUT, 0, null, null, null));

        assertThat(pipeline.execute(request("message-15")))
                .isEqualTo(new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.SERVICE_UNAVAILABLE));
        verify(records, never()).save(any());
    }

    @Test
    void aPictureTheStorageDoesNotTakeFallsBackToTheTranslatedText() {
        lineImage("message-16", new byte[] {20}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.imageResult("message-16", "warned", "A"));
        when(minio.uploadTranslatedImage(any(byte[].class), anyString())).thenReturn(ImageStorageResult.notStored());

        ImageTranslationPipelineResult result = success(pipeline.execute(request("message-16")));

        assertThat(result.renderedImage().url()).isEmpty();
        assertThat(result.warned()).as("a warning is about the picture; there is none").isFalse();
        assertThat(result.text().translatedText()).isNotBlank();
    }

    @Test
    void aPictureStoredWithoutAUrlFallsBackToTheTranslatedText() {
        lineImage("message-17", new byte[] {21}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.imageResult("message-17", "clean", "A"));
        when(minio.uploadTranslatedImage(any(byte[].class), anyString()))
                .thenReturn(ImageStorageResult.storedWithoutUrl());

        assertThat(success(pipeline.execute(request("message-17"))).renderedImage().url()).isEmpty();
    }

    @Test
    void anUnexpectedDefectWhileDeliveringIsCountedAndTheTextIsSent() {
        lineImage("message-18", new byte[] {22}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.imageResult("message-18", "clean", "A"));
        when(minio.uploadTranslatedImage(any(byte[].class), anyString())).thenThrow(new IllegalStateException("bug"));

        ImageTranslationPipelineResult result = success(pipeline.execute(request("message-18")));

        assertThat(result.renderedImage().url()).isEmpty();
        assertThat(unexpected("delivery")).isEqualTo(1.0);
    }

    @Test
    void aPictureAboveTheDeliveryTargetIsUploadedAsJpegWhenThatFits() {
        byte[] big = ImageTranslatorFixtures.gradientPng(600, 600);
        ImageTranslationProperties small = new ImageTranslationProperties(
                10_485_760, 4_096, 16_000_000, 10_485_760L, big.length / 3);
        lineImage("message-19", new byte[] {23}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.imageResult("message-19", "clean", "A"));
        when(client.fetchImage(any())).thenReturn(big);

        ImageTranslationPipelineResult result = success(pipeline(small).execute(request("message-19")));

        ArgumentCaptor<byte[]> uploaded = ArgumentCaptor.forClass(byte[].class);
        verify(minio).uploadTranslatedImage(uploaded.capture(), eq("image/jpeg"));
        assertThat(uploaded.getValue().length).isLessThanOrEqualTo(big.length / 3);
        assertThat(result.renderedImage().url()).contains(SIGNED);
    }

    @Test
    void aPictureAboveTheOutputCapThatNoEncodingFitsIsNotUploadedAndTheTextIsSent() {
        byte[] noisy = ImageTranslatorFixtures.noisyPng(200, 200);
        ImageTranslationProperties tiny = new ImageTranslationProperties(10_485_760, 4_096, 16_000_000, 1_000L, 500L);
        lineImage("message-20", new byte[] {24}, "image/jpeg");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.imageResult("message-20", "clean", "A"));
        when(client.fetchImage(any())).thenReturn(noisy);

        ImageTranslationPipelineResult result = success(pipeline(tiny).execute(request("message-20")));

        verify(minio, never()).uploadTranslatedImage(any(byte[].class), anyString());
        assertThat(result.renderedImage().url()).isEmpty();
        assertThat(result.text().translatedText()).isNotBlank();
    }

    // ------------------------------------------------------------------ storage of the original and the record

    @Test
    void aStorageThatIsDownStillTranslatesAndTheRecordSaysTheOriginalIsNotStored() {
        lineImage("message-21", new byte[] {25}, "image/jpeg");
        when(minio.uploadImage(any(byte[].class), anyString())).thenReturn(ImageStorageResult.notStored());
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.imageResult("message-21", "clean", "A"));

        ImageTranslationPipelineResult result = success(pipeline.execute(request("message-21")));

        assertThat(result.context().storage().stored()).isFalse();
        ArgumentCaptor<TranslationRecord> saved = ArgumentCaptor.forClass(TranslationRecord.class);
        verify(records).save(saved.capture());
        assertThat(saved.getValue().getImageStored()).isFalse();
        assertThat(saved.getValue().getImageUrl()).isNull();
    }

    @Test
    void aStorageThatThrowsIsCountedAndDoesNotBlockTheTranslation() {
        lineImage("message-22", new byte[] {26}, "image/jpeg");
        when(minio.uploadImage(any(byte[].class), anyString())).thenThrow(new IllegalStateException("storage bug"));
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.imageResult("message-22", "clean", "A"));

        ImageTranslationPipelineResult result = success(pipeline.execute(request("message-22")));

        assertThat(result.context().storage().stored()).isFalse();
        assertThat(unexpected("storage")).isEqualTo(1.0);
    }

    @Test
    void aRecordThatCannotBeSavedCostsTheQuickRepliesNotTheReply() {
        lineImage("message-23", new byte[] {27}, "image/jpeg");
        when(records.save(any(TranslationRecord.class))).thenThrow(new DataAccessResourceFailureException("db down"));
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.imageResult("message-23", "clean", "A"));

        ImageTranslationPipelineResult result = success(pipeline.execute(request("message-23")));

        assertThat(result.renderedImage().url()).contains(SIGNED);
        assertThat(result.text().recordId()).isNull();
        assertThat(unexpected("recording")).isZero();
        verify(preferencesModule).recordTranslationActivity("U-image", "zh-TW", "炸豬排套餐\nNT$120", true);
    }

    @Test
    void aDefectWhileSavingTheRecordIsCountedAndTheReplyStillGoesOut() {
        lineImage("message-24", new byte[] {28}, "image/jpeg");
        when(records.save(any(TranslationRecord.class))).thenThrow(new IllegalStateException("bug"));
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.imageResult("message-24", "clean", "A"));

        ImageTranslationPipelineResult result = success(pipeline.execute(request("message-24")));

        assertThat(result.text().recordId()).isNull();
        assertThat(unexpected("recording")).isEqualTo(1.0);
    }

    @Test
    void aTranslationWithoutTextsStoresEmptyStringsInTheRecord() {
        lineImage("message-25", new byte[] {29}, "image/jpeg");
        String json = ImageTranslatorFixtures.imageJson("message-25", "clean", "a_pass", "A", 10)
                .replace("\"recognized_text\": \"TONKATSU SET MENU NT$120\"", "\"recognized_text\": null")
                .replace("\"source_language\": \"en\"", "\"source_language\": null");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.parse(json));

        success(pipeline.execute(request("message-25")));

        ArgumentCaptor<TranslationRecord> saved = ArgumentCaptor.forClass(TranslationRecord.class);
        verify(records).save(saved.capture());
        assertThat(saved.getValue().getSourceText()).isEmpty();
        assertThat(saved.getValue().getSourceLanguage()).isNull();
    }

    // ------------------------------------------------------------------ before the service

    @Test
    void aLineResponseWithoutABodyStopsAtTheDownloadAndCallsNothing() {
        Result<BlobContent> response = mock(Result.class);
        when(blobClient.getMessageContent("message-no-body")).thenReturn(CompletableFuture.completedFuture(response));
        when(response.body()).thenReturn(null);

        assertThat(pipeline.execute(request("message-no-body")))
                .isEqualTo(new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.DOWNLOAD));
        verifyNoInteractions(client, records);
        verify(minio, never()).uploadImage(any(), anyString());
    }

    @Test
    void anUploadThatIsNotAnImageIsRefusedBeforeStorageAndTheService() {
        ImageTranslationProperties properties = ImageTranslationProperties.defaults();
        ImageTranslationPipeline strict = new ImageTranslationPipeline(client, records, preferencesModule, blobClient,
                minio, new ImageInputValidator(properties), properties, meters);
        lineImage("message-text-file", "not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8), "image/jpeg");

        assertThat(strict.execute(request("message-text-file")))
                .isEqualTo(new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.DOWNLOAD));
        verifyNoInteractions(client, records);
        verify(minio, never()).uploadImage(any(), anyString());
    }

    @Test
    void anUploadAboveTheFileSizeLimitIsRefusedBeforeStorageAndTheService() {
        ImageTranslationProperties properties = new ImageTranslationProperties(10, 4_096, 16_000_000);
        ImageTranslationPipeline limited = pipeline(properties);
        lineImage("message-large", new byte[64], "image/jpeg");

        assertThat(limited.execute(request("message-large")))
                .isEqualTo(new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.DOWNLOAD));
        verifyNoInteractions(client, records);
    }

    @Test
    void anInterruptedDownloadIsCancellationAndSkipsEveryLaterStage() {
        CompletableFuture<Result<BlobContent>> pending = new CompletableFuture<>();
        when(blobClient.getMessageContent("message-cancelled")).thenReturn(pending);

        ImageTranslationOutcome outcome;
        try {
            Thread.currentThread().interrupt();
            outcome = pipeline.execute(request("message-cancelled"));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(outcome).isEqualTo(new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.CANCELLED));
        verifyNoInteractions(client, records);
        verify(minio, never()).uploadImage(any(), anyString());
    }

    @Test
    void aMissingRequestIsRefused() {
        assertThatThrownBy(() -> pipeline.execute(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPngUploadIsSentToTheServiceAsAPng() {
        byte[] sent = {1, 1, 1};
        lineImage("message-26", sent, "image/png");
        when(client.translate(any(), anyString(), anyString(), anyString()))
                .thenReturn(ImageTranslatorFixtures.imageResult("message-26", "clean", "A"));

        pipeline.execute(request("message-26"));

        verify(client).translate(sent, "image/png", "zh-TW", "message-26");
    }

    // ------------------------------------------------------------------ helpers

    private double unexpected(String stage) {
        var counter = meters.find("image.translation.unexpected").tag("stage", stage).counter();
        return counter == null ? 0 : counter.count();
    }

    private ImageTranslationPipelineResult success(ImageTranslationOutcome outcome) {
        assertThat(outcome).isInstanceOf(ImageTranslationOutcome.Success.class);
        return ((ImageTranslationOutcome.Success) outcome).result();
    }

    private ImageTranslationRequest request(String messageId) {
        return new ImageTranslationRequest(profile, messageId, Instant.now().minusMillis(250));
    }

    private void lineImage(String messageId, byte[] bytes, String mimeType) {
        Result<BlobContent> result = mock(Result.class);
        BlobContent content = mock(BlobContent.class);
        InputStream stream = new ByteArrayInputStream(bytes);
        when(blobClient.getMessageContent(messageId)).thenReturn(CompletableFuture.completedFuture(result));
        when(result.body()).thenReturn(content);
        when(content.byteStream()).thenReturn(stream);
        when(content.mimeType()).thenReturn(mimeType);
    }
}
