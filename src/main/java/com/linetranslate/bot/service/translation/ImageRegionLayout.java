package com.linetranslate.bot.service.translation;

public record ImageRegionLayout(
        String groupId, int x, int y, int width, int height,
        int maxLines, int maxCharacters, boolean compactLabel) {
    public ImageRegionLayout {
        groupId = groupId == null || groupId.isBlank() ? "ungrouped" : groupId;
        x = Math.max(0, x);
        y = Math.max(0, y);
        width = Math.max(1, width);
        height = Math.max(1, height);
        maxLines = Math.max(1, maxLines);
        maxCharacters = Math.max(1, maxCharacters);
    }

    public static ImageRegionLayout unspecified(String regionId) {
        return new ImageRegionLayout(regionId, 0, 0, 1, 1, 1, 128, false);
    }
}
