package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

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
import com.linetranslate.bot.service.translation.ImageRegionTranslationInput;
import com.linetranslate.bot.service.translation.TranslationRequestKind;
import com.linetranslate.bot.service.translation.TranslationWorkflowModule;
import com.linetranslate.bot.service.translation.TranslationWorkflowOutcome;
import com.linetranslate.bot.service.translation.TranslationWorkflowRequest;
import com.linetranslate.bot.service.translation.TranslationWorkflowResult;

/**
 * A successful OCR is never rejected for its size. A result past the region or character limit
 * keeps its leading regions in reading order, and what was left out is reported as counts.
 */
@SuppressWarnings("unchecked")
class ImageTranslationPipelineOcrLimitsTests {

    private static final String[] DISHES = {
        "Noodles", "Dumplings", "Fried rice", "Green curry", "Black tea", "Coffee",
        "Fish soup", "Garden salad", "Pancakes", "Omelette", "Sandwich", "Burger"
    };

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
        UserPreferencesModule userPreferencesModule = mock(UserPreferencesModule.class);
        messagingApiBlobClient = mock(MessagingApiBlobClient.class);
        minioStorageService = mock(MinioStorageService.class);
        when(ocrServiceProvider.getIfAvailable()).thenReturn(ocrService);
        when(ocrService.recognize(any(InputStream.class))).thenCallRealMethod();
        userProfile = UserProfile.builder().userId("U-limits").preferredModel("gpt-test").build();
        when(userPreferencesModule.resolve(userProfile)).thenReturn(
                com.linetranslate.bot.testing.UserPreferencesFixtures.preferences(userProfile));
        ImageTranslationProperties limits = ImageTranslationProperties.defaults();
        pipeline = new ImageTranslationPipeline(
                ocrServiceProvider, workflowModule, aiProviderExecutionModule, userPreferencesModule,
                messagingApiBlobClient, minioStorageService, new ImageInputValidator(limits),
                new ImageTranslationOverlayRenderer(
                        new ImageTranslationFontProvider(Set.of("dejavu sans"))),
                limits);
        when(minioStorageService.uploadImage(any(byte[].class), anyString()))
                .thenReturn(ImageStorageResult.notStored());
    }

    @Test
    void tenMenusThatSegmentPastTheRegionLimitStillTranslateAndOverlayTheLeadingRegions() throws Exception {
        // Ten Vision paragraphs, each a column of dishes and prices, so far below any block limit.
        List<OcrRegion> paragraphs = IntStream.range(0, 10)
                .mapToObj(ImageTranslationPipelineOcrLimitsTests::menu).toList();
        List<OcrRegion> segmented = new OcrRegionSegmenter().segment(paragraphs);
        assertThat(segmented.size()).as("regions after segmentation").isGreaterThan(200);
        image("message-menus", whitePng(660, 1950));
        when(ocrService.recognizeRegions(any(InputStream.class))).thenReturn(segmented);
        when(workflowModule.execute(any())).thenAnswer(invocation -> translated(invocation.getArgument(0)));
        when(minioStorageService.uploadTranslatedImage(any(byte[].class), anyString()))
                .thenReturn(ImageStorageResult.stored("https://storage.example/menus.png"));

        ImageTranslationOutcome outcome = pipeline.execute(request("message-menus"));

        ImageTranslationPipelineResult result = successResult(outcome);
        ArgumentCaptor<TranslationWorkflowRequest> workflow =
                ArgumentCaptor.forClass(TranslationWorkflowRequest.class);
        verify(workflowModule).execute(workflow.capture());
        // The leading 200 regions in reading order reach the model; the rest are counted, not sent.
        assertThat(workflow.getValue().imageRegions()).extracting(ImageRegionTranslationInput::regionId)
                .containsExactlyElementsOf(segmented.stream().limit(200).map(OcrRegion::id).toList());
        assertThat(workflow.getValue().sourceText())
                .contains(segmented.get(198).text()).doesNotContain(segmented.get(200).text());
        assertThat(result.degradation().count(OverlayDegradationReason.LIMIT)).isEqualTo(40);
        // The overlay still covers the regions that were translated.
        assertThat(result.overlayDisposition()).isEqualTo(ImageOverlayDisposition.GENERATED);
        assertThat(result.renderedImage().url()).contains("https://storage.example/menus.png");
        // The reply still shows everything the OCR read.
        assertThat(result.context().recognizedText()).contains(segmented.get(239).text());
    }

    @Test
    void exactlyTheRegionLimitOmitsNothing() throws Exception {
        List<OcrRegion> regions = IntStream.range(0, 200)
                .mapToObj(index -> note("note-%03d".formatted(index), "Note " + DISHES[index % DISHES.length], index))
                .toList();
        image("message-at-limit", whitePng(660, 1950));
        when(ocrService.recognizeRegions(any(InputStream.class))).thenReturn(regions);
        when(workflowModule.execute(any())).thenAnswer(invocation -> translated(invocation.getArgument(0)));

        ImageTranslationPipelineResult result = successResult(pipeline.execute(request("message-at-limit")));

        ArgumentCaptor<TranslationWorkflowRequest> workflow =
                ArgumentCaptor.forClass(TranslationWorkflowRequest.class);
        verify(workflowModule).execute(workflow.capture());
        assertThat(workflow.getValue().imageRegions()).hasSize(200);
        assertThat(result.degradation().count(OverlayDegradationReason.LIMIT)).isZero();
    }

    @Test
    void regionsPastTheCharacterBudgetStayOutOfTheModelRequest() throws Exception {
        String paragraph = "Lorem ipsum dolor sit amet ".repeat(120).substring(0, 3_000);
        List<OcrRegion> regions = IntStream.range(0, 20)
                .mapToObj(index -> note("page-%02d".formatted(index), paragraph, index)).toList();
        image("message-long", whitePng(660, 1950));
        when(ocrService.recognizeRegions(any(InputStream.class))).thenReturn(regions);
        when(workflowModule.execute(any())).thenAnswer(invocation -> translated(invocation.getArgument(0)));

        ImageTranslationPipelineResult result = successResult(pipeline.execute(request("message-long")));

        ArgumentCaptor<TranslationWorkflowRequest> workflow =
                ArgumentCaptor.forClass(TranslationWorkflowRequest.class);
        verify(workflowModule).execute(workflow.capture());
        List<ImageRegionTranslationInput> sent = workflow.getValue().imageRegions();
        // 16 x 3,000 = 48,000 fits the 50,000 budget; a 17th region would not.
        assertThat(sent).extracting(ImageRegionTranslationInput::regionId)
                .containsExactlyElementsOf(regions.stream().limit(16).map(OcrRegion::id).toList());
        assertThat(sent.stream().mapToInt(region -> region.sourceText().length()).sum())
                .isLessThanOrEqualTo(50_000);
        assertThat(workflow.getValue().sourceText().length()).isLessThanOrEqualTo(50_000 + 16);
        assertThat(result.degradation().count(OverlayDegradationReason.LIMIT)).isEqualTo(4);
        assertThat(result.translation().translatedText()).isNotBlank();
    }

    @Test
    void leadingPreservedRegionsAreDroppedOldestFirstToKeepTheFirstTranslatableOne() throws Exception {
        List<OcrRegion> regions = new ArrayList<>(IntStream.range(0, 250)
                .mapToObj(index -> note("amount-%03d".formatted(index), "%d".formatted(1000 + index), index))
                .toList());
        regions.add(note("title", "Closing time", 250));
        image("message-numbers-first", whitePng(660, 1950));
        when(ocrService.recognizeRegions(any(InputStream.class))).thenReturn(regions);
        when(workflowModule.execute(any())).thenAnswer(invocation -> translated(invocation.getArgument(0)));

        ImageTranslationOutcome outcome = pipeline.execute(request("message-numbers-first"));

        ImageTranslationPipelineResult result = successResult(outcome);
        ArgumentCaptor<TranslationWorkflowRequest> workflow =
                ArgumentCaptor.forClass(TranslationWorkflowRequest.class);
        verify(workflowModule).execute(workflow.capture());
        List<ImageRegionTranslationInput> sent = workflow.getValue().imageRegions();
        // The title is kept with the 199 preserved amounts nearest to it; the 51 oldest are dropped.
        assertThat(sent).extracting(ImageRegionTranslationInput::regionId)
                .containsExactlyElementsOf(regions.subList(51, 251).stream().map(OcrRegion::id).toList());
        assertThat(sent).filteredOn(ImageRegionTranslationInput::translatable)
                .extracting(ImageRegionTranslationInput::regionId).containsExactly("title");
        // The workflow's plain-text fallback sends this text, so it respects the bound too.
        assertThat(workflow.getValue().sourceText().split("\n")).hasSize(200);
        assertThat(result.degradation().count(OverlayDegradationReason.LIMIT)).isEqualTo(51);
    }

    @Test
    void leadingPreservedRegionsPastTheCharacterBudgetAreDroppedToo() throws Exception {
        String account = "1234567890".repeat(300);
        List<OcrRegion> regions = new ArrayList<>(IntStream.range(0, 20)
                .mapToObj(index -> note("account-%02d".formatted(index), account, index)).toList());
        regions.add(note("title", "Closing time", 20));
        image("message-long-numbers-first", whitePng(660, 1950));
        when(ocrService.recognizeRegions(any(InputStream.class))).thenReturn(regions);
        when(workflowModule.execute(any())).thenAnswer(invocation -> translated(invocation.getArgument(0)));

        ImageTranslationPipelineResult result =
                successResult(pipeline.execute(request("message-long-numbers-first")));

        ArgumentCaptor<TranslationWorkflowRequest> workflow =
                ArgumentCaptor.forClass(TranslationWorkflowRequest.class);
        verify(workflowModule).execute(workflow.capture());
        List<ImageRegionTranslationInput> sent = workflow.getValue().imageRegions();
        // 16 x 3,000 + 12 characters fit the 50,000 budget; a 17th amount would not.
        assertThat(sent).extracting(ImageRegionTranslationInput::regionId)
                .containsExactlyElementsOf(regions.subList(4, 21).stream().map(OcrRegion::id).toList());
        assertThat(sent.stream().mapToInt(region -> region.sourceText().length()).sum())
                .isLessThanOrEqualTo(50_000);
        assertThat(workflow.getValue().sourceText().length()).isLessThanOrEqualTo(50_000 + sent.size());
        assertThat(result.degradation().count(OverlayDegradationReason.LIMIT)).isEqualTo(4);
    }

    @Test
    void duplicateRegionIdsFallBackToAiRecognition() throws Exception {
        image("message-duplicates", whitePng(660, 400));
        when(ocrService.recognizeRegions(any(InputStream.class))).thenReturn(List.of(
                note("same", "Noodles", 0), note("same", "Dumplings", 1)));
        when(aiProviderExecutionModule.processImage(any(), anyString(), anyString()))
                .thenReturn(new AiExecutionResult("fallback text", "openai", "gpt-image"));
        when(workflowModule.execute(any())).thenAnswer(invocation -> translated(invocation.getArgument(0)));

        ImageTranslationOutcome outcome = pipeline.execute(request("message-duplicates"));

        assertThat(successResult(outcome).context().recognizedText()).isEqualTo("fallback text");
        verify(aiProviderExecutionModule).processImage(any(), anyString(), anyString());
        verify(workflowModule).execute(any());
    }

    @Test
    void undecodableUploadStillFailsAtTheDownloadStage() throws Exception {
        image("message-garbage", new byte[] {1, 2, 3});

        ImageTranslationOutcome outcome = pipeline.execute(request("message-garbage"));

        assertThat(outcome).isEqualTo(new ImageTranslationOutcome.Failure(
                ImageTranslationFailureStage.DOWNLOAD));
        verify(ocrService, never()).recognize(any());
        verify(workflowModule, never()).execute(any());
    }

    /** A menu of twelve dishes, each with a price at the right edge: 24 layout regions. */
    private static OcrRegion menu(int index) {
        int left = 10 + (index % 2) * 320;
        int top = 10 + (index / 2) * 380;
        List<OcrWord> words = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < 12; row++) {
            int y = top + row * 30;
            String dish = DISHES[row] + " " + (char) ('A' + index);
            words.add(word(dish, left, y, 150, 24));
            words.add(word(String.valueOf(100 + row * 5), left + 225, y, 35, 24));
            text.append(text.isEmpty() ? "" : " ").append(dish).append(' ').append(100 + row * 5);
        }
        return new OcrRegion("menu" + index, text.toString(),
                rectangle(left - 5, top - 5, 280, 12 * 30 + 5), words, .98f, true, OcrBlockType.TEXT,
                List.of(new OcrDetectedLanguage("en", .98f)), index);
    }

    private static OcrRegion note(String id, String text, int order) {
        int y = 10 + (order % 60) * 30;
        return new OcrRegion(id, text, rectangle(10, y, 600, 24), List.of(), .98f, true,
                OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("en", .98f)), order);
    }

    private static OcrWord word(String text, int x, int y, int width, int height) {
        return new OcrWord(text, rectangle(x, y, width, height), .98f, true);
    }

    private static List<OcrPoint> rectangle(int x, int y, int width, int height) {
        return List.of(new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height));
    }

    /** Answers every translatable region of the request, as a provider that honours the schema. */
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
        when(content.mimeType()).thenReturn(bytes.length > 3 ? "image/png" : null);
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

    private ImageTranslationRequest request(String messageId) {
        return new ImageTranslationRequest(userProfile, messageId, Instant.parse("2026-08-11T00:00:00Z"));
    }

    private static ImageTranslationPipelineResult successResult(ImageTranslationOutcome outcome) {
        assertThat(outcome).isInstanceOf(ImageTranslationOutcome.Success.class);
        return ((ImageTranslationOutcome.Success) outcome).result();
    }
}
