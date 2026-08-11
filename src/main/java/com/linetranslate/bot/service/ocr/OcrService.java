package com.linetranslate.bot.service.ocr;

import java.io.InputStream;
import java.util.List;
import java.util.stream.IntStream;

/**
 * OCR 服務介面，定義圖片文字識別的方法
 */
public interface OcrService {

    /**
     * 識別圖片中的文字
     *
     * @param imageStream 圖片輸入流
     * @return 識別到的文字
     */
    String recognizeText(InputStream imageStream);

    /**
     * 識別圖片中的文字並返回每個文字塊的位置信息
     *
     * @param imageStream 圖片輸入流
     * @return 文字塊列表，包含文字內容和位置信息
     */
    List<TextBlock> recognizeTextWithLocations(InputStream imageStream);

    /** Structured geometry path. Legacy implementations remain text-only. */
    default List<OcrRegion> recognizeRegions(InputStream imageStream) {
        List<TextBlock> blocks = recognizeTextWithLocations(imageStream);
        if (blocks == null) return List.of();
        return IntStream.range(0, blocks.size()).mapToObj(index -> {
            TextBlock block = blocks.get(index);
            List<OcrPoint> polygon = List.of(
                    new OcrPoint(block.x, block.y),
                    new OcrPoint(block.x + block.width, block.y),
                    new OcrPoint(block.x + block.width, block.y + block.height),
                    new OcrPoint(block.x, block.y + block.height));
            return new OcrRegion(
                    "legacy-%04d".formatted(index + 1), block.text, polygon, List.of(),
                    block.confidence, block.confidence > 0, OcrBlockType.TEXT, List.of(), index);
        }).toList();
    }

    /**
     * 表示文字塊的類，包含文字內容和位置信息
     */
    class TextBlock {
        private String text;
        private int x;
        private int y;
        private int width;
        private int height;
        private float confidence;

        public TextBlock(String text, int x, int y, int width, int height, float confidence) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.confidence = confidence;
        }

        public String getText() {
            return text;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public float getConfidence() {
            return confidence;
        }

        @Override
        public String toString() {
            return "TextBlock{" +
                    "text='" + text + '\'' +
                    ", x=" + x +
                    ", y=" + y +
                    ", width=" + width +
                    ", height=" + height +
                    ", confidence=" + confidence +
                    '}';
        }
    }
}
