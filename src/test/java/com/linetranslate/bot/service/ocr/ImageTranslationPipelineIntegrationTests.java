package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.linecorp.bot.client.base.BlobContent;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderException;
import com.linetranslate.bot.service.ai.AiProviderExecutionModule;
import com.linetranslate.bot.service.storage.ImageStorageResult;
import com.linetranslate.bot.service.storage.MinioStorageService;
import com.linetranslate.bot.service.translation.TranslationRequestKind;
import com.linetranslate.bot.service.translation.TranslationWorkflowModule;
import com.linetranslate.bot.service.translation.TranslationWorkflowOutcome;
import com.linetranslate.bot.service.translation.TranslationWorkflowRequest;
import com.linetranslate.bot.service.translation.TranslationWorkflowResult;

@SuppressWarnings("unchecked")
class ImageTranslationPipelineIntegrationTests {

    private ObjectProvider<OcrService> ocrServiceProvider;
    private OcrService ocrService;
    private TranslationWorkflowModule workflowModule;
    private AiProviderExecutionModule aiProviderExecutionModule;
    private MessagingApiBlobClient messagingApiBlobClient;
    private MinioStorageService minioStorageService;
    private ImageTranslationPipeline pipeline;
    private UserProfile userProfile;

    @BeforeEach
    void setUp() {
        ocrServiceProvider = mock(ObjectProvider.class);
        ocrService = mock(OcrService.class);
        workflowModule = mock(TranslationWorkflowModule.class);
        aiProviderExecutionModule = mock(AiProviderExecutionModule.class);
        messagingApiBlobClient = mock(MessagingApiBlobClient.class);
        minioStorageService = mock(MinioStorageService.class);
        when(ocrServiceProvider.getIfAvailable()).thenReturn(ocrService);
        pipeline = new ImageTranslationPipeline(
                ocrServiceProvider,
                workflowModule,
                aiProviderExecutionModule,
                messagingApiBlobClient,
                minioStorageService);
        userProfile = UserProfile.builder()
                .userId("U-image")
                .preferredAiProvider("openai")
                .build();
    }

    @Test
    void successCarriesDownloadedStorageOcrAndTranslationResultsExplicitly() throws Exception {
        TrackingInputStream stream = image("message-1", new byte[] {1, 2, 3});
        when(minioStorageService.uploadImage(new byte[] {1, 2, 3}, "image/jpeg"))
                .thenReturn(ImageStorageResult.stored("https://storage.example/one.jpg"));
        when(ocrService.recognizeText(any(InputStream.class))).thenReturn("hello");
        when(workflowModule.execute(any())).thenReturn(success("hello", "你好"));

        ImageTranslationOutcome outcome = pipeline.execute(request("message-1"));

        assertThat(outcome).isInstanceOfSatisfying(ImageTranslationOutcome.Success.class, success -> {
            assertThat(success.result().context().image().bytes()).containsExactly(1, 2, 3);
            assertThat(success.result().context().storage().stored()).isTrue();
            assertThat(success.result().context().recognizedText()).isEqualTo("hello");
            assertThat(success.result().translation().translatedText()).isEqualTo("你好");
        });
        assertThat(stream.closed()).isTrue();
    }

    @Test
    void emptyLineResponseStopsAtDownloadStage() {
        Result<BlobContent> response = mock(Result.class);
        when(messagingApiBlobClient.getMessageContent("message-no-body"))
                .thenReturn(CompletableFuture.completedFuture(response));
        when(response.body()).thenReturn(null);

        ImageTranslationOutcome outcome = pipeline.execute(request("message-no-body"));

        assertThat(outcome).isEqualTo(new ImageTranslationOutcome.Failure(
                ImageTranslationFailureStage.DOWNLOAD));
        verify(minioStorageService, never()).uploadImage(any(), anyString());
        verify(ocrService, never()).recognizeText(any());
        verify(workflowModule, never()).execute(any());
    }

    @Test
    void minioDegradedStillTranslatesWithExplicitNotStoredResult() throws Exception {
        image("message-degraded", new byte[] {4});
        when(minioStorageService.uploadImage(any(byte[].class), anyString()))
                .thenReturn(ImageStorageResult.notStored());
        when(ocrService.recognizeText(any(InputStream.class))).thenReturn("hello");
        when(workflowModule.execute(any())).thenReturn(success("hello", "你好"));

        ImageTranslationOutcome outcome = pipeline.execute(request("message-degraded"));

        assertThat(outcome).isInstanceOfSatisfying(ImageTranslationOutcome.Success.class,
                success -> assertThat(success.result().context().storage().stored()).isFalse());
    }

    @Test
    void minioExceptionIsDegradedAndDoesNotBlockTranslation() throws Exception {
        image("message-storage-error", new byte[] {7});
        when(minioStorageService.uploadImage(any(byte[].class), anyString()))
                .thenThrow(new IllegalStateException("storage unavailable"));
        when(ocrService.recognizeText(any(InputStream.class))).thenReturn("hello");
        when(workflowModule.execute(any())).thenReturn(success("hello", "你好"));

        ImageTranslationOutcome outcome = pipeline.execute(request("message-storage-error"));

        assertThat(outcome).isInstanceOfSatisfying(ImageTranslationOutcome.Success.class,
                success -> assertThat(success.result().context().storage().stored()).isFalse());
    }

    @Test
    void primaryOcrFailureFallsBackToAiImageRecognition() throws Exception {
        image("message-fallback", new byte[] {5});
        when(minioStorageService.uploadImage(any(byte[].class), anyString()))
                .thenReturn(ImageStorageResult.notStored());
        when(ocrService.recognizeText(any(InputStream.class)))
                .thenThrow(new OcrProcessingException("vision unavailable"));
        when(aiProviderExecutionModule.processImage(any(), anyString(), anyString()))
                .thenReturn(new AiExecutionResult("fallback text", "openai", "gpt-image"));
        when(workflowModule.execute(any())).thenReturn(success("fallback text", "後備翻譯"));

        ImageTranslationOutcome outcome = pipeline.execute(request("message-fallback"));

        assertThat(outcome).isInstanceOfSatisfying(ImageTranslationOutcome.Success.class,
                success -> assertThat(success.result().context().recognizedText())
                        .isEqualTo("fallback text"));
        verify(aiProviderExecutionModule).processImage(any(), anyString(), anyString());
    }

    @Test
    void allRecognitionProvidersFailWithoutCallingTranslationWorkflow() throws Exception {
        TrackingInputStream stream = image("message-failed", new byte[] {6});
        when(minioStorageService.uploadImage(any(byte[].class), anyString()))
                .thenReturn(ImageStorageResult.notStored());
        when(ocrService.recognizeText(any(InputStream.class)))
                .thenThrow(new OcrProcessingException("vision unavailable"));
        when(aiProviderExecutionModule.processImage(any(), anyString(), anyString()))
                .thenThrow(new AiProviderException(
                        AiProviderException.Outcome.TRANSPORT_ERROR,
                        "openai",
                        "gpt-image",
                        "IO_FAILURE",
                        "correlation-image",
                        -1,
                        null));

        ImageTranslationOutcome outcome = pipeline.execute(request("message-failed"));

        assertThat(outcome).isEqualTo(new ImageTranslationOutcome.Failure(
                ImageTranslationFailureStage.RECOGNITION));
        assertThat(stream.closed()).isTrue();
        verify(workflowModule, never()).execute(any());
    }

    @Test
    void noRecognizedTextStopsBeforeTranslation() throws Exception {
        image("message-empty", new byte[] {8});
        when(minioStorageService.uploadImage(any(byte[].class), anyString()))
                .thenReturn(ImageStorageResult.notStored());
        when(ocrService.recognizeText(any(InputStream.class))).thenReturn("  ");

        ImageTranslationOutcome outcome = pipeline.execute(request("message-empty"));

        assertThat(outcome).isEqualTo(new ImageTranslationOutcome.Failure(
                ImageTranslationFailureStage.NO_TEXT));
        verify(workflowModule, never()).execute(any());
    }

    @Test
    void workflowFailureKeepsExplicitTranslationStage() throws Exception {
        image("message-translation-failed", new byte[] {9});
        when(minioStorageService.uploadImage(any(byte[].class), anyString()))
                .thenReturn(ImageStorageResult.notStored());
        when(ocrService.recognizeText(any(InputStream.class))).thenReturn("hello");
        var providerFailure = new AiProviderException(
                AiProviderException.Outcome.TRANSPORT_ERROR,
                "openai",
                "gpt-test",
                "IO_FAILURE",
                "correlation-translation",
                -1,
                null);
        when(workflowModule.execute(any())).thenReturn(new TranslationWorkflowOutcome.Failure(
                com.linetranslate.bot.service.ai.AiExecutionFailure.from(
                        providerFailure, List.of())));

        ImageTranslationOutcome outcome = pipeline.execute(request("message-translation-failed"));

        assertThat(outcome).isInstanceOfSatisfying(ImageTranslationOutcome.Failure.class, failure -> {
            assertThat(failure.stage()).isEqualTo(ImageTranslationFailureStage.TRANSLATION);
            assertThat(failure.executionFailure()).isPresent();
        });
    }

    @Test
    void interruptedDownloadPreservesCancellationAndSkipsEveryLaterStage() {
        CompletableFuture<Result<BlobContent>> pending = new CompletableFuture<>();
        when(messagingApiBlobClient.getMessageContent("message-cancelled")).thenReturn(pending);

        ImageTranslationOutcome outcome;
        try {
            Thread.currentThread().interrupt();
            outcome = pipeline.execute(request("message-cancelled"));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(outcome).isEqualTo(new ImageTranslationOutcome.Failure(
                ImageTranslationFailureStage.CANCELLED));
        verify(minioStorageService, never()).uploadImage(any(), anyString());
        verify(ocrService, never()).recognizeText(any());
        verify(workflowModule, never()).execute(any());
    }

    @Test
    void packageContainsNoThreadLocalRequestState() throws Exception {
        String service = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src", "main", "java", "com", "linetranslate", "bot", "service", "ocr",
                "ImageTranslationService.java"));
        String pipelineSource = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src", "main", "java", "com", "linetranslate", "bot", "service", "ocr",
                "ImageTranslationPipeline.java"));

        assertThat(service).doesNotContain("ThreadLocal");
        assertThat(pipelineSource).doesNotContain("ThreadLocal");
    }

    @Test
    void concurrentRequestsKeepBytesStorageOcrAndTranslationBoundToTheirMessage() throws Exception {
        image("message-a", new byte[] {10});
        image("message-b", new byte[] {20});
        when(minioStorageService.uploadImage(any(byte[].class), anyString()))
                .thenAnswer(invocation -> {
                    byte first = invocation.getArgument(0, byte[].class)[0];
                    return ImageStorageResult.stored("https://storage.example/" + first);
                });
        when(ocrService.recognizeText(any(InputStream.class))).thenAnswer(invocation -> {
            int first = invocation.getArgument(0, InputStream.class).read();
            return "ocr-" + first;
        });
        var workflowRequests = new ConcurrentLinkedQueue<TranslationWorkflowRequest>();
        when(workflowModule.execute(any())).thenAnswer(invocation -> {
            TranslationWorkflowRequest workflowRequest = invocation.getArgument(0);
            workflowRequests.add(workflowRequest);
            return success(workflowRequest.sourceText(), "translated-" + workflowRequest.sourceText());
        });

        List<ImageTranslationOutcome> outcomes;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> pipeline.execute(request("message-a")));
            var second = executor.submit(() -> pipeline.execute(request("message-b")));
            outcomes = List.of(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }

        assertThat(outcomes).extracting(outcome -> successResult(outcome).context().recognizedText())
                .containsExactlyInAnyOrder("ocr-10", "ocr-20");
        for (ImageTranslationOutcome outcome : outcomes) {
            ImageTranslationPipelineResult result = successResult(outcome);
            int imageByte = result.context().image().bytes()[0];
            assertThat(result.context().storage().url()).contains("https://storage.example/" + imageByte);
            assertThat(result.context().recognizedText()).isEqualTo("ocr-" + imageByte);
            assertThat(result.translation().translatedText()).isEqualTo("translated-ocr-" + imageByte);
        }
        assertThat(workflowRequests).allSatisfy(workflowRequest -> {
            String imageByte = workflowRequest.sourceText().substring("ocr-".length());
            assertThat(workflowRequest.imageUrl()).isEqualTo("https://storage.example/" + imageByte);
            assertThat(workflowRequest.kind()).isEqualTo(TranslationRequestKind.IMAGE_OCR);
        });
    }

    private TrackingInputStream image(String messageId, byte[] bytes) throws Exception {
        Result<BlobContent> result = mock(Result.class);
        BlobContent content = mock(BlobContent.class);
        TrackingInputStream stream = new TrackingInputStream(bytes);
        when(messagingApiBlobClient.getMessageContent(messageId))
                .thenReturn(CompletableFuture.completedFuture(result));
        when(result.body()).thenReturn(content);
        when(content.byteStream()).thenReturn(stream);
        return stream;
    }

    private ImageTranslationRequest request(String messageId) {
        return new ImageTranslationRequest(
                userProfile,
                messageId,
                Instant.parse("2026-08-11T00:00:00Z"));
    }

    private TranslationWorkflowOutcome success(String sourceText, String translatedText) {
        return new TranslationWorkflowOutcome.Success(new TranslationWorkflowResult(
                sourceText,
                "en",
                "zh-TW",
                new AiExecutionResult(translatedText, "openai", "gpt-test"),
                10,
                TranslationRequestKind.IMAGE_OCR));
    }

    private ImageTranslationPipelineResult successResult(ImageTranslationOutcome outcome) {
        return ((ImageTranslationOutcome.Success) outcome).result();
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {

        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        boolean closed() {
            return closed;
        }
    }
}
