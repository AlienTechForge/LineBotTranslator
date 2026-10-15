package com.linetranslate.bot.service.ocr;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Synthetic stand-in for the 723x1024 Vietnamese infographic that lost its last section to the
 * total-coverage cap: 38 reliable, horizontal text lines stacked in six coloured cards, the last
 * nine of them (the whole of section 6) being the ones that fall over a 35% cap when regions are
 * accepted in reading order. Geometry only; no text of the real poster.
 */
final class SyntheticPoster {
    static final int WIDTH = 723;
    static final int HEIGHT = 1024;
    static final int REGIONS = 38;
    /** Regions of section 6, the ones that were left in Vietnamese. */
    static final int LAST_SECTION_REGIONS = 9;
    private static final int PITCH = 26;
    private static final int LINE_HEIGHT = 20;
    private static final int LEFT = 40;
    /** Five white cards and a tinted last one: about a fifth of the pixels are colourful. */
    private static final Color[] CARD_COLOURS = {
        Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE, new Color(255, 205, 210)};

    private SyntheticPoster() {
    }

    static String regionId(int index) {
        return String.format("r-%04d", index + 1);
    }

    /** Text lines on flat cards, the way an infographic is set. */
    static BufferedImage posterImage() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            int rowsPerCard = (REGIONS - LAST_SECTION_REGIONS) / 5;
            for (int index = 0; index < REGIONS; index++) {
                int card = Math.min(5, index / rowsPerCard);
                if (index >= REGIONS - LAST_SECTION_REGIONS) card = 5;
                graphics.setColor(CARD_COLOURS[card]);
                graphics.fillRect(0, top(index) - 3, WIDTH, PITCH);
            }
            graphics.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setFont(new Font("DejaVu Sans", Font.PLAIN, 13));
            graphics.setColor(new Color(33, 33, 33));
            for (int index = 0; index < REGIONS; index++) {
                graphics.drawString("source line " + index, LEFT + 2, top(index) + 15);
            }
            // Icons and badges: saturated, but they sit beside the text, not under it.
            for (int index = 0; index < REGIONS; index += 3) {
                graphics.setColor(new Color(229, 57, 53));
                graphics.fillOval(8, top(index) + 1, 18, 18);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /**
     * The same geometry over a photograph: no flat surface anywhere, strong noise on a gradient,
     * so the pixels around every text box vary far more than a printed background does.
     */
    static BufferedImage photoImage() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int noise = random.nextInt(81) - 40;
                int red = clamp(60 + x * 190 / WIDTH + noise);
                int green = clamp(40 + y * 170 / HEIGHT + random.nextInt(81) - 40);
                int blue = clamp(120 + (x + y) * 60 / (WIDTH + HEIGHT) + random.nextInt(81) - 40);
                image.setRGB(x, y, new Color(red, green, blue).getRGB());
            }
        }
        return image;
    }

    /**
     * Width of line {@code index}. The first 29 lines come to about 34% of the image once their
     * masks are padded, which leaves no room for any of the nine lines of section 6.
     */
    static int width(int index) {
        return index < REGIONS - LAST_SECTION_REGIONS ? 322 + (index % 4) * 8 : 340;
    }

    static int top(int index) {
        return 8 + index * PITCH;
    }

    static List<OcrRegion> regions() {
        List<OcrRegion> regions = new ArrayList<>();
        for (int index = 0; index < REGIONS; index++) {
            int x = LEFT;
            int y = top(index);
            int width = width(index);
            List<OcrPoint> polygon = rectangle(x, y, width, LINE_HEIGHT);
            regions.add(new OcrRegion(regionId(index), "source line " + index, polygon,
                    List.of(new OcrWord("source", rectangle(x, y, width, LINE_HEIGHT), .98f, true)),
                    .98f, true, OcrBlockType.TEXT, List.of(new OcrDetectedLanguage("vi", .98f)),
                    index, "g" + index / 5, false));
        }
        return List.copyOf(regions);
    }

    static List<ImageRegionOverlay> overlays() {
        return regions().stream()
                .map(region -> new ImageRegionOverlay(region, "譯文"))
                .toList();
    }

    static List<OcrPoint> rectangle(int x, int y, int width, int height) {
        return List.of(new OcrPoint(x, y), new OcrPoint(x + width, y),
                new OcrPoint(x + width, y + height), new OcrPoint(x, y + height));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
