package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.google.cloud.vision.v1.AnnotateImageResponse;
import com.google.cloud.vision.v1.BatchAnnotateImagesResponse;
import com.google.cloud.vision.v1.Block;
import com.google.cloud.vision.v1.BoundingPoly;
import com.google.cloud.vision.v1.Feature;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.Page;
import com.google.cloud.vision.v1.Paragraph;
import com.google.cloud.vision.v1.Symbol;
import com.google.cloud.vision.v1.TextAnnotation;
import com.google.cloud.vision.v1.TextAnnotation.DetectedBreak.BreakType;
import com.google.cloud.vision.v1.Vertex;
import com.google.cloud.vision.v1.Word;

class GoogleVisionOcrServiceTests {

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
