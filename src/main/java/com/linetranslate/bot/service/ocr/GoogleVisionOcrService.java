package com.linetranslate.bot.service.ocr;

import com.google.cloud.vision.v1.*;
import com.google.protobuf.ByteString;
import com.linetranslate.bot.logging.SafeLog;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class GoogleVisionOcrService implements OcrService {

    private final ImageAnnotatorClient visionClient;

    public GoogleVisionOcrService(ObjectProvider<ImageAnnotatorClient> visionClientProvider) {
        this.visionClient = visionClientProvider.getIfAvailable();
        if (visionClient != null) {
            log.info("Google Vision OCR 服務初始化成功");
        } else {
            log.warn("Google Vision OCR 服務未能正確初始化，OCR 功能將不可用");
        }
    }

    @Override
    public String recognizeText(InputStream imageStream) {
        if (visionClient == null) {
            throw new OcrProcessingException("Google Vision client is unavailable");
        }

        try {
            // 讀取圖片數據
            byte[] imageData = imageStream.readAllBytes();
            ByteString imgBytes = ByteString.copyFrom(imageData);

            // 創建圖片
            Image image = Image.newBuilder().setContent(imgBytes).build();

            // 設置特徵類型為文本檢測
            Feature feature = Feature.newBuilder().setType(Feature.Type.TEXT_DETECTION).build();

            // 創建請求
            AnnotateImageRequest request = AnnotateImageRequest.newBuilder()
                    .addFeatures(feature)
                    .setImage(image)
                    .build();

            // 執行 OCR 請求
            BatchAnnotateImagesResponse response = visionClient.batchAnnotateImages(List.of(request));

            if (response.getResponsesCount() > 0 && response.getResponses(0).hasError()) {
                throw new OcrProcessingException("Google Vision returned an OCR error");
            }

            // 提取文本
            StringBuilder textBuilder = new StringBuilder();
            if (response.getResponsesCount() > 0) {
                TextAnnotation textAnnotation = response.getResponses(0).getFullTextAnnotation();
                if (textAnnotation != null) {
                    return textAnnotation.getText();
                }

                // 如果沒有完整文本註釋，則嘗試獲取單獨的文本註釋
                for (EntityAnnotation annotation : response.getResponses(0).getTextAnnotationsList()) {
                    if (textBuilder.length() > 0) {
                        textBuilder.append("\n");
                    }
                    textBuilder.append(annotation.getDescription());
                }
            }

            String result = textBuilder.toString();
            log.info("OCR 識別完成: content={}", SafeLog.content(result));
            return result;

        } catch (IOException e) {
            log.error("OCR 識別失敗: failure={}", SafeLog.failure(e));
            throw new OcrProcessingException("Google Vision could not read image bytes", e);
        } catch (RuntimeException failure) {
            if (failure instanceof OcrProcessingException ocrFailure) {
                throw ocrFailure;
            }
            throw new OcrProcessingException("Google Vision OCR failed", failure);
        }
    }

    @Override
    public List<TextBlock> recognizeTextWithLocations(InputStream imageStream) {
        if (visionClient == null) {
            throw new OcrProcessingException("Google Vision client is unavailable");
        }

        try {
            byte[] imageData = imageStream.readAllBytes();
            ByteString imgBytes = ByteString.copyFrom(imageData);
            Image image = Image.newBuilder().setContent(imgBytes).build();
            Feature feature = Feature.newBuilder()
                    .setType(Feature.Type.DOCUMENT_TEXT_DETECTION)
                    .build();
            AnnotateImageRequest request = AnnotateImageRequest.newBuilder()
                    .addFeatures(feature)
                    .setImage(image)
                    .build();
            BatchAnnotateImagesResponse response = visionClient.batchAnnotateImages(List.of(request));
            if (response.getResponsesCount() == 0) {
                return List.of();
            }
            var annotationResponse = response.getResponses(0);
            if (annotationResponse.hasError()) {
                throw new OcrProcessingException("Google Vision returned an OCR error");
            }

            List<TextBlock> textBlocks = new ArrayList<>();
            TextAnnotation fullText = annotationResponse.getFullTextAnnotation();
            for (var page : fullText.getPagesList()) {
                for (var block : page.getBlocksList()) {
                    for (var paragraph : block.getParagraphsList()) {
                        String text = paragraph.getWordsList().stream()
                                .map(word -> word.getSymbolsList().stream()
                                        .map(symbol -> symbol.getText())
                                        .reduce("", String::concat))
                                .filter(value -> !value.isBlank())
                                .reduce((left, right) -> left + " " + right)
                                .orElse("");
                        TextBlock located = located(text, paragraph.getBoundingBox(), paragraph.getConfidence());
                        if (located != null) {
                            textBlocks.add(located);
                        }
                    }
                }
            }
            if (textBlocks.isEmpty()) {
                boolean fullImageAnnotation = true;
                for (EntityAnnotation annotation : annotationResponse.getTextAnnotationsList()) {
                    if (fullImageAnnotation) {
                        fullImageAnnotation = false;
                        continue;
                    }
                    TextBlock located = located(
                            annotation.getDescription(), annotation.getBoundingPoly(), annotation.getScore());
                    if (located != null) {
                        textBlocks.add(located);
                    }
                }
            }
            log.info("識別到 {} 個文本塊", textBlocks.size());
            return OcrReadingOrder.sort(textBlocks);
        } catch (IOException e) {
            log.error("OCR 識別失敗: failure={}", SafeLog.failure(e));
            throw new OcrProcessingException("Google Vision could not read image bytes", e);
        } catch (RuntimeException failure) {
            if (failure instanceof OcrProcessingException ocrFailure) {
                throw ocrFailure;
            }
            throw new OcrProcessingException("Google Vision located OCR failed", failure);
        }
    }

    private static TextBlock located(String text, BoundingPoly boundingPoly, float confidence) {
        if (text == null || text.isBlank() || boundingPoly == null || boundingPoly.getVerticesCount() == 0) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Vertex vertex : boundingPoly.getVerticesList()) {
            minX = Math.min(minX, vertex.getX());
            minY = Math.min(minY, vertex.getY());
            maxX = Math.max(maxX, vertex.getX());
            maxY = Math.max(maxY, vertex.getY());
        }
        int width = maxX - minX;
        int height = maxY - minY;
        return width <= 0 || height <= 0
                ? null
                : new TextBlock(text.strip(), minX, minY, width, height, confidence);
    }
}
