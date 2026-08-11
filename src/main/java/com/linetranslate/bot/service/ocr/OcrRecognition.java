package com.linetranslate.bot.service.ocr;

import java.util.List;

public record OcrRecognition(String text, List<OcrService.TextBlock> blocks) {

    public OcrRecognition {
        text = text == null ? "" : text.strip();
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    public static OcrRecognition located(List<OcrService.TextBlock> blocks) {
        List<OcrService.TextBlock> ordered = OcrReadingOrder.sort(blocks);
        String text = ordered.stream()
                .map(OcrService.TextBlock::getText)
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return new OcrRecognition(text, ordered);
    }

    public static OcrRecognition plain(String text) {
        return new OcrRecognition(text, List.of());
    }

    public String reliableText(float threshold) {
        if (blocks.isEmpty()) {
            return text;
        }
        return blocks.stream()
                .filter(block -> isReliable(block, threshold))
                .map(OcrService.TextBlock::getText)
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    static boolean isReliable(OcrService.TextBlock block, float threshold) {
        return block.getConfidence() <= 0 || block.getConfidence() >= threshold;
    }
}
