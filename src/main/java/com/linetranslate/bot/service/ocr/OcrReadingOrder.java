package com.linetranslate.bot.service.ocr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class OcrReadingOrder {

    private OcrReadingOrder() {
    }

    public static List<OcrService.TextBlock> sort(List<OcrService.TextBlock> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        List<OcrService.TextBlock> blocks = input.stream()
                .filter(block -> block != null && block.getText() != null && !block.getText().isBlank())
                .toList();
        long vertical = blocks.stream().filter(OcrReadingOrder::isVertical).count();
        return vertical * 2 > blocks.size() ? vertical(blocks) : horizontal(blocks);
    }

    public static boolean isVertical(OcrService.TextBlock block) {
        return block.getHeight() > block.getWidth() * 3L / 2L;
    }

    private static List<OcrService.TextBlock> horizontal(List<OcrService.TextBlock> blocks) {
        List<OcrService.TextBlock> pending = new ArrayList<>(blocks);
        pending.sort(Comparator.comparingInt(OcrReadingOrder::centerY)
                .thenComparingInt(OcrService.TextBlock::getX));
        List<List<OcrService.TextBlock>> lines = new ArrayList<>();
        for (OcrService.TextBlock block : pending) {
            List<OcrService.TextBlock> line = lines.stream()
                    .filter(candidate -> sameHorizontalLine(candidate, block))
                    .findFirst()
                    .orElseGet(() -> {
                        List<OcrService.TextBlock> created = new ArrayList<>();
                        lines.add(created);
                        return created;
                    });
            line.add(block);
        }
        lines.sort(Comparator.comparingInt(line -> line.stream()
                .mapToInt(OcrReadingOrder::centerY).min().orElse(0)));
        List<OcrService.TextBlock> result = new ArrayList<>();
        for (List<OcrService.TextBlock> line : lines) {
            line.sort(Comparator.comparingInt(OcrService.TextBlock::getX));
            result.addAll(line);
        }
        return List.copyOf(result);
    }

    private static List<OcrService.TextBlock> vertical(List<OcrService.TextBlock> blocks) {
        List<OcrService.TextBlock> pending = new ArrayList<>(blocks);
        pending.sort(Comparator.comparingInt(OcrReadingOrder::centerX).reversed()
                .thenComparingInt(OcrService.TextBlock::getY));
        List<List<OcrService.TextBlock>> columns = new ArrayList<>();
        for (OcrService.TextBlock block : pending) {
            List<OcrService.TextBlock> column = columns.stream()
                    .filter(candidate -> sameVerticalColumn(candidate, block))
                    .findFirst()
                    .orElseGet(() -> {
                        List<OcrService.TextBlock> created = new ArrayList<>();
                        columns.add(created);
                        return created;
                    });
            column.add(block);
        }
        columns.sort(Comparator.<List<OcrService.TextBlock>>comparingInt(column -> column.stream()
                .mapToInt(OcrReadingOrder::centerX).max().orElse(0)).reversed());
        List<OcrService.TextBlock> result = new ArrayList<>();
        for (List<OcrService.TextBlock> column : columns) {
            column.sort(Comparator.comparingInt(OcrService.TextBlock::getY));
            result.addAll(column);
        }
        return List.copyOf(result);
    }

    private static boolean sameHorizontalLine(List<OcrService.TextBlock> line, OcrService.TextBlock block) {
        OcrService.TextBlock anchor = line.get(0);
        int tolerance = Math.max(anchor.getHeight(), block.getHeight()) / 2;
        return Math.abs(centerY(anchor) - centerY(block)) <= Math.max(2, tolerance);
    }

    private static boolean sameVerticalColumn(List<OcrService.TextBlock> column, OcrService.TextBlock block) {
        OcrService.TextBlock anchor = column.get(0);
        int tolerance = Math.max(anchor.getWidth(), block.getWidth()) / 2;
        return Math.abs(centerX(anchor) - centerX(block)) <= Math.max(2, tolerance);
    }

    private static int centerX(OcrService.TextBlock block) {
        return block.getX() + block.getWidth() / 2;
    }

    private static int centerY(OcrService.TextBlock block) {
        return block.getY() + block.getHeight() / 2;
    }
}
