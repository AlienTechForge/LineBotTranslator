package com.linetranslate.bot.service.ocr;

import java.util.List;

/**
 * What one OCR call read.
 *
 * @param frame the frame the regions are in. {@link PageFrame#NONE} unless the page was found to be
 *              turned a quarter, in which case the regions are in the upright frame and the frame
 *              says by how much
 */
public record OcrRecognition(
        String text, List<OcrService.TextBlock> blocks, List<OcrRegion> regions, PageFrame frame) {

    public OcrRecognition {
        text = text == null ? "" : text.strip();
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        regions = regions == null ? List.of() : List.copyOf(regions);
        frame = frame == null ? PageFrame.NONE : frame;
    }

    public OcrRecognition(String text, List<OcrService.TextBlock> blocks, List<OcrRegion> regions) {
        this(text, blocks, regions, PageFrame.NONE);
    }

    public OcrRecognition(String text, List<OcrService.TextBlock> blocks) {
        this(text, blocks, List.of());
    }

    public static OcrRecognition located(List<OcrService.TextBlock> blocks) {
        List<OcrService.TextBlock> ordered = OcrReadingOrder.sort(blocks);
        String text = ordered.stream()
                .map(OcrService.TextBlock::getText)
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return new OcrRecognition(text, ordered, List.of());
    }

    public static OcrRecognition structured(List<OcrRegion> regions) {
        return structured(regions, PageFrame.NONE);
    }

    public static OcrRecognition structured(List<OcrRegion> regions, PageFrame frame) {
        List<OcrRegion> ordered = regions == null ? List.of() : regions.stream()
                .sorted(java.util.Comparator.comparingInt(OcrRegion::readingOrder).thenComparing(OcrRegion::id))
                .toList();
        String text = ordered.stream().map(OcrRegion::text).filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return new OcrRecognition(text, List.of(), ordered, frame);
    }

    public static OcrRecognition plain(String text) {
        return new OcrRecognition(text, List.of(), List.of());
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
        return block.getConfidence() > 0 && block.getConfidence() >= threshold;
    }
}
