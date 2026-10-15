package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.linecorp.bot.client.base.BlobContent;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderExecutionModule;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.storage.ImageStorageResult;
import com.linetranslate.bot.service.storage.MinioStorageService;
import com.linetranslate.bot.service.translation.ImageRegionTranslation;
import com.linetranslate.bot.service.translation.TranslationRequestKind;
import com.linetranslate.bot.service.translation.TranslationWorkflowModule;
import com.linetranslate.bot.service.translation.TranslationWorkflowOutcome;
import com.linetranslate.bot.service.translation.TranslationWorkflowResult;
import com.linetranslate.bot.testing.Reachability;

/**
 * How many copies of one upload a request keeps alive, and for how long: the source raster is
 * released before the rendered image is uploaded, the preview reuses the rendered raster instead of
 * decoding it again, and nothing in the result keeps either raster after the request.
 */
@SuppressWarnings("unchecked")
class ImageTranslationPipelineMemoryTests {

    private ObjectProvider<OcrService> ocrServiceProvider;
    private OcrService ocrService;
    private TranslationWorkflowModule workflowModule;
    private MessagingApiBlobClient messagingApiBlobClient;
    private UserProfile userProfile;
    private UserPreferencesModule userPreferencesModule;
    private byte[] upload;

    @BeforeEach
    void setUp() throws Exception {
        ocrServiceProvider = mock(ObjectProvider.class);
        ocrService = mock(OcrService.class);
        workflowModule = mock(TranslationWorkflowModule.class);
        userPreferencesModule = mock(UserPreferencesModule.class);
        messagingApiBlobClient = mock(MessagingApiBlobClient.class);
        when(ocrServiceProvider.getIfAvailable()).thenReturn(ocrService);
        when(ocrService.recognize(any(InputStream.class))).thenCallRealMethod();
        userProfile = UserProfile.builder().userId("U-memory").preferredModel("gpt-test").build();
        when(userPreferencesModule.resolve(userProfile)).thenReturn(
                com.linetranslate.bot.testing.UserPreferencesFixtures.preferences(userProfile));
        upload = png(240, 120);
        Result<BlobContent> result = mock(Result.class);
        BlobContent content = mock(BlobContent.class);
        when(messagingApiBlobClient.getMessageContent("message-memory"))
                .thenReturn(CompletableFuture.completedFuture(result));
        when(result.body()).thenReturn(content);
        when(content.byteStream()).thenReturn(new ByteArrayInputStream(upload));
        when(content.mimeType()).thenReturn("image/png");
        when(ocrService.recognizeRegions(any(InputStream.class))).thenReturn(List.of(
                region("r-hello", "hello", 10, 10, 90, 35),
                region("r-world", "world", 10, 60, 90, 35)));
        doReturn(new TranslationWorkflowOutcome.Success(new TranslationWorkflowResult(
                "hello\nworld", "en", "zh-TW",
                new AiExecutionResult("greetings\nplanet", "openai", "gpt-test"),
                10, TranslationRequestKind.IMAGE_OCR, null, "faithful", "faithful-v1",
                List.of(new ImageRegionTranslation("r-hello", "greetings"),
                        new ImageRegionTranslation("r-world", "planet")))))
                .when(workflowModule).execute(any());
    }

    @Test
    void theRendererHandsItsRasterToTheUploadSoThePreviewDoesNotDecodeItAgain() throws Exception {
        MinioStorageService storage = mock(MinioStorageService.class);
        when(storage.uploadImage(any(byte[].class), anyString()))
                .thenReturn(ImageStorageResult.stored("https://storage.example/source.png"));
        when(storage.uploadTranslatedImage(any(byte[].class), anyString(), any()))
                .thenReturn(ImageStorageResult.stored("https://storage.example/translated.png"));
        TrackingValidator validator = new TrackingValidator();

        ImageTranslationOutcome outcome = pipeline(storage, validator).execute(request());

        assertThat(outcome).isInstanceOf(ImageTranslationOutcome.Success.class);
        ArgumentCaptor<byte[]> original = ArgumentCaptor.forClass(byte[].class);
        verify(storage).uploadImage(original.capture(), anyString());
        // The stored original is the array the validator kept, not a copy of it.
        assertThat(original.getValue()).isSameAs(validator.lastBytes);
        ArgumentCaptor<byte[]> rendered = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<BufferedImage> raster = ArgumentCaptor.forClass(BufferedImage.class);
        verify(storage).uploadTranslatedImage(rendered.capture(), org.mockito.ArgumentMatchers.eq("image/png"),
                raster.capture());
        assertThat(raster.getValue()).isNotNull();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(rendered.getValue()));
        assertThat(raster.getValue().getWidth()).isEqualTo(decoded.getWidth()).isEqualTo(240);
        assertThat(raster.getValue().getHeight()).isEqualTo(decoded.getHeight()).isEqualTo(120);
        for (int y = 0; y < decoded.getHeight(); y++) {
            for (int x = 0; x < decoded.getWidth(); x++) {
                assertThat(raster.getValue().getRGB(x, y)).isEqualTo(decoded.getRGB(x, y));
            }
        }
    }

    @Test
    void theSourceRasterIsCollectableWhenTheRenderedImageIsUploaded() throws Exception {
        MinioStorageService storage = mock(MinioStorageService.class);
        TrackingValidator validator = new TrackingValidator();
        AtomicBoolean sourceCollected = new AtomicBoolean();
        when(storage.uploadTranslatedImage(any(byte[].class), anyString(), any())).thenAnswer(invocation -> {
            sourceCollected.set(Reachability.collected(validator.source));
            return ImageStorageResult.stored("https://storage.example/translated.png");
        });

        ImageTranslationOutcome outcome = pipeline(storage, validator).execute(request());

        assertThat(outcome).isInstanceOf(ImageTranslationOutcome.Success.class);
        assertThat(validator.source).isNotNull();
        assertThat(sourceCollected).as("source raster collected before the upload").isTrue();
    }

    @Test
    void neitherRasterIsKeptByTheResultOnceTheRequestIsDone() throws Exception {
        WeakRasterStorage storage = new WeakRasterStorage();
        TrackingValidator validator = new TrackingValidator();

        ImageTranslationOutcome outcome = pipeline(storage, validator).execute(request());

        assertThat(storage.rendered).as("rendered raster reached the upload").isNotNull();
        // The outcome is still in hand here, so this is what the reply stage would be holding.
        assertThat(outcome).isInstanceOf(ImageTranslationOutcome.Success.class);
        assertThat(Reachability.collected(storage.rendered)).as("rendered raster").isTrue();
        assertThat(Reachability.collected(validator.source)).as("source raster").isTrue();
    }

    // --- a page that was turned a quarter -----------------------------------------------------------

    /**
     * A turned page keeps the same two rasters alive at most: the photo is dropped as soon as the
     * upright raster exists, the upright source as soon as the upright output is drawn, and that
     * output as soon as it has been turned back. The upload gets the turned-back raster alone.
     */
    @Test
    void aTurnedPageHandsTheUploadTheRestoredRasterAndHasDroppedBothUprightRastersByThen() throws Exception {
        turnedPage();
        MinioStorageService storage = mock(MinioStorageService.class);
        TrackingValidator validator = new TrackingValidator();
        UprightTrackingRenderer renderer = new UprightTrackingRenderer();
        AtomicBoolean photoCollected = new AtomicBoolean();
        AtomicBoolean uprightSourceCollected = new AtomicBoolean();
        AtomicInteger uploadedWidth = new AtomicInteger();
        AtomicInteger uploadedHeight = new AtomicInteger();
        when(storage.uploadTranslatedImage(any(byte[].class), anyString(), any())).thenAnswer(invocation -> {
            photoCollected.set(Reachability.collected(validator.source));
            uprightSourceCollected.set(Reachability.collected(renderer.uprightSource));
            BufferedImage raster = invocation.getArgument(2);
            uploadedWidth.set(raster.getWidth());
            uploadedHeight.set(raster.getHeight());
            return ImageStorageResult.stored("https://storage.example/translated.png");
        });

        ImageTranslationOutcome outcome = pipeline(storage, validator, renderer).execute(request());

        assertThat(outcome).isInstanceOf(ImageTranslationOutcome.Success.class);
        assertThat(renderer.uprightSource).isNotNull();
        assertThat(photoCollected).as("the photo's raster collected before the upload").isTrue();
        assertThat(uprightSourceCollected).as("the upright source raster collected before the upload").isTrue();
        // The photo is 240 x 120 and the page is 120 x 240: the upload is in the photo's frame.
        assertThat(uploadedWidth.get()).isEqualTo(240);
        assertThat(uploadedHeight.get()).isEqualTo(120);
    }

    @Test
    void theRasterTheUploadGetsForATurnedPageIsTheOneItsBytesEncode() throws Exception {
        turnedPage();
        MinioStorageService storage = mock(MinioStorageService.class);
        when(storage.uploadTranslatedImage(any(byte[].class), anyString(), any()))
                .thenReturn(ImageStorageResult.stored("https://storage.example/translated.png"));

        pipeline(storage, new TrackingValidator(), new UprightTrackingRenderer()).execute(request());

        ArgumentCaptor<byte[]> rendered = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<BufferedImage> raster = ArgumentCaptor.forClass(BufferedImage.class);
        verify(storage).uploadTranslatedImage(rendered.capture(), org.mockito.ArgumentMatchers.eq("image/png"),
                raster.capture());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(rendered.getValue()));
        assertThat(raster.getValue().getWidth()).isEqualTo(decoded.getWidth()).isEqualTo(240);
        assertThat(raster.getValue().getHeight()).isEqualTo(decoded.getHeight()).isEqualTo(120);
        for (int y = 0; y < decoded.getHeight(); y++) {
            for (int x = 0; x < decoded.getWidth(); x++) {
                assertThat(raster.getValue().getRGB(x, y)).isEqualTo(decoded.getRGB(x, y));
            }
        }
    }

    @Test
    void noRasterOfATurnedPageIsKeptByTheResultOnceTheRequestIsDone() throws Exception {
        turnedPage();
        WeakRasterStorage storage = new WeakRasterStorage();
        TrackingValidator validator = new TrackingValidator();
        UprightTrackingRenderer renderer = new UprightTrackingRenderer();

        ImageTranslationOutcome outcome = pipeline(storage, validator, renderer).execute(request());

        assertThat(storage.rendered).as("restored raster reached the upload").isNotNull();
        assertThat(outcome).isInstanceOf(ImageTranslationOutcome.Success.class);
        assertThat(Reachability.collected(storage.rendered)).as("restored raster").isTrue();
        assertThat(Reachability.collected(validator.source)).as("photo raster").isTrue();
        assertThat(Reachability.collected(renderer.uprightSource)).as("upright raster").isTrue();
    }

    /** The OCR reads the 240 x 120 photo as a page that is 120 x 240 when upright, turned one quarter. */
    private void turnedPage() {
        doReturn(OcrRecognition.structured(List.of(
                region("r-hello", "hello", 10, 10, 90, 35), region("r-world", "world", 10, 60, 90, 35)),
                new PageFrame(1, 240, 120, PageOrientation.Verdict.ROTATED, false, 1d)))
                .when(ocrService).recognize(any(InputStream.class));
    }

    /** The real renderer, remembering its upright source only weakly. */
    private static final class UprightTrackingRenderer extends ImageTranslationOverlayRenderer {

        private volatile WeakReference<BufferedImage> uprightSource;

        UprightTrackingRenderer() {
            super(new ImageTranslationFontProvider(Set.of("dejavu sans")));
        }

        @Override
        public RenderedImage render(ValidatedImage source, OverlaySafetyPlan plan, int restoreQuarterTurns) {
            uprightSource = new WeakReference<>(source.image());
            return super.render(source, plan, restoreQuarterTurns);
        }
    }

    private ImageTranslationPipeline pipeline(
            MinioStorageService storage, ImageInputValidator validator, ImageTranslationOverlayRenderer renderer) {
        return new ImageTranslationPipeline(
                ocrServiceProvider, workflowModule, mock(AiProviderExecutionModule.class),
                userPreferencesModule, messagingApiBlobClient, storage, validator, renderer,
                ImageTranslationProperties.defaults());
    }

    private ImageTranslationPipeline pipeline(MinioStorageService storage, ImageInputValidator validator) {
        ImageTranslationProperties limits = ImageTranslationProperties.defaults();
        return new ImageTranslationPipeline(
                ocrServiceProvider, workflowModule, mock(AiProviderExecutionModule.class),
                userPreferencesModule, messagingApiBlobClient, storage, validator,
                new ImageTranslationOverlayRenderer(new ImageTranslationFontProvider(Set.of("dejavu sans"))),
                limits);
    }

    private ImageTranslationRequest request() {
        return new ImageTranslationRequest(userProfile, "message-memory", Instant.parse("2026-08-11T00:00:00Z"));
    }

    /**
     * Storage that remembers the rendered raster only weakly. A Mockito mock would not do: it
     * keeps the arguments of the call that last used a stub.
     */
    private static final class WeakRasterStorage extends MinioStorageService {

        private volatile WeakReference<BufferedImage> rendered;

        @SuppressWarnings("unchecked")
        WeakRasterStorage() {
            super(mock(ObjectProvider.class), mock(ObjectProvider.class), "bucket", false, 1_000L,
                    mock(ObjectProvider.class));
        }

        @Override
        public ImageStorageResult uploadImage(byte[] imageBytes, String contentType) {
            return ImageStorageResult.stored("https://storage.example/source.png");
        }

        @Override
        public ImageStorageResult uploadTranslatedImage(
                byte[] imageBytes, String contentType, BufferedImage raster) {
            rendered = new WeakReference<>(raster);
            return ImageStorageResult.stored("https://storage.example/translated.png");
        }
    }

    /** Real validation that remembers its result without keeping the raster alive. */
    private static final class TrackingValidator extends ImageInputValidator {

        private volatile WeakReference<BufferedImage> source;
        private volatile byte[] lastBytes;

        TrackingValidator() {
            super(ImageTranslationProperties.defaults());
        }

        @Override
        public ValidatedImage validate(byte[] bytes, String claimedContentType) {
            ValidatedImage validated = super.validate(bytes, claimedContentType);
            source = new WeakReference<>(validated.image());
            lastBytes = validated.bytes();
            return validated;
        }
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static OcrRegion region(String id, String text, int x, int y, int width, int height) {
        return new OcrRegion(id, text, List.of(
                new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height)),
                List.of(), .98f, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage("en", .98f)), 0);
    }
}
