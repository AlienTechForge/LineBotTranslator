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
                        .addBlocks(Block.newBuilder().addParagraphs(paragraph)))
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

    private static Word word(String value) {
        Word.Builder word = Word.newBuilder();
        value.codePoints().forEach(codePoint -> word.addSymbols(
                Symbol.newBuilder().setText(new String(Character.toChars(codePoint)))));
        return word.build();
    }
}
