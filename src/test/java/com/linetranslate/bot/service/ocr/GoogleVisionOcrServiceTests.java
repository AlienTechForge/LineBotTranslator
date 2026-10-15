package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.google.api.gax.grpc.GrpcStatusCode;
import com.google.api.gax.rpc.DeadlineExceededException;
import com.google.api.gax.rpc.UnavailableException;
import com.google.cloud.vision.v1.AnnotateImageResponse;
import com.google.cloud.vision.v1.BatchAnnotateImagesResponse;
import com.google.cloud.vision.v1.Block;
import com.google.cloud.vision.v1.BoundingPoly;
import com.google.cloud.vision.v1.EntityAnnotation;
import com.google.cloud.vision.v1.Feature;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.ImageAnnotatorSettings;
import com.google.cloud.vision.v1.Page;
import com.google.cloud.vision.v1.Paragraph;
import com.google.cloud.vision.v1.Symbol;
import com.google.cloud.vision.v1.TextAnnotation;
import com.google.cloud.vision.v1.TextAnnotation.DetectedBreak.BreakType;
import com.google.cloud.vision.v1.Vertex;
import com.google.cloud.vision.v1.Word;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.grpc.Status;

class GoogleVisionOcrServiceTests {

    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(GoogleVisionOcrService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractsParagraphBlocksWithBoundsConfidenceAndReadingText() {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        ObjectProvider<ImageAnnotatorClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        Paragraph paragraph = Paragraph.newBuilder()
                .setBoundingBox(BoundingPoly.newBuilder()
                        .addVertices(Vertex.newBuilder().setX(10).setY(20))
                        .addVertices(Vertex.newBuilder().setX(150).setY(20))
                        .addVertices(Vertex.newBuilder().setX(150).setY(60))
                        .addVertices(Vertex.newBuilder().setX(10).setY(60)))
                .setConfidence(0.87f)
                .addWords(word("Hello"))
                .addWords(word("world"))
                .build();
        TextAnnotation annotation = TextAnnotation.newBuilder()
                .addPages(Page.newBuilder()
                        .addBlocks(Block.newBuilder().setBlockType(Block.BlockType.TEXT).addParagraphs(paragraph)))
                .build();
        when(client.batchAnnotateImages(anyList())).thenReturn(
                BatchAnnotateImagesResponse.newBuilder()
                        .addResponses(AnnotateImageResponse.newBuilder()
                                .setFullTextAnnotation(annotation))
                        .build());
        GoogleVisionOcrService service = new GoogleVisionOcrService(provider);

        List<OcrService.TextBlock> result = service.recognizeTextWithLocations(
                new ByteArrayInputStream(new byte[] {1, 2, 3}));

        assertThat(result).singleElement().satisfies(block -> {
            assertThat(block.getText()).isEqualTo("Hello world");
            assertThat(block.getX()).isEqualTo(10);
            assertThat(block.getY()).isEqualTo(20);
            assertThat(block.getWidth()).isEqualTo(140);
            assertThat(block.getHeight()).isEqualTo(40);
            assertThat(block.getConfidence()).isEqualTo(0.87f);
        });
        org.mockito.ArgumentCaptor<List<com.google.cloud.vision.v1.AnnotateImageRequest>> request =
                (org.mockito.ArgumentCaptor) org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(client).batchAnnotateImages(request.capture());
        assertThat(request.getValue().get(0).getFeatures(0).getType())
                .isEqualTo(Feature.Type.DOCUMENT_TEXT_DETECTION);
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesRotatedVertexOrderLanguageAndStableIdWhileIgnoringPictures() {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        ObjectProvider<ImageAnnotatorClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        BoundingPoly rotated = BoundingPoly.newBuilder()
                .addVertices(Vertex.newBuilder().setX(20).setY(10))
                .addVertices(Vertex.newBuilder().setX(120).setY(45))
                .addVertices(Vertex.newBuilder().setX(105).setY(85))
                .addVertices(Vertex.newBuilder().setX(5).setY(50)).build();
        Paragraph paragraph = Paragraph.newBuilder()
                .setBoundingBox(rotated).setConfidence(.93f)
                .setProperty(TextAnnotation.TextProperty.newBuilder().addDetectedLanguages(
                        TextAnnotation.DetectedLanguage.newBuilder().setLanguageCode("ko").setConfidence(.97f)))
                .addWords(word("불닭볶음면")).build();
        TextAnnotation annotation = TextAnnotation.newBuilder().addPages(Page.newBuilder()
                .addBlocks(Block.newBuilder().setBlockType(Block.BlockType.PICTURE).addParagraphs(paragraph))
                .addBlocks(Block.newBuilder().setBlockType(Block.BlockType.TEXT).addParagraphs(paragraph))).build();
        when(client.batchAnnotateImages(anyList())).thenReturn(BatchAnnotateImagesResponse.newBuilder()
                .addResponses(AnnotateImageResponse.newBuilder().setFullTextAnnotation(annotation)).build());
        GoogleVisionOcrService service = new GoogleVisionOcrService(provider);

        List<OcrRegion> first = service.recognizeRegions(new ByteArrayInputStream(new byte[] {1}));
        List<OcrRegion> second = service.recognizeRegions(new ByteArrayInputStream(new byte[] {1}));

        assertThat(first).singleElement().satisfies(region -> {
            assertThat(region.polygon()).containsExactly(
                    new OcrPoint(20, 10), new OcrPoint(120, 45),
                    new OcrPoint(105, 85), new OcrPoint(5, 50));
            assertThat(region.languages()).containsExactly(new OcrDetectedLanguage("ko", .97f));
            assertThat(region.blockType()).isEqualTo(OcrBlockType.TEXT);
            assertThat(region.confidenceKnown()).isTrue();
        });
        assertThat(second.get(0).id()).isEqualTo(first.get(0).id());
    }

    @Test
    @SuppressWarnings("unchecked")
    void segmentsProviderParagraphThatContainsMultipleMenuRowsAndColumns() {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        ObjectProvider<ImageAnnotatorClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        Paragraph paragraph = Paragraph.newBuilder()
                .setBoundingBox(box(10, 10, 260, 70))
                .setConfidence(.96f)
                .addWords(word("雞排飯", 10, 10, 100, 24, .97f))
                .addWords(word("110", 225, 10, 35, 24, .99f))
                .addWords(word("魚排飯", 10, 50, 100, 24, .96f))
                .addWords(word("90", 225, 50, 35, 24, .99f))
                .build();
        TextAnnotation annotation = TextAnnotation.newBuilder().addPages(Page.newBuilder()
                .addBlocks(Block.newBuilder().setBlockType(Block.BlockType.TEXT)
                        .addParagraphs(paragraph))).build();
        when(client.batchAnnotateImages(anyList())).thenReturn(BatchAnnotateImagesResponse.newBuilder()
                .addResponses(AnnotateImageResponse.newBuilder().setFullTextAnnotation(annotation)).build());

        List<OcrRegion> regions = new GoogleVisionOcrService(provider)
                .recognizeRegions(new ByteArrayInputStream(new byte[] {1}));

        assertThat(regions).extracting(OcrRegion::text)
                .containsExactly("雞排飯", "110", "魚排飯", "90");
        assertThat(regions).allSatisfy(region -> {
            assertThat(region.groupId()).startsWith("r-0001-");
            assertThat(region.compactLabel()).isTrue();
            assertThat(region.confidenceKnown()).isTrue();
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void foldsTheOrphanLineOfAnArticleBackIntoItsParagraphBeforeQualification() {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        ObjectProvider<ImageAnnotatorClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        // Vision reported four blocks: three paragraphs and the two characters that wrapped out of
        // the last one, with no language of their own.
        List<OcrRegion> blocks = List.of(
                OcrArticleFixtures.paragraph("p1", 100, 4, 0),
                OcrArticleFixtures.paragraph("p2", 440, 4, 1),
                OcrArticleFixtures.paragraph("p3", 780, 6, 2),
                OcrArticleFixtures.tail("orphan", "る。", OcrArticleFixtures.nextLineTop(780, 6), 3));
        Page.Builder page = Page.newBuilder();
        blocks.forEach(block -> page.addBlocks(Block.newBuilder().setBlockType(Block.BlockType.TEXT)
                .addParagraphs(paragraphOf(block))));
        when(client.batchAnnotateImages(anyList())).thenReturn(BatchAnnotateImagesResponse.newBuilder()
                .addResponses(AnnotateImageResponse.newBuilder()
                        .setFullTextAnnotation(TextAnnotation.newBuilder().addPages(page)))
                .build());

        List<OcrRegion> regions = new GoogleVisionOcrService(provider)
                .recognizeRegions(new ByteArrayInputStream(new byte[] {1}));

        assertThat(regions).hasSize(3);
        assertThat(regions.get(2).text()).isEqualTo(blocks.get(2).text() + "る。");
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("providerRegions=4, layoutRegions=3"));
        OcrRegionQualificationPolicy policy = new OcrRegionQualificationPolicy();
        assertThat(regions).allSatisfy(region ->
                assertThat(policy.decide(region, .6f).qualification()).isEqualTo(OcrQualification.TRANSLATE));
    }

    @Test
    @SuppressWarnings("unchecked")
    void imageWithoutTextCostsOneDocumentRequest() {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        ObjectProvider<ImageAnnotatorClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        when(client.batchAnnotateImages(anyList())).thenReturn(BatchAnnotateImagesResponse.newBuilder()
                .addResponses(AnnotateImageResponse.getDefaultInstance()).build());

        OcrRecognition recognition = new GoogleVisionOcrService(provider)
                .recognize(new ByteArrayInputStream(new byte[] {1}));

        assertThat(recognition.text()).isEmpty();
        assertThat(recognition.regions()).isEmpty();
        assertSingleDocumentRequest(client);
    }

    @Test
    @SuppressWarnings("unchecked")
    void textWithoutParagraphRegionsComesBackPlainFromTheSameRequest() {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        ObjectProvider<ImageAnnotatorClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        when(client.batchAnnotateImages(anyList())).thenReturn(BatchAnnotateImagesResponse.newBuilder()
                .addResponses(AnnotateImageResponse.newBuilder()
                        .setFullTextAnnotation(TextAnnotation.newBuilder().setText("SALE 50%\n")))
                .build());

        OcrRecognition recognition = new GoogleVisionOcrService(provider)
                .recognize(new ByteArrayInputStream(new byte[] {1}));

        assertThat(recognition.text()).isEqualTo("SALE 50%");
        assertThat(recognition.regions()).isEmpty();
        assertSingleDocumentRequest(client);
    }

    @Test
    @SuppressWarnings("unchecked")
    void plainTextFallsBackToTextAnnotationsWhenThereIsNoFullTextAnnotation() {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        ObjectProvider<ImageAnnotatorClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        // The first entry is the whole text and the rest repeat it word by word.
        AnnotateImageResponse annotations = AnnotateImageResponse.newBuilder()
                .addTextAnnotations(EntityAnnotation.newBuilder().setDescription("SALE 50%\nToday only"))
                .addTextAnnotations(EntityAnnotation.newBuilder().setDescription("SALE"))
                .addTextAnnotations(EntityAnnotation.newBuilder().setDescription("50%"))
                .build();
        assertThat(annotations.hasFullTextAnnotation()).isFalse();
        when(client.batchAnnotateImages(anyList())).thenReturn(
                BatchAnnotateImagesResponse.newBuilder().addResponses(annotations).build());

        String text = new GoogleVisionOcrService(provider).recognizeText(new ByteArrayInputStream(new byte[] {1}));

        assertThat(text).isEqualTo("SALE 50%\nToday only");
    }

    @Test
    @SuppressWarnings("unchecked")
    void plainTextPrefersTheFullTextAnnotationOverTextAnnotations() {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        ObjectProvider<ImageAnnotatorClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        when(client.batchAnnotateImages(anyList())).thenReturn(BatchAnnotateImagesResponse.newBuilder()
                .addResponses(AnnotateImageResponse.newBuilder()
                        .setFullTextAnnotation(TextAnnotation.newBuilder().setText("full page text"))
                        .addTextAnnotations(EntityAnnotation.newBuilder().setDescription("loose entry")))
                .build());

        String text = new GoogleVisionOcrService(provider).recognizeText(new ByteArrayInputStream(new byte[] {1}));

        assertThat(text).isEqualTo("full page text");
    }

    @Test
    @SuppressWarnings("unchecked")
    void plainTextOfAnImageWithoutAnyAnnotationIsEmpty() {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        ObjectProvider<ImageAnnotatorClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        when(client.batchAnnotateImages(anyList())).thenReturn(BatchAnnotateImagesResponse.newBuilder()
                .addResponses(AnnotateImageResponse.getDefaultInstance()).build());

        String text = new GoogleVisionOcrService(provider).recognizeText(new ByteArrayInputStream(new byte[] {1}));

        assertThat(text).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void clientDeadlineIsAnOcrTimeoutThatReportsTheConfiguredBound() {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        ObjectProvider<ImageAnnotatorClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        when(client.getSettings()).thenReturn(settingsWithTimeout(Duration.ofSeconds(45)));
        DeadlineExceededException deadline = new DeadlineExceededException(
                new RuntimeException("private-provider-detail"),
                GrpcStatusCode.of(Status.Code.DEADLINE_EXCEEDED), false);
        when(client.batchAnnotateImages(anyList())).thenThrow(deadline);
        GoogleVisionOcrService service = new GoogleVisionOcrService(provider);

        assertThatThrownBy(() -> service.recognize(new ByteArrayInputStream(new byte[] {1})))
                .isInstanceOf(OcrTimeoutException.class)
                .isInstanceOf(OcrProcessingException.class)
                .hasCause(deadline);
        assertThatThrownBy(() -> service.recognizeText(new ByteArrayInputStream(new byte[] {1})))
                .isInstanceOf(OcrTimeoutException.class)
                .hasCause(deadline);

        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN).hasSize(2)
                .allSatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("timeoutMs=45000")
                        .contains("DeadlineExceededException")
                        .doesNotContain("private-provider-detail"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void otherProviderFailuresAreNotReportedAsTimeouts() {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        ObjectProvider<ImageAnnotatorClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        UnavailableException unavailable = new UnavailableException(
                new RuntimeException("provider down"), GrpcStatusCode.of(Status.Code.UNAVAILABLE), true);
        when(client.batchAnnotateImages(anyList())).thenThrow(unavailable);
        GoogleVisionOcrService service = new GoogleVisionOcrService(provider);

        assertThatThrownBy(() -> service.recognize(new ByteArrayInputStream(new byte[] {1})))
                .isExactlyInstanceOf(OcrProcessingException.class)
                .hasCause(unavailable);
        assertThatThrownBy(() -> service.recognizeText(new ByteArrayInputStream(new byte[] {1})))
                .isExactlyInstanceOf(OcrProcessingException.class)
                .hasCause(unavailable);

        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBugInTheAdapterIsADefectButATransportFailureIsNot() {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        ObjectProvider<ImageAnnotatorClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        GoogleVisionOcrService service = new GoogleVisionOcrService(provider);

        // A null response makes the adapter's own parsing throw: not a provider failure.
        when(client.batchAnnotateImages(anyList())).thenReturn(null);
        assertThatThrownBy(() -> service.recognize(new ByteArrayInputStream(new byte[] {1})))
                .isExactlyInstanceOf(OcrDefectException.class)
                .isInstanceOf(OcrProcessingException.class)
                .hasCauseInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.recognizeText(new ByteArrayInputStream(new byte[] {1})))
                .isExactlyInstanceOf(OcrDefectException.class)
                .hasCauseInstanceOf(NullPointerException.class);

        // A transport failure raised unchecked is still the provider's.
        UncheckedIOException transport = new UncheckedIOException(new IOException("socket closed"));
        when(client.batchAnnotateImages(anyList())).thenThrow(transport);
        assertThatThrownBy(() -> service.recognize(new ByteArrayInputStream(new byte[] {1})))
                .isExactlyInstanceOf(OcrProcessingException.class)
                .hasCause(transport);
        assertThatThrownBy(() -> service.recognizeText(new ByteArrayInputStream(new byte[] {1})))
                .isExactlyInstanceOf(OcrProcessingException.class)
                .hasCause(transport);
    }

    // --- a page that was turned a quarter ----------------------------------------------------------

    @Test
    void aPageTurnedAQuarterComesBackUprightWithItsFrameAndTheIdsOfTheProviderPolygons() {
        RotatedPageFixtures page = new RotatedPageFixtures(600, 400, 1);
        Reading reading = reading(page.pageWidth(), page.pageHeight(), cafePage(page));

        OcrRecognition recognition = reading.service().recognize(new ByteArrayInputStream(new byte[] {1}));

        PageFrame frame = recognition.frame();
        assertThat(frame.quarterTurns()).isEqualTo(1);
        assertThat(frame.sourceWidth()).isEqualTo(400);
        assertThat(frame.sourceHeight()).isEqualTo(600);
        assertThat(frame.verdict()).isEqualTo(PageOrientation.Verdict.ROTATED);
        assertThat(frame.retryAdvised()).isFalse();
        assertThat(recognition.regions()).extracting(OcrRegion::text).containsExactly(
                "Welcome to our little cafe", "Fresh bread baked every morning",
                "Chicken", "110", "Fish", "90",
                "Open daily from nine to five", "Thank you for visiting us");
        // The price row is split in upright order, into cells that read along +x.
        assertThat(recognition.regions()).allSatisfy(region -> {
            assertThat(region.writingMode()).isEqualTo(WritingMode.HORIZONTAL);
            assertThat(region.rotationDegrees()).isZero();
            assertThat(region.polygon().get(0).x()).isLessThan(region.polygon().get(1).x());
            assertThat(region.polygon().get(0).y()).isLessThan(region.polygon().get(3).y());
        });
        assertThat(recognition.regions().stream().filter(region -> region.id().contains(".l")))
                .extracting(OcrRegion::text).containsExactly("Chicken", "110", "Fish", "90");

        // Ids are hashes of the provider polygons, so they are the ids of the page as the provider
        // sent it, and the regions are exactly that page's, moved by the inverse of the turn.
        List<OcrRegion> inProviderFrame = reading.service()
                .recognizeRegions(new ByteArrayInputStream(new byte[] {1}));
        assertThat(recognition.regions()).extracting(OcrRegion::id)
                .containsExactlyElementsOf(inProviderFrame.stream().map(OcrRegion::id).toList());
        assertThat(QuarterTurns.fromUpright(recognition.regions(), 1, 400, 600))
                .containsExactlyElementsOf(inProviderFrame);
    }

    @Test
    void aRotatedPageStillCostsOneDocumentRequest() {
        RotatedPageFixtures page = new RotatedPageFixtures(600, 400, 3);
        Reading reading = reading(page.pageWidth(), page.pageHeight(), cafePage(page));

        OcrRecognition recognition = reading.service().recognize(new ByteArrayInputStream(new byte[] {1}));

        assertThat(recognition.frame().quarterTurns()).isEqualTo(3);
        assertSingleDocumentRequest(reading.client());
    }

    @Test
    void theOrientationDecisionIsLoggedOnceWithNumbersOnly() {
        RotatedPageFixtures page = new RotatedPageFixtures(600, 400, 1);
        Reading reading = reading(page.pageWidth(), page.pageHeight(), cafePage(page));

        OcrRecognition recognition = reading.service().recognize(new ByteArrayInputStream(new byte[] {1}));

        List<String> lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).filteredOn(line -> line.startsWith("Page orientation decision")).singleElement()
                .satisfies(line -> assertThat(line)
                        .contains("verdict=ROTATED", "quarterTurns=1", "regions=5", "reliableRegions=5",
                                "dominantShare=1.000", "skewedShare=0.000", "reliableShare=1.000",
                                "cjkShare=0.000", "action=normalize", "retryAdvised=false",
                                "pageWidth=400", "pageHeight=600"));
        // Nothing the page says, and no region id, reaches any line written for this image.
        List<String> secrets = new java.util.ArrayList<>();
        for (OcrRegion region : recognition.regions()) {
            secrets.add(region.id());
            for (String word : region.text().split(" ")) {
                if (word.length() >= 5 && word.chars().allMatch(Character::isLetter)) secrets.add(word);
            }
        }
        assertThat(String.join("\n", lines)).doesNotContain(secrets.toArray(String[]::new));
    }

    @Test
    void theRecognitionKeepsTheProviderFrameWhenNothingCallsForTurningThePage() {
        RotatedPageFixtures upright = new RotatedPageFixtures(600, 400, 0);
        RotatedPageFixtures turned = new RotatedPageFixtures(600, 400, 1);
        List<OcrRegion> mixed = new java.util.ArrayList<>(latinLines(turned, 4));
        mixed.addAll(latinLines(upright, 2, 300));
        List<OcrRegion> ambiguous = new java.util.ArrayList<>();
        for (int index = 0; index < 6; index++) {
            ambiguous.add(turned.cjk("c" + index, "日本語の文章を読みます、そして書きます。", 20, 20 + index * 40,
                    RotatedPageFixtures.Marks.NONE, RotatedPageFixtures.SymbolFrame.FOLLOWS_LINE, index));
        }
        List<OcrRegion> skewed = List.of(
                RotatedPageFixtures.atAngle("skew", "Slanted text on a tilted page", 19, 20, 20, .95f, 0));
        record Case(String name, int width, int height, List<OcrRegion> regions, PageOrientation.Verdict verdict) {
        }
        List<Case> cases = List.of(
                new Case("upright", 600, 400, latinLines(upright, 6), PageOrientation.Verdict.UPRIGHT),
                new Case("mixed", 400, 600, mixed, PageOrientation.Verdict.MIXED),
                new Case("ambiguous", 400, 600, ambiguous, PageOrientation.Verdict.AMBIGUOUS),
                new Case("skewed", 600, 400, skewed, PageOrientation.Verdict.INSUFFICIENT));

        for (Case scenario : cases) {
            Reading reading = reading(scenario.width(), scenario.height(), scenario.regions());

            OcrRecognition recognition = reading.service().recognize(new ByteArrayInputStream(new byte[] {1}));
            List<OcrRegion> legacy = reading.service().recognizeRegions(new ByteArrayInputStream(new byte[] {1}));

            assertThat(recognition.frame().quarterTurns()).as(scenario.name()).isZero();
            assertThat(recognition.frame().verdict()).as(scenario.name()).isEqualTo(scenario.verdict());
            assertThat(recognition.frame().retryAdvised()).as(scenario.name()).isFalse();
            // The same ids, the same polygons, the same order: nothing about the page changed.
            assertThat(recognition.regions()).as(scenario.name())
                    .containsExactlyElementsOf(OcrRecognition.structured(legacy).regions());
            assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.startsWith("Page orientation decision")).reduce((first, last) -> last))
                    .as(scenario.name()).hasValueSatisfying(line -> assertThat(line)
                            .contains("verdict=" + scenario.verdict(), "action=none"));
        }
    }

    @Test
    void aRotatedPageWithoutASizeToMapByIsLeftInTheFrameOfTheImage() {
        RotatedPageFixtures page = new RotatedPageFixtures(600, 400, 1);
        Reading unsized = reading(0, 0, cafePage(page));

        OcrRecognition recognition = unsized.service().recognize(new ByteArrayInputStream(new byte[] {1}));

        assertThat(recognition.frame().quarterTurns()).isZero();
        assertThat(recognition.frame().verdict()).isEqualTo(PageOrientation.Verdict.ROTATED);
        assertThat(recognition.frame().retryAdvised()).isFalse();
        assertThat(recognition.regions()).extracting(OcrRegion::writingMode)
                .containsOnly(WritingMode.ROTATED_90);
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(line -> line.startsWith("Page orientation decision")
                        && line.contains("action=skip-page-size"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aResponseWithSeveralPagesIsNeverTurned() {
        RotatedPageFixtures page = new RotatedPageFixtures(600, 400, 1);
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        ObjectProvider<ImageAnnotatorClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        TextAnnotation.Builder annotation = TextAnnotation.newBuilder();
        for (int index = 0; index < 2; index++) {
            Page.Builder pageBuilder = Page.newBuilder().setWidth(page.pageWidth()).setHeight(page.pageHeight());
            cafePage(page).forEach(region -> pageBuilder.addBlocks(Block.newBuilder()
                    .setBlockType(Block.BlockType.TEXT).addParagraphs(proto(region))));
            annotation.addPages(pageBuilder);
        }
        when(client.batchAnnotateImages(anyList())).thenReturn(BatchAnnotateImagesResponse.newBuilder()
                .addResponses(AnnotateImageResponse.newBuilder().setFullTextAnnotation(annotation)).build());

        OcrRecognition recognition = new GoogleVisionOcrService(provider)
                .recognize(new ByteArrayInputStream(new byte[] {1}));

        assertThat(recognition.frame().quarterTurns()).isZero();
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(line -> line.startsWith("Page orientation decision")
                        && line.contains("action=skip-pages"));
    }

    @Test
    void theRegionApiNeverTurnsAPageBecauseItsCallerDrawsOnTheImageItSent() {
        RotatedPageFixtures page = new RotatedPageFixtures(600, 400, 1);
        List<OcrRegion> provider = cafePage(page);
        Reading reading = reading(page.pageWidth(), page.pageHeight(), provider);

        List<OcrRegion> regions = reading.service().recognizeRegions(new ByteArrayInputStream(new byte[] {1}));
        List<OcrService.TextBlock> blocks = reading.service()
                .recognizeTextWithLocations(new ByteArrayInputStream(new byte[] {1}));

        assertThat(regions.get(0).polygon()).isEqualTo(provider.get(0).polygon());
        assertThat(regions.get(0).writingMode()).isEqualTo(WritingMode.ROTATED_90);
        assertThat(blocks.get(0).getX()).isEqualTo(
                provider.get(0).polygon().stream().mapToInt(OcrPoint::x).min().orElseThrow());
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                .noneMatch(line -> line.startsWith("Page orientation decision"));
    }

    @Test
    void inspectionReadsTheProviderPageWithoutActingOnIt() {
        RotatedPageFixtures page = new RotatedPageFixtures(600, 400, 2);
        List<OcrRegion> provider = cafePage(page);
        Reading reading = reading(page.pageWidth(), page.pageHeight(), provider);

        GoogleVisionOcrService.ProviderInspection inspection =
                reading.service().inspect(new ByteArrayInputStream(new byte[] {1}));

        assertThat(inspection.page().width()).isEqualTo(600);
        assertThat(inspection.page().height()).isEqualTo(400);
        assertThat(inspection.page().pageCount()).isEqualTo(1);
        assertThat(inspection.page().regions()).extracting(OcrRegion::polygon)
                .containsExactlyElementsOf(provider.stream().map(OcrRegion::polygon).toList());
        assertThat(inspection.orientation().verdict()).isEqualTo(PageOrientation.Verdict.ROTATED);
        assertThat(inspection.orientation().quarterTurns()).isEqualTo(2);
        assertSingleDocumentRequest(reading.client());
    }

    // --- vertical writing ---------------------------------------------------------------------------

    @Test
    void recordsTheBreakVisionDetectedAfterEachWord() {
        Paragraph paragraph = Paragraph.newBuilder()
                .setBoundingBox(box(10, 10, 400, 60))
                .setConfidence(.95f)
                .addWords(word("南")).addWords(word("洋", BreakType.EOL_SURE_SPACE))
                .addWords(word("Green", BreakType.SPACE)).addWords(word("curry", BreakType.SURE_SPACE))
                .addWords(word("chick", BreakType.HYPHEN)).addWords(word("en", BreakType.UNKNOWN))
                .addWords(word("rice", BreakType.LINE_BREAK))
                .build();

        List<OcrRegion> regions = recognize(paragraph);

        assertThat(regions).singleElement().satisfies(region ->
                assertThat(region.words()).extracting(OcrWord::breakAfter).containsExactly(
                        OcrBreak.NONE, OcrBreak.WRAP, OcrBreak.SPACE, OcrBreak.SPACE, OcrBreak.HYPHEN,
                        OcrBreak.NONE, OcrBreak.PARAGRAPH_END));
    }

    @Test
    void aPageOfVerticalWritingIsNamedAsSuchAndNotTurned() {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, 1);
        List<OcrRegion> provider = new java.util.ArrayList<>();
        for (int index = 0; index < 3; index++) {
            provider.add(page.cjk("c" + index, "日本語の文章を読みます、そして書きます。", 10, 10 + index * 40,
                    RotatedPageFixtures.Marks.HIGH, RotatedPageFixtures.SymbolFrame.FOLLOWS_LINE, index));
        }
        Reading reading = reading(page.pageWidth(), page.pageHeight(), provider);

        OcrRecognition recognition = reading.service().recognize(new ByteArrayInputStream(new byte[] {1}));

        PageFrame frame = recognition.frame();
        assertThat(frame.verdict()).isEqualTo(PageOrientation.Verdict.VERTICAL_WRITING);
        assertThat(frame.quarterTurns()).as("named, not turned").isZero();
        assertThat(recognition.regions()).hasSize(3).allSatisfy(region -> {
            assertThat(region.writingMode()).isEqualTo(WritingMode.VERTICAL_RL);
            assertThat(region.rotationDegrees()).as("the provider's own vertices").isCloseTo(90,
                    org.assertj.core.data.Offset.offset(.01));
        });
        // The legacy entry point has no frame to report and names nothing.
        assertThat(reading.service().recognizeRegions(new ByteArrayInputStream(new byte[] {1})))
                .allSatisfy(region -> assertThat(region.writingMode()).isEqualTo(WritingMode.ROTATED_90));
        List<String> lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).filteredOn(line -> line.startsWith("Page orientation decision")).first()
                .satisfies(line -> assertThat(line).contains("verdict=VERTICAL_WRITING", "quarterTurns=1",
                        "action=mark-vertical", "verticalRegions=3"));
        assertThat(String.join("\n", lines)).doesNotContain("日本語", "c0", "c1");
    }

    @Test
    void aVerticalProseParagraphIsSplitIntoOneFlowUsingTheBreaksTheProviderRecorded() {
        RotatedPageFixtures page = new RotatedPageFixtures(400, 400, 1);
        List<OcrRegion> provider = new java.util.ArrayList<>();
        for (int index = 0; index < 3; index++) {
            provider.add(page.cjk("c" + index, "日本語の文章を読みます、そして書きます。", 10, 10 + index * 40,
                    RotatedPageFixtures.Marks.HIGH, RotatedPageFixtures.SymbolFrame.FOLLOWS_LINE, index));
        }
        provider.add(verticalProse());
        Reading reading = reading(page.pageWidth(), page.pageHeight(), provider);

        OcrRecognition recognition = reading.service().recognize(new ByteArrayInputStream(new byte[] {1}));

        assertThat(recognition.frame().verdict()).isEqualTo(PageOrientation.Verdict.VERTICAL_WRITING);
        OcrRegion flow = recognition.regions().get(recognition.regions().size() - 1);
        assertThat(flow.id()).endsWith(".f1");
        assertThat(flow.writingMode()).isEqualTo(WritingMode.VERTICAL_RL);
        assertThat(flow.flowCells()).hasSize(4);
        assertThat(flow.text()).isEqualTo("一二三四五六七八九十甲乙" + "丙丁戊己庚辛壬癸子丑寅卯"
                + "辰巳午未申酉戌亥天地玄黃" + "宇宙洪荒日月盈昃辰宿列張");
        assertThat(flow.rotationDegrees()).as("the flow is upright").isZero();
        assertThat(recognition.regions().subList(0, 3)).allSatisfy(region ->
                assertThat(region.writingMode()).isEqualTo(WritingMode.VERTICAL_RL));
    }

    /**
     * Four columns of twelve glyphs as Vision reports a vertical paragraph: one word a column, in
     * reading order from the right, every box listed from its top-right corner downwards, a wrap
     * after each column and the end of the paragraph after the last.
     */
    private static OcrRegion verticalProse() {
        String[] columns = {"一二三四五六七八九十甲乙", "丙丁戊己庚辛壬癸子丑寅卯", "辰巳午未申酉戌亥天地玄黃",
                "宇宙洪荒日月盈昃辰宿列張"};
        List<OcrWord> words = new java.util.ArrayList<>();
        for (int column = 0; column < columns.length; column++) {
            int x = 290 - column * 30;
            List<OcrSymbol> symbols = new java.util.ArrayList<>();
            int row = 0;
            for (int codePoint : columns[column].codePoints().toArray()) {
                symbols.add(new OcrSymbol(new String(Character.toChars(codePoint)),
                        downwards(x, 20 + row++ * 24, 22, 22), .97f, true));
            }
            words.add(new OcrWord(columns[column], downwards(x, 20, 22, 286), .97f, true, symbols,
                    column == columns.length - 1 ? OcrBreak.PARAGRAPH_END : OcrBreak.WRAP));
        }
        return new OcrRegion("prose", String.join("", columns), downwards(200, 20, 112, 286), words,
                .97f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("ja", .97f)), 3);
    }

    private static List<OcrPoint> downwards(int x, int y, int width, int height) {
        return List.of(new OcrPoint(x + width, y), new OcrPoint(x + width, y + height),
                new OcrPoint(x, y + height), new OcrPoint(x, y));
    }

    /** Five paragraphs of a cafe page, one of them a two-row price list, on the upright page. */
    private static List<OcrRegion> cafePage(RotatedPageFixtures page) {
        return List.of(
                page.latin("welcome", "Welcome to our little cafe", 20, 20, .97f, 0),
                page.latin("bread", "Fresh bread baked every morning", 20, 60, .96f, 1),
                page.block("menu", "en", .97f, 2,
                        new RotatedPageFixtures.Placed("Chicken", 20, 120, 100, 24),
                        new RotatedPageFixtures.Placed("110", 300, 120, 35, 24),
                        new RotatedPageFixtures.Placed("Fish", 20, 160, 100, 24),
                        new RotatedPageFixtures.Placed("90", 300, 160, 35, 24)),
                page.latin("hours", "Open daily from nine to five", 20, 240, .96f, 3),
                page.latin("thanks", "Thank you for visiting us", 20, 280, .95f, 4));
    }

    private static List<OcrRegion> latinLines(RotatedPageFixtures page, int count) {
        return latinLines(page, count, 20);
    }

    private static List<OcrRegion> latinLines(RotatedPageFixtures page, int count, int top) {
        List<OcrRegion> regions = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            regions.add(page.latin("l" + top + "-" + index, "The quick brown fox jumps over",
                    20, top + index * 40, .95f, index));
        }
        return regions;
    }

    private record Reading(GoogleVisionOcrService service, ImageAnnotatorClient client) {
    }

    @SuppressWarnings("unchecked")
    private static Reading reading(int pageWidth, int pageHeight, List<OcrRegion> regions) {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        ObjectProvider<ImageAnnotatorClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        Page.Builder page = Page.newBuilder().setWidth(pageWidth).setHeight(pageHeight);
        regions.forEach(region -> page.addBlocks(Block.newBuilder()
                .setBlockType(Block.BlockType.TEXT).addParagraphs(proto(region))));
        when(client.batchAnnotateImages(anyList())).thenReturn(BatchAnnotateImagesResponse.newBuilder()
                .addResponses(AnnotateImageResponse.newBuilder()
                        .setFullTextAnnotation(TextAnnotation.newBuilder().addPages(page)))
                .build());
        return new Reading(new GoogleVisionOcrService(provider), client);
    }

    /** The Vision paragraph that carries this region's exact vertices, down to its symbols. */
    private static Paragraph proto(OcrRegion region) {
        Paragraph.Builder paragraph = Paragraph.newBuilder()
                .setBoundingBox(polygon(region.polygon())).setConfidence(region.confidence());
        region.languages().forEach(language -> paragraph.getPropertyBuilder().addDetectedLanguages(
                TextAnnotation.DetectedLanguage.newBuilder()
                        .setLanguageCode(language.code()).setConfidence(language.confidence())));
        for (OcrWord word : region.words()) {
            Word.Builder builder = Word.newBuilder()
                    .setBoundingBox(polygon(word.polygon())).setConfidence(word.confidence());
            for (OcrSymbol symbol : word.symbols()) {
                builder.addSymbols(Symbol.newBuilder().setText(symbol.text())
                        .setBoundingBox(polygon(symbol.polygon())).setConfidence(symbol.confidence()));
            }
            BreakType recorded = switch (word.breakAfter()) {
                case WRAP -> BreakType.EOL_SURE_SPACE;
                case PARAGRAPH_END -> BreakType.LINE_BREAK;
                case SPACE -> BreakType.SPACE;
                case HYPHEN -> BreakType.HYPHEN;
                case NONE -> null;
            };
            if (recorded != null && builder.getSymbolsCount() > 0) {
                int last = builder.getSymbolsCount() - 1;
                builder.setSymbols(last, builder.getSymbols(last).toBuilder().setProperty(
                        TextAnnotation.TextProperty.newBuilder().setDetectedBreak(
                                TextAnnotation.DetectedBreak.newBuilder().setType(recorded))));
            }
            paragraph.addWords(builder);
        }
        return paragraph.build();
    }

    private static BoundingPoly polygon(List<OcrPoint> points) {
        BoundingPoly.Builder polygon = BoundingPoly.newBuilder();
        points.forEach(point -> polygon.addVertices(Vertex.newBuilder().setX(point.x()).setY(point.y())));
        return polygon.build();
    }

    private static ImageAnnotatorSettings settingsWithTimeout(Duration timeout) {
        try {
            ImageAnnotatorSettings.Builder builder = ImageAnnotatorSettings.newBuilder();
            builder.batchAnnotateImagesSettings().setRetrySettings(
                    builder.batchAnnotateImagesSettings().getRetrySettings().toBuilder()
                            .setTotalTimeoutDuration(timeout)
                            .build());
            return builder.build();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void assertSingleDocumentRequest(ImageAnnotatorClient client) {
        org.mockito.ArgumentCaptor<List<com.google.cloud.vision.v1.AnnotateImageRequest>> request =
                (org.mockito.ArgumentCaptor) org.mockito.ArgumentCaptor.forClass(List.class);
        verify(client).batchAnnotateImages(request.capture());
        verifyNoMoreInteractions(client);
        assertThat(request.getValue().get(0).getFeatures(0).getType())
                .isEqualTo(Feature.Type.DOCUMENT_TEXT_DETECTION);
    }

    @Test
    void joinsParagraphWordsWithTheBreaksVisionDetected() {
        Paragraph paragraph = Paragraph.newBuilder()
                .setBoundingBox(box(10, 10, 400, 60))
                .setConfidence(.95f)
                .addWords(word("南")).addWords(word("洋")).addWords(word("綠")).addWords(word("咖"))
                .addWords(word("哩", BreakType.EOL_SURE_SPACE))
                .addWords(word("雞")).addWords(word("飯", BreakType.SPACE))
                .addWords(word("Green", BreakType.SPACE))
                .addWords(word("curry", BreakType.SURE_SPACE))
                .addWords(word("chick", BreakType.HYPHEN))
                .addWords(word("en", BreakType.EOL_SURE_SPACE))
                .addWords(word("rice", BreakType.SPACE))
                .addWords(word("套", BreakType.UNKNOWN)).addWords(word("餐"))
                .addWords(word("NT$110", BreakType.LINE_BREAK))
                .build();

        List<OcrRegion> regions = recognize(paragraph);

        assertThat(regions).singleElement().satisfies(region -> {
            assertThat(region.text()).isEqualTo("南洋綠咖哩雞飯 Green curry chick-en rice 套餐NT$110");
            assertThat(region.words()).extracting(OcrWord::text).containsExactly(
                    "南", "洋", "綠", "咖", "哩", "雞", "飯", "Green", "curry", "chick", "en", "rice",
                    "套", "餐", "NT$110");
        });
    }

    @Test
    void joinsParagraphWordsByScriptWhenVisionReportsNoBreaks() {
        Paragraph paragraph = Paragraph.newBuilder()
                .setBoundingBox(box(10, 10, 400, 30))
                .setConfidence(.95f)
                .addWords(word("南")).addWords(word("洋")).addWords(word("綠")).addWords(word("咖"))
                .addWords(word("哩")).addWords(word("雞")).addWords(word("飯"))
                .addWords(word("Green")).addWords(word("curry")).addWords(word("rice"))
                .build();

        assertThat(recognize(paragraph)).extracting(OcrRegion::text)
                .containsExactly("南洋綠咖哩雞飯 Green curry rice");
    }

    @Test
    void honoursABreakThatVisionAttachesBeforeTheNextWord() {
        Word world = Word.newBuilder()
                .addSymbols(Symbol.newBuilder().setText("w")
                        .setProperty(TextAnnotation.TextProperty.newBuilder()
                                .setDetectedBreak(TextAnnotation.DetectedBreak.newBuilder()
                                        .setType(BreakType.SPACE).setIsPrefix(true))))
                .addSymbols(Symbol.newBuilder().setText("orld"))
                .build();
        Paragraph paragraph = Paragraph.newBuilder()
                .setBoundingBox(box(10, 10, 200, 30))
                .setConfidence(.95f)
                .addWords(word("Hello")).addWords(world)
                .build();

        assertThat(recognize(paragraph)).extracting(OcrRegion::text).containsExactly("Hello world");
    }

    @SuppressWarnings("unchecked")
    private static List<OcrRegion> recognize(Paragraph paragraph) {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        ObjectProvider<ImageAnnotatorClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        TextAnnotation annotation = TextAnnotation.newBuilder().addPages(Page.newBuilder()
                .addBlocks(Block.newBuilder().setBlockType(Block.BlockType.TEXT)
                        .addParagraphs(paragraph))).build();
        when(client.batchAnnotateImages(anyList())).thenReturn(BatchAnnotateImagesResponse.newBuilder()
                .addResponses(AnnotateImageResponse.newBuilder().setFullTextAnnotation(annotation)).build());
        return new GoogleVisionOcrService(provider)
                .recognizeRegions(new ByteArrayInputStream(new byte[] {1}));
    }

    /** The Vision paragraph that would have produced this region: its box, languages and words. */
    private static Paragraph paragraphOf(OcrRegion region) {
        java.awt.Rectangle bounds = OverlaySafetyPolicy.polygon(region.polygon()).getBounds();
        Paragraph.Builder paragraph = Paragraph.newBuilder()
                .setBoundingBox(box(bounds.x, bounds.y, bounds.width, bounds.height))
                .setConfidence(region.confidence());
        region.languages().forEach(language -> paragraph.getPropertyBuilder().addDetectedLanguages(
                TextAnnotation.DetectedLanguage.newBuilder()
                        .setLanguageCode(language.code()).setConfidence(language.confidence())));
        for (OcrWord word : region.words()) {
            java.awt.Rectangle box = OverlaySafetyPolicy.polygon(word.polygon()).getBounds();
            paragraph.addWords(word(word.text(), box.x, box.y, box.width, box.height, word.confidence()));
        }
        return paragraph.build();
    }

    /** A word whose last symbol carries the break Vision detected after it. */
    private static Word word(String value, BreakType breakType) {
        Word.Builder word = word(value).toBuilder();
        int last = word.getSymbolsCount() - 1;
        word.setSymbols(last, word.getSymbols(last).toBuilder()
                .setProperty(TextAnnotation.TextProperty.newBuilder()
                        .setDetectedBreak(TextAnnotation.DetectedBreak.newBuilder().setType(breakType))));
        return word.build();
    }

    private static Word word(String value) {
        Word.Builder word = Word.newBuilder();
        value.codePoints().forEach(codePoint -> word.addSymbols(
                Symbol.newBuilder().setText(new String(Character.toChars(codePoint)))));
        return word.build();
    }

    private static Word word(String value, int x, int y, int width, int height, float confidence) {
        Word.Builder word = Word.newBuilder().setBoundingBox(box(x, y, width, height))
                .setConfidence(confidence);
        value.codePoints().forEach(codePoint -> word.addSymbols(Symbol.newBuilder()
                .setText(new String(Character.toChars(codePoint)))
                .setBoundingBox(box(x, y, width, height))
                .setConfidence(confidence)));
        return word.build();
    }

    private static BoundingPoly box(int x, int y, int width, int height) {
        return BoundingPoly.newBuilder()
                .addVertices(Vertex.newBuilder().setX(x).setY(y))
                .addVertices(Vertex.newBuilder().setX(x + width).setY(y))
                .addVertices(Vertex.newBuilder().setX(x + width).setY(y + height))
                .addVertices(Vertex.newBuilder().setX(x).setY(y + height))
                .build();
    }
}
