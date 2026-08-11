package com.linetranslate.bot.service.ocr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TranslatedBlockMapper {

    private TranslatedBlockMapper() {
    }

    /**
     * Maps only explicit translated lines. If a provider merges lines, remaining
     * source regions stay visible instead of receiving guessed translations.
     */
    public static List<ImageOverlayBlock> map(
            List<OcrService.TextBlock> blocks,
            String translatedText,
            float lowConfidenceThreshold) {
        List<OcrService.TextBlock> ordered = OcrReadingOrder.sort(blocks);
        List<String> translatedLines = translatedText == null
                ? List.of()
                : Arrays.stream(translatedText.replace('\r', '\n').split("\\n+"))
                        .map(String::strip)
                        .filter(value -> !value.isBlank())
                        .toList();
        long reliableCount = ordered.stream()
                .filter(block -> OcrRecognition.isReliable(block, lowConfidenceThreshold))
                .count();
        if (reliableCount == 1 && !translatedLines.isEmpty()) {
            translatedLines = List.of(String.join("\n", translatedLines));
        }

        List<ImageOverlayBlock> result = new ArrayList<>(ordered.size());
        int translationIndex = 0;
        int reliableIndex = 0;
        for (OcrService.TextBlock block : ordered) {
            String replacement = "";
            if (OcrRecognition.isReliable(block, lowConfidenceThreshold)) {
                if (translationIndex < translatedLines.size()) {
                    replacement = reliableIndex == reliableCount - 1
                            ? String.join("\n", translatedLines.subList(
                                    translationIndex, translatedLines.size()))
                            : translatedLines.get(translationIndex);
                    translationIndex = reliableIndex == reliableCount - 1
                            ? translatedLines.size()
                            : translationIndex + 1;
                }
                reliableIndex++;
            }
            result.add(new ImageOverlayBlock(block, replacement));
        }
        return List.copyOf(result);
    }
}
