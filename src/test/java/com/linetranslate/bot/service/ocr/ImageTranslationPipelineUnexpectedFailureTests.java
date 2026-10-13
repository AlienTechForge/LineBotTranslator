package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;

import com.google.api.gax.grpc.GrpcStatusCode;
import com.google.api.gax.rpc.UnavailableException;
import com.google.cloud.vision.v1.ImageAnnotatorClient;

import com.linecorp.bot.client.base.BlobContent;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiProviderAdapter;
import com.linetranslate.bot.service.ai.AiProviderException;
import com.linetranslate.bot.service.ai.AiProviderOperation;
import com.linetranslate.bot.service.ai.AiProviderRequest;
import com.linetranslate.bot.service.ai.AiProviderResponse;
import com.linetranslate.bot.service.ai.AiProviderExecutionModule;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.settings.RuntimeSettings;
import com.linetranslate.bot.service.storage.ImageStorageResult;
import com.linetranslate.bot.service.storage.MinioStorageService;
import com.linetranslate.bot.service.translation.ImageRegionTranslation;
import com.linetranslate.bot.service.translation.ImageRegionTranslationInput;
import com.linetranslate.bot.service.translation.StructuredTranslationException;
import com.linetranslate.bot.service.translation.TranslationPromptFactory;
import com.linetranslate.bot.service.translation.TranslationRequestKind;
import com.linetranslate.bot.service.translation.TranslationWorkflowModule;
import com.linetranslate.bot.service.translation.TranslationWorkflowOutcome;
import com.linetranslate.bot.service.translation.TranslationWorkflowRequest;
import com.linetranslate.bot.service.translation.TranslationWorkflowResult;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.grpc.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * A stage that throws something the pipeline does not know is a defect: the image still degrades
 * as it does for an outage, but the defect is logged once at ERROR with its stack frames and
 * counted, while the failures a stage is known to end in keep their WARN line.
 */
@SuppressWarnings("unchecked")
class ImageTranslationPipelineUnexpectedFailureTests {

    private static final String SEEDED = "seeded-private-ocr-text https://storage.example/x?token=seeded-secret";

    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(ImageTranslationPipeline.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private OcrService ocrService;
    private TranslationWorkflowModule workflowModule;
    private AiProviderExecutionModule aiProviderExecutionModule;
    private MinioStorageService minioStorageService;
    private MessagingApiBlobClient messagingApiBlobClient;
    private ImageTranslationOverlayRenderer renderer;
    private UserPreferencesModule userPreferencesModule;
    private ImageTranslationProperties limits;
    private ImageTranslationPipeline pipeline;
    private UserProfile userProfile;

    @BeforeEach
    void setUp() throws Exception {
        logs.start();
        logger.addAppender(logs);
        ocrService = mock(OcrService.class);
        workflowModule = mock(TranslationWorkflowModule.class);
        aiProviderExecutionModule = mock(AiProviderExecutionModule.class);
        userPreferencesModule = mock(UserPreferencesModule.class);
        messagingApiBlobClient = mock(MessagingApiBlobClient.class);
        minioStorageService = mock(MinioStorageService.class);
        renderer = mock(ImageTranslationOverlayRenderer.class);
        userProfile = UserProfile.builder().userId("U-unexpected").preferredModel("gpt-test").build();
        when(userPreferencesModule.resolve(userProfile)).thenReturn(
                com.linetranslate.bot.testing.UserPreferencesFixtures.preferences(userProfile));
        limits = ImageTranslationProperties.defaults();
        pipeline = pipeline(ocrService, aiProviderExecutionModule, new OcrRegionQualificationPolicy());
        when(minioStorageService.uploadImage(any(byte[].class), anyString()))
                .thenReturn(ImageStorageResult.notStored());
        when(workflowModule.execute(any())).thenAnswer(invocation -> translated(invocation.getArgument(0)));
        image("message-1", whitePng(660, 400));
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void anUnexpectedRecognitionErrorIsLoggedAtErrorAndCountedButStillDegradesTheImage() {
        when(ocrService.recognize(any(InputStream.class))).thenThrow(new IndexOutOfBoundsException(SEEDED));

        ImageTranslationOutcome outcome = pipeline.execute(request());

        assertThat(outcome).isEqualTo(new ImageTranslationOutcome.Failure(
                ImageTranslationFailureStage.RECOGNITION));
        assertReportedAsDefect("recognition", IndexOutOfBoundsException.class);
    }

    @Test
    void anUnexpectedTranslationErrorIsLoggedAtErrorAndCountedButStillDegradesTheImage() {
        when(ocrService.recognize(any(InputStream.class))).thenReturn(OcrRecognition.plain("hello"));
        doThrow(new NullPointerException(SEEDED)).when(workflowModule).execute(any());

        ImageTranslationOutcome outcome = pipeline.execute(request());

        assertThat(outcome).isEqualTo(new ImageTranslationOutcome.Failure(
                ImageTranslationFailureStage.TRANSLATION));
        assertReportedAsDefect("translation", NullPointerException.class);
    }

    @Test
    void anUnexpectedStorageErrorIsLoggedAtErrorAndCountedButTranslationContinues() {
        when(minioStorageService.uploadImage(any(byte[].class), anyString()))
                .thenThrow(new IllegalStateException(SEEDED));
        when(ocrService.recognize(any(InputStream.class))).thenReturn(OcrRecognition.plain("hello"));

        ImageTranslationOutcome outcome = pipeline.execute(request());

        assertThat(outcome).isInstanceOfSatisfying(ImageTranslationOutcome.Success.class,
                success -> assertThat(success.result().context().storage().stored()).isFalse());
        assertReportedAsDefect("storage", IllegalStateException.class);
    }

    @Test
    void anUnexpectedRenderingErrorIsLoggedAtErrorAndCountedButTheReplyFallsBackToText() {
        when(ocrService.recognize(any(InputStream.class))).thenReturn(OcrRecognition.structured(List.of(region())));
        when(renderer.render(any(), any())).thenThrow(new NullPointerException(SEEDED));

        ImageTranslationOutcome outcome = pipeline.execute(request());

        assertThat(outcome).isInstanceOfSatisfying(ImageTranslationOutcome.Success.class, success -> {
            assertThat(success.result().overlayDisposition()).isEqualTo(ImageOverlayDisposition.SAFETY_DEGRADED);
            assertThat(success.result().degradation().count(OverlayDegradationReason.OTHER)).isEqualTo(1);
        });
        assertReportedAsDefect("rendering", NullPointerException.class);
    }

    @Test
    void theErrorLineCarriesTheStackFramesAndCauseTypesButNoMessage() {
        when(ocrService.recognize(any(InputStream.class))).thenThrow(
                new IllegalStateException(SEEDED, new IllegalArgumentException("seeded-cause-detail")));

        pipeline.execute(request());

        ILoggingEvent error = only(Level.ERROR);
        String trace = ThrowableProxyUtil.asString(error.getThrowableProxy());
        assertThat(trace)
                .contains("java.lang.IllegalStateException")
                .contains("Caused by")
                .contains("java.lang.IllegalArgumentException")
                .contains(getClass().getName());
        assertThat(error.getFormattedMessage() + trace).doesNotContain("seeded");
    }

    @Test
    void aBugInTheVisionAdapterIsReportedAsADefectWhileAiRecognitionStillServesTheImage() {
        // The client stub answers with null, so the adapter's own parsing throws a NullPointerException.
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        when(client.batchAnnotateImages(anyList())).thenReturn(null);
        when(aiProviderExecutionModule.processImage(any(), anyString(), anyString()))
                .thenReturn(new AiExecutionResult("fallback text", "openai", "gpt-image"));
        pipeline = pipeline(new GoogleVisionOcrService(providerOf(client)), aiProviderExecutionModule,
                new OcrRegionQualificationPolicy());

        ImageTranslationOutcome outcome = pipeline.execute(request());

        assertThat(outcome).isInstanceOfSatisfying(ImageTranslationOutcome.Success.class,
                success -> assertThat(success.result().context().recognizedText()).isEqualTo("fallback text"));
        assertReportedAsDefect("recognition", OcrDefectException.class, NullPointerException.class);
        assertThat(ThrowableProxyUtil.asString(only(Level.ERROR).getThrowableProxy()))
                .contains(GoogleVisionOcrService.class.getName());
    }

    @Test
    void aVisionProviderFailureStillFallsBackToAiRecognitionWithoutBeingReportedAsADefect() {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        when(client.batchAnnotateImages(anyList())).thenThrow(new UnavailableException(
                new RuntimeException("provider down"), GrpcStatusCode.of(Status.Code.UNAVAILABLE), true));
        when(aiProviderExecutionModule.processImage(any(), anyString(), anyString()))
                .thenReturn(new AiExecutionResult("fallback text", "openai", "gpt-image"));
        pipeline = pipeline(new GoogleVisionOcrService(providerOf(client)), aiProviderExecutionModule,
                new OcrRegionQualificationPolicy());

        ImageTranslationOutcome outcome = pipeline.execute(request());

        assertThat(outcome).isInstanceOf(ImageTranslationOutcome.Success.class);
        assertThat(logs.list).filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).isEmpty();
        assertNothingCounted();
    }

    @Test
    void aBugInProviderExecutionDuringRecognitionIsReportedWithItsRealStack() {
        when(ocrService.recognize(any(InputStream.class))).thenThrow(new OcrProcessingException("vision down"));
        pipeline = pipeline(ocrService, providerModuleWhoseAdapterThrows(new NullPointerException(SEEDED)),
                new OcrRegionQualificationPolicy());

        ImageTranslationOutcome outcome = pipeline.execute(request());

        assertThat(outcome).isEqualTo(new ImageTranslationOutcome.Failure(
                ImageTranslationFailureStage.RECOGNITION));
        assertReportedAsDefect("recognition", AiProviderException.class, NullPointerException.class);
        assertThat(ThrowableProxyUtil.asString(only(Level.ERROR).getThrowableProxy()))
                .contains(ImageTranslationPipelineUnexpectedFailureTests.class.getName());
    }

    @Test
    void aBugInProviderExecutionDuringTranslationIsReportedWithItsRealStack() {
        when(ocrService.recognize(any(InputStream.class))).thenReturn(OcrRecognition.plain("hello"));
        AiExecutionOutcome failed = providerModuleWhoseAdapterThrows(new NullPointerException(SEEDED))
                .translateTextOutcome(
                        com.linetranslate.bot.testing.UserPreferencesFixtures.preferences(userProfile),
                        "hello", "zh-TW");
        var executionFailure = ((AiExecutionOutcome.Failure) failed).failure();
        doReturn(new TranslationWorkflowOutcome.Failure(executionFailure))
                .when(workflowModule).execute(any());

        ImageTranslationOutcome outcome = pipeline.execute(request());

        assertThat(outcome).isEqualTo(new ImageTranslationOutcome.Failure(
                ImageTranslationFailureStage.TRANSLATION, executionFailure));
        assertReportedAsDefect("translation", AiProviderException.class, NullPointerException.class);
    }

    @Test
    void aProviderOutageDuringTranslationIsNotReportedAsADefect() {
        when(ocrService.recognize(any(InputStream.class))).thenReturn(OcrRecognition.plain("hello"));
        AiExecutionOutcome failed = providerModuleWhoseAdapterThrows(new AiProviderException(
                AiProviderException.Outcome.RATE_LIMITED, "openrouter", "test-model", "HTTP_429",
                "correlation-1", 429, null))
                .translateTextOutcome(
                        com.linetranslate.bot.testing.UserPreferencesFixtures.preferences(userProfile),
                        "hello", "zh-TW");
        doReturn(new TranslationWorkflowOutcome.Failure(((AiExecutionOutcome.Failure) failed).failure()))
                .when(workflowModule).execute(any());

        ImageTranslationOutcome outcome = pipeline.execute(request());

        assertThat(outcome).isInstanceOf(ImageTranslationOutcome.Failure.class);
        assertThat(logs.list).filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).isEmpty();
        assertNothingCounted();
    }

    @Test
    void anOcrServiceThatReturnsNothingIsAnUnexpectedRecognitionFailure() {
        when(ocrService.recognize(any(InputStream.class))).thenReturn(null);

        ImageTranslationOutcome outcome = pipeline.execute(request());

        assertThat(outcome).isEqualTo(new ImageTranslationOutcome.Failure(
                ImageTranslationFailureStage.RECOGNITION));
        assertReportedAsDefect("recognition", NullPointerException.class);
    }

    @Test
    void aDefectWhileQualifyingTheRecognizedRegionsIsAnUnexpectedRecognitionFailure() {
        when(ocrService.recognize(any(InputStream.class))).thenReturn(OcrRecognition.structured(List.of(region())));
        OcrRegionQualificationPolicy policy = mock(OcrRegionQualificationPolicy.class);
        when(policy.decide(any(), anyFloat())).thenThrow(new IllegalStateException(SEEDED));
        pipeline = pipeline(ocrService, aiProviderExecutionModule, policy);

        ImageTranslationOutcome outcome = pipeline.execute(request());

        assertThat(outcome).isEqualTo(new ImageTranslationOutcome.Failure(
                ImageTranslationFailureStage.RECOGNITION));
        assertReportedAsDefect("recognition", IllegalStateException.class);
    }

    @Test
    void aNullQualificationDecisionIsAnUnexpectedRecognitionFailure() {
        when(ocrService.recognize(any(InputStream.class))).thenReturn(OcrRecognition.structured(List.of(region())));
        OcrRegionQualificationPolicy policy = mock(OcrRegionQualificationPolicy.class);
        when(policy.decide(any(), anyFloat())).thenReturn(null);
        pipeline = pipeline(ocrService, aiProviderExecutionModule, policy);

        ImageTranslationOutcome outcome = pipeline.execute(request());

        assertThat(outcome).isEqualTo(new ImageTranslationOutcome.Failure(
                ImageTranslationFailureStage.RECOGNITION));
        assertReportedAsDefect("recognition", NullPointerException.class);
    }

    @ParameterizedTest
    @MethodSource("expectedRecognitionFailures")
    void aKnownRecognitionFailureKeepsItsWarnLineAndIsNotCounted(RuntimeException expected) {
        when(ocrService.recognize(any(InputStream.class))).thenThrow(new OcrTimeoutException("slow", null));
        when(aiProviderExecutionModule.processImage(any(), anyString(), anyString())).thenThrow(expected);

        ImageTranslationOutcome outcome = pipeline.execute(request());

        assertThat(outcome).isEqualTo(new ImageTranslationOutcome.Failure(
                ImageTranslationFailureStage.RECOGNITION));
        assertKnownFailureWarned("Image recognition failed: user=", expected);
    }

    @ParameterizedTest
    @MethodSource("expectedTranslationFailures")
    void aKnownTranslationFailureKeepsItsWarnLineAndIsNotCounted(RuntimeException expected) {
        when(ocrService.recognize(any(InputStream.class))).thenReturn(OcrRecognition.plain("hello"));
        doThrow(expected).when(workflowModule).execute(any());

        ImageTranslationOutcome outcome = pipeline.execute(request());

        assertThat(outcome).isEqualTo(new ImageTranslationOutcome.Failure(
                ImageTranslationFailureStage.TRANSLATION));
        assertKnownFailureWarned("Image translation workflow failed: user=", expected);
    }

    @Test
    void aRefusedOversizedRenderingKeepsItsWarnLineAndIsNotCounted() {
        when(ocrService.recognize(any(InputStream.class))).thenReturn(OcrRecognition.structured(List.of(region())));
        RuntimeException expected = new RenderedImageEncodingException(
                "Translated image could not be encoded", new java.io.IOException("cap"));
        when(renderer.render(any(), any())).thenThrow(expected);

        ImageTranslationOutcome outcome = pipeline.execute(request());

        assertThat(outcome).isInstanceOfSatisfying(ImageTranslationOutcome.Success.class,
                success -> assertThat(success.result().overlayDisposition())
                        .isEqualTo(ImageOverlayDisposition.SAFETY_DEGRADED));
        assertKnownFailureWarned("Translated image rendering degraded to text: failure=", expected);
    }

    static Stream<RuntimeException> expectedRecognitionFailures() {
        return Stream.of(
                new OcrProcessingException("ai recognition returned no result"),
                new AiProviderException(AiProviderException.Outcome.TRANSPORT_ERROR,
                        "openai", "gpt-image", "IO_FAILURE", "correlation-image", -1, null));
    }

    static Stream<RuntimeException> expectedTranslationFailures() {
        return Stream.of(
                new StructuredTranslationException(StructuredTranslationException.PROVIDER_FAILED, "mapping"),
                new AiProviderException(AiProviderException.Outcome.RATE_LIMITED,
                        "openai", "gpt-test", "HTTP_429", "correlation-text", 429, null),
                new DataAccessResourceFailureException("mongo down"));
    }

    private ImageTranslationPipeline pipeline(
            OcrService ocr, AiProviderExecutionModule providerModule, OcrRegionQualificationPolicy policy) {
        ObjectProvider<OcrService> ocrServiceProvider = mock(ObjectProvider.class);
        when(ocrServiceProvider.getIfAvailable()).thenReturn(ocr);
        return new ImageTranslationPipeline(
                ocrServiceProvider, workflowModule, providerModule, userPreferencesModule,
                messagingApiBlobClient, minioStorageService, new ImageInputValidator(limits), renderer,
                limits, policy, new OcrSourceLanguageResolver(),
                new OverlaySafetyPolicy(), new TranslationPromptFactory(), meters);
    }

    private static AiProviderExecutionModule providerModuleWhoseAdapterThrows(RuntimeException failure) {
        AiProviderAdapter adapter = new AiProviderAdapter() {
            public String providerName() { return "openrouter"; }
            public String defaultModel() { return "test-model"; }
            public Set<String> availableModels() { return Set.of("test-model"); }
            public Set<AiProviderOperation> capabilities() { return Set.of(AiProviderOperation.values()); }
            public AiProviderResponse execute(AiProviderRequest request) { throw failure; }
        };
        return new AiProviderExecutionModule(List.of(adapter),
                () -> new RuntimeSettings("en", "zh-TW", "test-model", true, 2, 1, null, "U-admin",
                        RuntimeSettings.Source.PERSISTED),
                (operation, outcome) -> { });
    }

    private void assertReportedAsDefect(String stage, Class<? extends Throwable> type, Class<?>... causes) {
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN).isEmpty();
        ILoggingEvent error = only(Level.ERROR);
        String summary = type.getSimpleName()
                + Stream.of(causes).map(cause -> "<-" + cause.getSimpleName()).collect(Collectors.joining());
        assertThat(error.getFormattedMessage()).isEqualTo(
                "Image translation stage failed unexpectedly: stage=%s, failure=%s".formatted(stage, summary));
        // The stack frames survive so the defect can be located; the message does not.
        assertThat(error.getThrowableProxy()).isNotNull();
        assertThat(error.getThrowableProxy().getMessage()).isEqualTo(type.getName());
        assertThat(error.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
        String trace = ThrowableProxyUtil.asString(error.getThrowableProxy());
        for (Class<?> cause : causes) {
            assertThat(trace).contains("Caused by: com.linetranslate.bot.logging.SafeLog$RedactedFailure: "
                    + cause.getName());
        }
        assertThat(error.getFormattedMessage() + trace).doesNotContain("seeded").doesNotContain("https://");
        for (String counted : List.of("recognition", "storage", "translation", "rendering")) {
            assertThat(unexpectedCount(counted)).as("unexpected{stage=%s}", counted)
                    .isEqualTo(counted.equals(stage) ? 1d : 0d);
        }
    }

    private void assertKnownFailureWarned(String warning, RuntimeException expected) {
        assertThat(logs.list).filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.ERROR)).isEmpty();
        ILoggingEvent warn = only(Level.WARN);
        assertThat(warn.getFormattedMessage())
                .startsWith(warning)
                .contains("failure=" + expected.getClass().getSimpleName());
        // A known failure keeps the type-only line it always had: no stack trace.
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        assertNothingCounted();
    }

    private static ObjectProvider<ImageAnnotatorClient> providerOf(ImageAnnotatorClient client) {
        ObjectProvider<ImageAnnotatorClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }

    private void assertNothingCounted() {
        for (String stage : List.of("recognition", "storage", "translation", "rendering")) {
            assertThat(unexpectedCount(stage)).isZero();
        }
    }

    private ILoggingEvent only(Level level) {
        List<ILoggingEvent> matching = logs.list.stream().filter(event -> event.getLevel() == level).toList();
        assertThat(matching).as("%s events", level).hasSize(1);
        return matching.get(0);
    }

    private double unexpectedCount(String stage) {
        return meters.get("image.translation.unexpected").tag("stage", stage).counter().count();
    }

    private static OcrRegion region() {
        return new OcrRegion("r1", "Hello",
                List.of(new OcrPoint(10, 10), new OcrPoint(310, 10), new OcrPoint(310, 34), new OcrPoint(10, 34)),
                List.of(), .98f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("en", .98f)), 0);
    }

    private static TranslationWorkflowOutcome translated(TranslationWorkflowRequest request) {
        List<ImageRegionTranslation> translations = request.imageRegions().stream()
                .filter(ImageRegionTranslationInput::translatable)
                .map(region -> new ImageRegionTranslation(region.regionId(), "T " + region.regionId()))
                .toList();
        return new TranslationWorkflowOutcome.Success(new TranslationWorkflowResult(
                request.sourceText(), "en", "zh-TW",
                new AiExecutionResult("translated", "openai", "gpt-test"),
                10, TranslationRequestKind.IMAGE_OCR, null, "faithful", "faithful-v1", translations));
    }

    private void image(String messageId, byte[] bytes) {
        Result<BlobContent> result = mock(Result.class);
        BlobContent content = mock(BlobContent.class);
        when(messagingApiBlobClient.getMessageContent(messageId))
                .thenReturn(CompletableFuture.completedFuture(result));
        when(result.body()).thenReturn(content);
        when(content.byteStream()).thenReturn(new ByteArrayInputStream(bytes));
        when(content.mimeType()).thenReturn("image/png");
    }

    private static byte[] whitePng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private ImageTranslationRequest request() {
        return new ImageTranslationRequest(userProfile, "message-1", Instant.parse("2026-08-11T00:00:00Z"));
    }
}
