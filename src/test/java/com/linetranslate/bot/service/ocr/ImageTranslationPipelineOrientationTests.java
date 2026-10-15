package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
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
import com.linetranslate.bot.service.translation.ImageRegionTranslationInput;
import com.linetranslate.bot.service.translation.TranslationPromptFactory;
import com.linetranslate.bot.service.translation.TranslationRequestKind;
import com.linetranslate.bot.service.translation.TranslationWorkflowModule;
import com.linetranslate.bot.service.translation.TranslationWorkflowOutcome;
import com.linetranslate.bot.service.translation.TranslationWorkflowRequest;
import com.linetranslate.bot.service.translation.TranslationWorkflowResult;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * A page photographed a quarter turn off, end to end: the OCR reads it in the upright frame, the
 * pipeline turns the raster into that frame, draws there and delivers the result in the frame of
 * the photo, so the user gets the picture they sent with its text replaced.
 */
@SuppressWarnings("unchecked")
class ImageTranslationPipelineOrientationTests {

    /** The photo: a portrait page lying on its side, 300 wide and 200 tall. */
    private static final int WIDTH = 300;
    private static final int HEIGHT = 200;
    /** The page as it reads: 200 wide and 300 tall, and the photo is that page turned one quarter clockwise. */
    private static final RotatedPageFixtures PHOTO = new RotatedPageFixtures(HEIGHT, WIDTH, 1);
    private static final Rectangle LINE_A = new Rectangle(20, 40, 160, 30);
    private static final Rectangle LINE_B = new Rectangle(20, 120, 160, 30);
    private static final Map<String, String> TRANSLATIONS = Map.of(
            "line-a", "Greetings everyone", "line-b", "Good evening");

    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(ImageTranslationPipeline.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private TranslationWorkflowModule workflowModule;
    private AiProviderExecutionModule aiModule;
    private MessagingApiBlobClient blobClient;
    private MinioStorageService storage;
    private UserPreferencesModule preferences;
    private UserProfile userProfile;
    private BlobContent content;
    private byte[] photoBytes;
    private BufferedImage photo;
    private final List<byte[]> storedTranslated = new ArrayList<>();
    private final List<byte[]> storedOriginal = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        logs.start();
        logger.addAppender(logs);
        workflowModule = mock(TranslationWorkflowModule.class);
        aiModule = mock(AiProviderExecutionModule.class);
        blobClient = mock(MessagingApiBlobClient.class);
        storage = mock(MinioStorageService.class);
        preferences = mock(UserPreferencesModule.class);
        userProfile = UserProfile.builder().userId("U-orientation").preferredModel("test-model").build();
        when(preferences.resolve(userProfile)).thenReturn(
                com.linetranslate.bot.testing.UserPreferencesFixtures.preferences(userProfile));
        photo = photograph();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(photo, "png", encoded);
        photoBytes = encoded.toByteArray();
        Result<BlobContent> result = mock(Result.class);
        content = mock(BlobContent.class);
        when(blobClient.getMessageContent("message-orientation"))
                .thenReturn(CompletableFuture.completedFuture(result));
        when(result.body()).thenReturn(content);
        when(content.byteStream()).thenAnswer(invocation -> new ByteArrayInputStream(photoBytes));
        when(content.mimeType()).thenReturn("image/png");
        when(storage.uploadImage(any(byte[].class), anyString())).thenAnswer(invocation -> {
            storedOriginal.add(invocation.getArgument(0));
            return ImageStorageResult.stored("https://storage.example/source.png");
        });
        when(storage.uploadTranslatedImage(any(byte[].class), anyString(), any())).thenAnswer(invocation -> {
            storedTranslated.add(invocation.getArgument(0));
            return ImageStorageResult.stored("https://storage.example/translated.png");
        });
        when(workflowModule.execute(any())).thenAnswer(invocation -> {
            TranslationWorkflowRequest request = invocation.getArgument(0);
            return new TranslationWorkflowOutcome.Success(new TranslationWorkflowResult(
                    request.sourceText(), "en", "zh-TW", new AiExecutionResult("translated", "test-provider", "test-model"),
                    10, TranslationRequestKind.IMAGE_OCR, null, "faithful", "faithful-v1",
                    request.imageRegions().stream().filter(ImageRegionTranslationInput::translatable)
                            .map(region -> new ImageRegionTranslation(region.regionId(),
                                    TRANSLATIONS.get(region.regionId()))).toList()));
        });
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logs.stop();
    }

    // --- the normal path: one OCR call, a photo that comes back in its own frame -------------------

    @Test
    void aTurnedPageIsTranslatedUprightAndDeliveredInTheFrameOfThePhoto() throws Exception {
        ScriptedOcr ocr = new ScriptedOcr(rotated(.98f, "hello world", "good morning", 1d, false));

        ImageTranslationOutcome outcome = pipeline(ocr, false, new ImageTranslationOverlayRenderer(fonts()))
                .execute(request());

        assertThat(outcome).isInstanceOf(ImageTranslationOutcome.Success.class);
        assertThat(ocr.calls()).as("ocrCalls").isEqualTo(1);
        assertThat(ocr.received().get(0)).as("the OCR is given the photo as it was sent").isEqualTo(photoBytes);
        assertThat(storedOriginal).singleElement().isEqualTo(photoBytes);
        assertThat(storedTranslated).hasSize(1);

        BufferedImage delivered = ImageIO.read(new ByteArrayInputStream(storedTranslated.get(0)));
        // The photo's own dimensions, not the upright page's.
        assertThat(delivered.getWidth()).isEqualTo(WIDTH);
        assertThat(delivered.getHeight()).isEqualTo(HEIGHT);

        Rectangle lineA = inPhoto(LINE_A);
        Rectangle lineB = inPhoto(LINE_B);
        // Outside the cells and their padding the photo is untouched, pixel for pixel.
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (near(lineA, x, y, 12) || near(lineB, x, y, 12)) continue;
                assertThat(delivered.getRGB(x, y)).as("(%d,%d)", x, y).isEqualTo(photo.getRGB(x, y));
            }
        }
        // The overlay sits where the source text was in the photo, and nowhere else.
        Rectangle changedA = changed(delivered, lineA, 12);
        Rectangle changedB = changed(delivered, lineB, 12);
        assertThat(changedA).as("line A").isNotNull();
        assertThat(changedB).as("line B").isNotNull();
        assertThat(grown(lineA, 12).contains(changedA)).isTrue();
        assertThat(grown(lineB, 12).contains(changedB)).isTrue();
        // The lines of the photo run down it, and so does the new text.
        assertThat(changedA.height).isGreaterThan(changedA.width);

        // Turned back into the upright frame, the text lies along the line, the way it reads.
        BufferedImage upright = QuarterTurns.toUpright(delivered, 1);
        Rectangle ink = inkBounds(upright, LINE_A);
        assertThat(ink.width).isGreaterThan(ink.height * 3);
        assertThat(LINE_A.contains(ink)).isTrue();
        assertThat(logLine("Image translation timing")).contains("orientationQuarterTurns=1", "ocrCalls=1");
    }

    @Test
    void thePageIsDrawnUprightAndOnlyTheOutputIsTurnedBack() {
        ScriptedOcr ocr = new ScriptedOcr(rotated(.98f, "hello world", "good morning", 1d, false));
        RecordingRenderer renderer = new RecordingRenderer();

        pipeline(ocr, false, renderer).execute(request());

        assertThat(renderer.restoreQuarterTurns.get()).isEqualTo(1);
        assertThat(renderer.sourceWidth.get()).as("drawn on the upright raster").isEqualTo(HEIGHT);
        assertThat(renderer.sourceHeight.get()).isEqualTo(WIDTH);
    }

    @Test
    void aPageThatWasNotTurnedGoesThroughTheUnchangedPath() {
        ScriptedOcr ocr = new ScriptedOcr(OcrRecognition.structured(List.of(
                upright("line-a", "hello world", LINE_A, .98f), upright("line-b", "good morning", LINE_B, .98f))));
        RecordingRenderer renderer = new RecordingRenderer();

        ImageTranslationOutcome outcome = pipeline(ocr, true, renderer).execute(request());

        assertThat(outcome).isInstanceOf(ImageTranslationOutcome.Success.class);
        assertThat(ocr.calls()).isEqualTo(1);
        assertThat(renderer.restoreQuarterTurns.get()).as("nothing is turned").isZero();
        assertThat(renderer.sourceWidth.get()).as("the photo as it came").isEqualTo(WIDTH);
        assertThat(renderer.sourceHeight.get()).isEqualTo(HEIGHT);
        assertThat(logLine("Image translation timing")).contains("orientationQuarterTurns=0", "ocrCalls=1");
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                .noneMatch(line -> line.startsWith("Page orientation"));
    }

    @Test
    void aFrameThatDoesNotMatchTheImageIsNotTurnedAndRendersInTheFrameOfThePhoto() {
        // The provider says the page was 301 x 200: one pixel more than the photo that was sent.
        List<OcrRegion> upright = List.of(upright("line-a", "hello world", LINE_A, .98f));
        ScriptedOcr ocr = new ScriptedOcr(OcrRecognition.structured(upright,
                new PageFrame(1, WIDTH + 1, HEIGHT, PageOrientation.Verdict.ROTATED, false, 1d)));
        RecordingRenderer renderer = new RecordingRenderer();

        ImageTranslationOutcome outcome = pipeline(ocr, false, renderer).execute(request());

        assertThat(outcome).isInstanceOf(ImageTranslationOutcome.Success.class);
        assertThat(renderer.restoreQuarterTurns.get()).isZero();
        assertThat(renderer.sourceWidth.get()).isEqualTo(WIDTH);
        assertThat(logLine("Page orientation normalization skipped"))
                .contains("verdict=frame-mismatch", "pageWidth=301", "imageWidth=300");
        // The regions were moved back by the exact inverse, so they are the provider's again.
        assertThat(renderer.regions.get()).singleElement().satisfies(region -> {
            assertThat(region.polygon()).isEqualTo(QuarterTurns.fromUpright(upright, 1, WIDTH + 1, HEIGHT).get(0).polygon());
            assertThat(region.writingMode()).isEqualTo(WritingMode.ROTATED_90);
        });
    }

    // --- the optional second reading ----------------------------------------------------------------

    @Test
    void aBetterSecondReadingOfThePoorlyReadPageFromItsUprightRasterIsAccepted() throws Exception {
        OcrRecognition poor = rotated(.7f, "hello wurld", "good mornnig", .0, true);
        OcrRecognition better = OcrRecognition.structured(List.of(
                upright("line-a", "hello world", LINE_A, .98f), upright("line-b", "good morning", LINE_B, .98f)),
                new PageFrame(0, HEIGHT, WIDTH, PageOrientation.Verdict.UPRIGHT, false, 1d));
        ScriptedOcr ocr = new ScriptedOcr(poor, better);

        ImageTranslationOutcome outcome = pipeline(ocr, true, new ImageTranslationOverlayRenderer(fonts()))
                .execute(request());

        assertThat(outcome).isInstanceOf(ImageTranslationOutcome.Success.class);
        assertThat(ocr.calls()).isEqualTo(2);
        BufferedImage second = ImageIO.read(new ByteArrayInputStream(ocr.received().get(1)));
        assertThat(second.getWidth()).as("the second call is given the upright raster").isEqualTo(HEIGHT);
        assertThat(second.getHeight()).isEqualTo(WIDTH);
        assertThat(translatedSources()).containsExactly("hello world", "good morning");
        assertThat(logLine("Page orientation retry")).contains("outcome=accepted", "retryVerdict=UPRIGHT");
        assertThat(logLine("Image translation timing")).contains("orientationQuarterTurns=1", "ocrCalls=2");
        // The accepted reading is still delivered in the frame of the photo.
        BufferedImage delivered = ImageIO.read(new ByteArrayInputStream(storedTranslated.get(0)));
        assertThat(delivered.getWidth()).isEqualTo(WIDTH);
        assertThat(delivered.getHeight()).isEqualTo(HEIGHT);
        assertThat(changed(delivered, inPhoto(LINE_A), 12)).isNotNull();
    }

    @Test
    void aSecondReadingThatIsNoBetterIsRejectedAndTheFirstKept() {
        OcrRecognition poor = rotated(.7f, "hello wurld", "good mornnig", .0, true);
        OcrRecognition same = OcrRecognition.structured(List.of(
                upright("line-a", "hello world", LINE_A, .7f), upright("line-b", "good morning", LINE_B, .7f)),
                new PageFrame(0, HEIGHT, WIDTH, PageOrientation.Verdict.UPRIGHT, false, .0));
        ScriptedOcr ocr = new ScriptedOcr(poor, same);

        ImageTranslationOutcome outcome = pipeline(ocr, true, new ImageTranslationOverlayRenderer(fonts()))
                .execute(request());

        assertThat(outcome).isInstanceOf(ImageTranslationOutcome.Success.class);
        assertThat(ocr.calls()).isEqualTo(2);
        assertThat(translatedSources()).containsExactly("hello wurld", "good mornnig");
        assertThat(logLine("Page orientation retry")).contains("outcome=rejected", "reason=no-gain");
    }

    @Test
    void aSecondReadingNeedsToBeTenPercentBetterAndNoLess() {
        // 20 reliable letters in the first reading; 21 is 5 percent more, 22 is exactly 10 percent.
        for (int extra : new int[] {0, 1, 2, 3}) {
            logs.list.clear();
            OcrRecognition first = OcrRecognition.structured(List.of(
                    upright("line-a", "abcdefghij abcdefghij", LINE_A, .98f),
                    upright("line-b", "poorly read", LINE_B, .7f)),
                    new PageFrame(1, WIDTH, HEIGHT, PageOrientation.Verdict.ROTATED, true, .5));
            OcrRecognition second = OcrRecognition.structured(List.of(
                    upright("line-a", "abcdefghij abcdefghij", LINE_A, .98f),
                    upright("line-b", "b".repeat(extra), LINE_B, .98f)),
                    new PageFrame(0, HEIGHT, WIDTH, PageOrientation.Verdict.UPRIGHT, false, 1d));

            pipeline(new ScriptedOcr(first, second), true, new ImageTranslationOverlayRenderer(fonts()))
                    .execute(request());

            assertThat(logLine("Page orientation retry")).as("%d more letters", extra)
                    .contains(extra >= 2 ? "outcome=accepted" : "outcome=rejected");
        }
    }

    @Test
    void aSecondReadingThatIsNotUprightOrOfAnotherSizeOrHasDuplicateIdsIsRejected() {
        OcrRecognition poor = rotated(.7f, "hello wurld", "good mornnig", .0, true);
        OcrRecognition stillTurned = OcrRecognition.structured(List.of(
                upright("line-a", "hello world", LINE_A, .98f)),
                new PageFrame(1, HEIGHT, WIDTH, PageOrientation.Verdict.ROTATED, false, 1d));
        OcrRecognition duplicates = new OcrRecognition("t", List.of(), List.of(
                upright("line-a", "hello world", LINE_A, .98f), upright("line-a", "hello world", LINE_B, .98f)),
                new PageFrame(0, HEIGHT, WIDTH, PageOrientation.Verdict.UPRIGHT, false, 1d));

        OcrRecognition otherSize = OcrRecognition.structured(List.of(
                upright("line-a", "hello world", LINE_A, .98f)),
                new PageFrame(0, HEIGHT + 5, WIDTH, PageOrientation.Verdict.UPRIGHT, false, 1d));

        pipeline(new ScriptedOcr(poor, stillTurned), true, new ImageTranslationOverlayRenderer(fonts())).execute(request());
        assertThat(logLine("Page orientation retry")).contains("outcome=rejected", "reason=not-upright");
        logs.list.clear();
        pipeline(new ScriptedOcr(poor, otherSize), true, new ImageTranslationOverlayRenderer(fonts())).execute(request());
        assertThat(logLine("Page orientation retry")).contains("outcome=rejected", "reason=frame-mismatch");
        logs.list.clear();
        pipeline(new ScriptedOcr(poor, duplicates), true, new ImageTranslationOverlayRenderer(fonts())).execute(request());
        assertThat(logLine("Page orientation retry")).contains("outcome=rejected", "reason=duplicate-ids");
    }

    @Test
    void aTimeoutOnTheSecondReadingKeepsTheFirstAndTheImageStillSucceeds() {
        OcrRecognition poor = rotated(.7f, "hello wurld", "good mornnig", .0, true);
        ScriptedOcr ocr = new ScriptedOcr(poor, new OcrTimeoutException("slow", new RuntimeException("private")));

        ImageTranslationOutcome outcome = pipeline(ocr, true, new ImageTranslationOverlayRenderer(fonts()))
                .execute(request());

        assertThat(outcome).isInstanceOf(ImageTranslationOutcome.Success.class);
        assertThat(ocr.calls()).isEqualTo(2);
        assertThat(translatedSources()).containsExactly("hello wurld", "good mornnig");
        assertThat(logLine("Page orientation retry")).contains("outcome=failed", "failure=OcrTimeoutException")
                .doesNotContain("private");
        verifyNoAiRecognition();
        assertThat(unexpected("recognition")).isZero();
    }

    @Test
    void aDefectOnTheSecondReadingIsCountedKeepsTheFirstAndTheImageStillSucceeds() {
        OcrRecognition poor = rotated(.7f, "hello wurld", "good mornnig", .0, true);
        ScriptedOcr ocr = new ScriptedOcr(poor, new OcrDefectException("bug", new NullPointerException()));

        ImageTranslationOutcome outcome = pipeline(ocr, true, new ImageTranslationOverlayRenderer(fonts()))
                .execute(request());

        assertThat(outcome).isInstanceOf(ImageTranslationOutcome.Success.class);
        assertThat(translatedSources()).containsExactly("hello wurld", "good mornnig");
        assertThat(unexpected("recognition")).isEqualTo(1d);
        assertThat(logLine("Page orientation retry")).contains("outcome=failed");
        verifyNoAiRecognition();
    }

    @Test
    void anyOtherFailureOnTheSecondReadingIsHandledTheSameWay() {
        OcrRecognition poor = rotated(.7f, "hello wurld", "good mornnig", .0, true);
        ScriptedOcr ocr = new ScriptedOcr(poor, new IllegalStateException("not an OCR failure"));

        ImageTranslationOutcome outcome = pipeline(ocr, true, new ImageTranslationOverlayRenderer(fonts()))
                .execute(request());

        assertThat(outcome).isInstanceOf(ImageTranslationOutcome.Success.class);
        assertThat(unexpected("recognition")).isEqualTo(1d);
        verifyNoAiRecognition();
    }

    @Test
    void theRetryIsOffByDefaultAndTheFlagDecidesIt() {
        OcrRecognition poor = rotated(.7f, "hello wurld", "good mornnig", .0, true);
        ScriptedOcr off = new ScriptedOcr(poor, poor);

        pipeline(off, false, new ImageTranslationOverlayRenderer(fonts())).execute(request());

        assertThat(ImageTranslationProperties.defaults().orientationRetryEnabled()).isFalse();
        assertThat(off.calls()).as("flag off").isEqualTo(1);
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                .noneMatch(line -> line.startsWith("Page orientation retry"));
    }

    @Test
    void aSlowFirstCallSkipsTheRetry() {
        OcrRecognition poor = rotated(.7f, "hello wurld", "good mornnig", .0, true);
        ScriptedOcr slow = new ScriptedOcr(poor, poor).slowFirstCall(80);

        // The bound is 100 ms and the first call took 80: more than half of it.
        new ImageTranslationPipeline(provider(slow), workflowModule, aiModule, preferences, blobClient, storage,
                new ImageInputValidator(ImageTranslationProperties.defaults()),
                new ImageTranslationOverlayRenderer(fonts()), properties(true),
                new OcrRegionQualificationPolicy(), new OcrSourceLanguageResolver(), new OverlaySafetyPolicy(),
                new TranslationPromptFactory(), meters, 100).execute(request());

        assertThat(slow.calls()).isEqualTo(1);
        assertThat(logLine("Page orientation retry")).contains("outcome=skipped", "reason=slow-first-call");
    }

    @Test
    void aFastFirstCallIsRetriedAtTheSameBound() {
        OcrRecognition poor = rotated(.7f, "hello wurld", "good mornnig", .0, true);
        ScriptedOcr fast = new ScriptedOcr(poor, poor);

        new ImageTranslationPipeline(provider(fast), workflowModule, aiModule, preferences, blobClient, storage,
                new ImageInputValidator(ImageTranslationProperties.defaults()),
                new ImageTranslationOverlayRenderer(fonts()), properties(true),
                new OcrRegionQualificationPolicy(), new OcrSourceLanguageResolver(), new OverlaySafetyPolicy(),
                new TranslationPromptFactory(), meters, 100_000).execute(request());

        assertThat(fast.calls()).isEqualTo(2);
    }

    @Test
    void aTurnedPageThatWasReadWellIsNotRetried() {
        ScriptedOcr good = new ScriptedOcr(rotated(.98f, "hello world", "good morning", 1d, false));

        pipeline(good, true, new ImageTranslationOverlayRenderer(fonts())).execute(request());

        assertThat(good.calls()).isEqualTo(1);
    }

    @Test
    void aPageThatIsNotTurnedIsNeverRetriedWhateverItsReliability() {
        ScriptedOcr upright = new ScriptedOcr(OcrRecognition.structured(List.of(
                upright("line-a", "hello wurld", LINE_A, .7f)),
                new PageFrame(0, WIDTH, HEIGHT, PageOrientation.Verdict.UPRIGHT, false, .0)));

        pipeline(upright, true, new ImageTranslationOverlayRenderer(fonts())).execute(request());

        assertThat(upright.calls()).isEqualTo(1);
    }

    @Test
    void anUprightRasterTooLargeToSendAgainSkipsTheRetry() throws Exception {
        // A heavily compressed upload: its file is small, but the raster is encoded again at a
        // high quality for the second call, and that file is far larger than the bound.
        photo = noisy(WIDTH, HEIGHT);
        photoBytes = jpeg(photo, .1f);
        photo = ImageIO.read(new ByteArrayInputStream(photoBytes));
        when(content.mimeType()).thenReturn("image/jpeg");
        ImageTranslationProperties bound = new ImageTranslationProperties(
                photoBytes.length, 4096, 16_000_000, .6f, true, .12, .35, .60, true);
        OcrRecognition poor = rotated(.7f, "hello wurld", "good mornnig", .0, true);
        ScriptedOcr ocr = new ScriptedOcr(poor, poor);

        new ImageTranslationPipeline(provider(ocr), workflowModule, aiModule, preferences, blobClient, storage,
                new ImageInputValidator(bound), new ImageTranslationOverlayRenderer(fonts()), bound,
                new OcrRegionQualificationPolicy(), new OcrSourceLanguageResolver(), new OverlaySafetyPolicy(),
                new TranslationPromptFactory(), meters, 60_000).execute(request());

        assertThat(ocr.calls()).isEqualTo(1);
        assertThat(logLine("Page orientation retry")).contains("outcome=skipped", "reason=too-large", "format=jpeg");
    }

    // --- helpers -------------------------------------------------------------------------------------

    private ImageTranslationPipeline pipeline(
            ScriptedOcr ocr, boolean retry, ImageTranslationOverlayRenderer renderer) {
        return new ImageTranslationPipeline(provider(ocr), workflowModule, aiModule, preferences, blobClient,
                storage, new ImageInputValidator(ImageTranslationProperties.defaults()), renderer,
                properties(retry), new OcrRegionQualificationPolicy(), new OcrSourceLanguageResolver(),
                new OverlaySafetyPolicy(), new TranslationPromptFactory(), meters, 60_000);
    }

    private static ImageTranslationProperties properties(boolean retry) {
        return new ImageTranslationProperties(10_485_760, 4096, 16_000_000, .6f, true, .12, .35, .60, retry);
    }

    private static ObjectProvider<OcrService> provider(OcrService service) {
        ObjectProvider<OcrService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }

    private static ImageTranslationFontProvider fonts() {
        return new ImageTranslationFontProvider(Set.of("dejavu sans"));
    }

    private ImageTranslationRequest request() {
        return new ImageTranslationRequest(userProfile, "message-orientation", Instant.parse("2026-08-11T00:00:00Z"));
    }

    private void verifyNoAiRecognition() {
        verify(aiModule, never()).processImage(any(), anyString(), anyString());
    }

    private double unexpected(String stage) {
        return meters.get("image.translation.unexpected").tag("stage", stage).counter().count();
    }

    private String logLine(String prefix) {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.startsWith(prefix)).reduce((first, last) -> last).orElse("<no such line>");
    }

    /** What the model was asked to translate on the last workflow request. */
    private List<String> translatedSources() {
        ArgumentCaptor<TranslationWorkflowRequest> request = ArgumentCaptor.forClass(TranslationWorkflowRequest.class);
        verify(workflowModule, org.mockito.Mockito.atLeastOnce()).execute(request.capture());
        return request.getValue().imageRegions().stream().map(ImageRegionTranslationInput::sourceText).toList();
    }

    /** A recognition in the upright frame of the page, as the adapter returns it for the photo. */
    private static OcrRecognition rotated(float confidence, String first, String second,
            double reliableShare, boolean retryAdvised) {
        return OcrRecognition.structured(List.of(
                upright("line-a", first, LINE_A, confidence), upright("line-b", second, LINE_B, confidence)),
                new PageFrame(1, WIDTH, HEIGHT, PageOrientation.Verdict.ROTATED, retryAdvised, reliableShare));
    }

    private static OcrRegion upright(String id, String text, Rectangle box, float confidence) {
        return new OcrRegion(id, text, List.of(
                new OcrPoint(box.x, box.y), new OcrPoint(box.x + box.width, box.y),
                new OcrPoint(box.x + box.width, box.y + box.height), new OcrPoint(box.x, box.y + box.height)),
                List.of(), confidence, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage("en", confidence)), id.equals("line-a") ? 0 : 1);
    }

    /** Where a box of the upright page lies in the photo. */
    private static Rectangle inPhoto(Rectangle box) {
        OcrPoint a = PHOTO.turn(box.x, box.y);
        OcrPoint b = PHOTO.turn(box.x + box.width, box.y + box.height);
        return new Rectangle(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()),
                Math.abs(a.x() - b.x()), Math.abs(a.y() - b.y()));
    }

    private static Rectangle grown(Rectangle box, int margin) {
        return new Rectangle(box.x - margin, box.y - margin, box.width + 2 * margin, box.height + 2 * margin);
    }

    private static boolean near(Rectangle box, int x, int y, int margin) {
        return x >= box.x - margin && x < box.x + box.width + margin
                && y >= box.y - margin && y < box.y + box.height + margin;
    }

    /** Bounding box of the pixels inside {@code box} grown by {@code margin} that differ from the photo. */
    private Rectangle changed(BufferedImage delivered, Rectangle box, int margin) {
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = -1, bottom = -1;
        for (int y = Math.max(0, box.y - margin); y < Math.min(HEIGHT, box.y + box.height + margin); y++) {
            for (int x = Math.max(0, box.x - margin); x < Math.min(WIDTH, box.x + box.width + margin); x++) {
                if (delivered.getRGB(x, y) != photo.getRGB(x, y)) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        return right < 0 ? null : new Rectangle(left, top, right - left + 1, bottom - top + 1);
    }

    /** Bounding box of the pixels in the upright frame that are dark enough to be ink. */
    private static Rectangle inkBounds(BufferedImage upright, Rectangle box) {
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = -1, bottom = -1;
        for (int y = box.y; y < box.y + box.height; y++) {
            for (int x = box.x; x < box.x + box.width; x++) {
                Color color = new Color(upright.getRGB(x, y));
                if (color.getRed() + color.getGreen() + color.getBlue() < 3 * 110) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        return new Rectangle(left, top, right - left + 1, bottom - top + 1);
    }

    /** The photo: the upright page with two printed lines, turned a quarter clockwise, plus corner marks. */
    private static BufferedImage photograph() {
        BufferedImage page = new BufferedImage(HEIGHT, WIDTH, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = page.createGraphics();
        try {
            graphics.setColor(new Color(244, 241, 232));
            graphics.fillRect(0, 0, page.getWidth(), page.getHeight());
            graphics.setColor(new Color(30, 30, 30));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
            graphics.drawString("hello world", LINE_A.x + 4, LINE_A.y + 22);
            graphics.drawString("good morning", LINE_B.x + 4, LINE_B.y + 22);
        } finally {
            graphics.dispose();
        }
        BufferedImage photo = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        // Clockwise by one quarter, written out here: (x, y) of the page lands at (height - 1 - y, x).
        for (int y = 0; y < page.getHeight(); y++) {
            for (int x = 0; x < page.getWidth(); x++) {
                photo.setRGB(page.getHeight() - 1 - y, x, page.getRGB(x, y));
            }
        }
        // Marks in the corners of the photo itself, each different, which no wrong turn can fake.
        for (int offset = 0; offset < 6; offset++) {
            for (int other = 0; other < 6; other++) {
                photo.setRGB(offset, other, 0xD02020);
                photo.setRGB(WIDTH - 1 - offset, other, 0x20A020);
                photo.setRGB(offset, HEIGHT - 1 - other, 0x2020D0);
            }
        }
        return photo;
    }

    private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (javax.imageio.stream.ImageOutputStream stream = ImageIO.createImageOutputStream(output)) {
            javax.imageio.ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            writer.setOutput(stream);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private static BufferedImage noisy(int width, int height) {
        java.util.Random random = new java.util.Random(5);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        return image;
    }

    /** An OCR service that answers from a script, counts its calls and remembers what it was sent. */
    private static final class ScriptedOcr implements OcrService {

        private final List<Object> script;
        private final List<byte[]> received = new ArrayList<>();
        private long firstCallDelayMillis;

        ScriptedOcr(Object... script) {
            this.script = List.of(script);
        }

        ScriptedOcr slowFirstCall(long millis) {
            this.firstCallDelayMillis = millis;
            return this;
        }

        int calls() {
            return received.size();
        }

        List<byte[]> received() {
            return received;
        }

        @Override
        public OcrRecognition recognize(InputStream imageStream) {
            try {
                received.add(imageStream.readAllBytes());
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            if (received.size() == 1 && firstCallDelayMillis > 0) {
                try {
                    Thread.sleep(firstCallDelayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            Object step = script.get(Math.min(received.size(), script.size()) - 1);
            if (step instanceof RuntimeException failure) throw failure;
            return (OcrRecognition) step;
        }

        @Override
        public String recognizeText(InputStream imageStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TextBlock> recognizeTextWithLocations(InputStream imageStream) {
            throw new UnsupportedOperationException();
        }
    }

    /** The real renderer, remembering what the pipeline handed it. */
    private final class RecordingRenderer extends ImageTranslationOverlayRenderer {

        final AtomicInteger restoreQuarterTurns = new AtomicInteger(-1);
        final AtomicInteger sourceWidth = new AtomicInteger();
        final AtomicInteger sourceHeight = new AtomicInteger();
        final AtomicReference<List<OcrRegion>> regions = new AtomicReference<>(List.of());

        RecordingRenderer() {
            super(fonts());
        }

        @Override
        public RenderedImage render(ValidatedImage source, OverlaySafetyPlan plan) {
            remember(source, plan, 0);
            return super.render(source, plan);
        }

        @Override
        public RenderedImage render(ValidatedImage source, OverlaySafetyPlan plan, int quarterTurns) {
            remember(source, plan, quarterTurns);
            return super.render(source, plan, quarterTurns);
        }

        private void remember(ValidatedImage source, OverlaySafetyPlan plan, int quarterTurns) {
            restoreQuarterTurns.set(quarterTurns);
            sourceWidth.set(source.image().getWidth());
            sourceHeight.set(source.image().getHeight());
            regions.set(plan.overlays().stream().map(ImageRegionOverlay::region).toList());
        }
    }
}
