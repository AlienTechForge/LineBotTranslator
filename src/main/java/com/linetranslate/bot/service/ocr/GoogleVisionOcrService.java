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
            log.error("Google Vision 客戶端未初始化");
            return "OCR 服務未正確配置，無法識別圖片文字";
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
            return "OCR 識別失敗，請稍後再試。";
        }
    }

    @Override
    public List<TextBlock> recognizeTextWithLocations(InputStream imageStream) {
        List<TextBlock> textBlocks = new ArrayList<>();

        if (visionClient == null) {
            log.error("Google Vision 客戶端未初始化");
            return textBlocks;
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

            // 提取文本塊
            if (response.getResponsesCount() > 0) {
                // 跳過第一個結果，因為它是整個圖片的文本
                boolean isFirst = true;

                for (EntityAnnotation annotation : response.getResponses(0).getTextAnnotationsList()) {
                    if (isFirst) {
                        isFirst = false;
                        continue;
                    }

                    // 獲取位置信息
                    BoundingPoly boundingPoly = annotation.getBoundingPoly();

                    // 計算邊界框的坐標
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

                    // 創建文本塊
                    TextBlock textBlock = new TextBlock(
                            annotation.getDescription(),
                            minX,
                            minY,
                            width,
                            height,
                            annotation.getScore()
                    );

                    textBlocks.add(textBlock);
                }
            }

            log.info("識別到 {} 個文本塊", textBlocks.size());
            return textBlocks;

        } catch (IOException e) {
            log.error("OCR 識別失敗: failure={}", SafeLog.failure(e));
            return textBlocks;
        }
    }
}
