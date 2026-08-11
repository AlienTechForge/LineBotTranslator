package com.linetranslate.bot.service.ocr;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;

/** Deterministic Java2D renderer that replaces reliable OCR regions in place. */
@Component
public class ImageTranslationOverlayRenderer {

    private static final Color LOW_CONFIDENCE = new Color(230, 138, 0);
    private static final int MIN_FONT_SIZE = 8;

    public RenderedImage render(
            ValidatedImage source,
            List<ImageOverlayBlock> blocks,
            float lowConfidenceThreshold) {
        BufferedImage output = copy(source.image());
        Graphics2D graphics = output.createGraphics();
        int rendered = 0;
        int lowConfidence = 0;
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            for (ImageOverlayBlock planned : blocks == null ? List.<ImageOverlayBlock>of() : blocks) {
                OcrService.TextBlock block = planned.source();
                Bounds bounds = Bounds.clamped(block, output.getWidth(), output.getHeight());
                if (bounds.width() <= 1 || bounds.height() <= 1) {
                    continue;
                }
                boolean uncertain = block.getConfidence() > 0
                        && block.getConfidence() < lowConfidenceThreshold;
                if (uncertain || planned.replacement().isBlank()) {
                    if (uncertain) {
                        lowConfidence++;
                        markLowConfidence(graphics, bounds);
                    }
                    continue;
                }
                Color background = sampleBackground(output, bounds);
                graphics.setColor(background);
                graphics.fillRect(bounds.x(), bounds.y(), bounds.width(), bounds.height());
                graphics.setColor(contrastingText(background));
                if (OcrReadingOrder.isVertical(block)) {
                    drawVertical(graphics, planned.replacement(), bounds);
                } else {
                    drawHorizontal(graphics, planned.replacement(), bounds);
                }
                rendered++;
            }
        } finally {
            graphics.dispose();
        }
        return new RenderedImage(encode(output), rendered, lowConfidence);
    }

    private static void drawHorizontal(Graphics2D graphics, String text, Bounds bounds) {
        Layout layout = fitHorizontal(graphics, text, bounds);
        graphics.setFont(layout.font());
        FontMetrics metrics = graphics.getFontMetrics();
        int baseline = bounds.y() + metrics.getAscent();
        for (String line : layout.lines()) {
            if (baseline > bounds.bottom()) {
                break;
            }
            graphics.drawString(line, bounds.x() + 1, baseline);
            baseline += metrics.getHeight();
        }
    }

    private static Layout fitHorizontal(Graphics2D graphics, String text, Bounds bounds) {
        int maximum = Math.max(MIN_FONT_SIZE, Math.min(48, bounds.height() - 2));
        for (int size = maximum; size >= MIN_FONT_SIZE; size--) {
            Font font = new Font(Font.SANS_SERIF, Font.PLAIN, size);
            graphics.setFont(font);
            FontMetrics metrics = graphics.getFontMetrics();
            List<String> lines = wrap(text, metrics, Math.max(1, bounds.width() - 2));
            if ((long) lines.size() * metrics.getHeight() <= bounds.height()) {
                return new Layout(font, lines);
            }
        }
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, MIN_FONT_SIZE);
        graphics.setFont(font);
        FontMetrics metrics = graphics.getFontMetrics();
        List<String> lines = wrap(text, metrics, Math.max(1, bounds.width() - 2));
        int allowed = Math.max(1, bounds.height() / Math.max(1, metrics.getHeight()));
        if (lines.size() > allowed) {
            lines = new ArrayList<>(lines.subList(0, allowed));
            int last = lines.size() - 1;
            lines.set(last, ellipsize(lines.get(last), metrics, Math.max(1, bounds.width() - 2)));
        }
        return new Layout(font, lines);
    }

    private static List<String> wrap(String text, FontMetrics metrics, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.replace('\r', '\n').split("\\n+", -1)) {
            StringBuilder line = new StringBuilder();
            int[] codePoints = paragraph.codePoints().toArray();
            for (int codePoint : codePoints) {
                String glyph = new String(Character.toChars(codePoint));
                if (!line.isEmpty() && metrics.stringWidth(line + glyph) > width) {
                    lines.add(line.toString().stripTrailing());
                    line.setLength(0);
                }
                line.append(glyph);
            }
            if (!line.isEmpty() || paragraph.isEmpty()) {
                lines.add(line.toString().strip());
            }
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    private static String ellipsize(String value, FontMetrics metrics, int width) {
        String suffix = "…";
        StringBuilder result = new StringBuilder(value);
        while (!result.isEmpty() && metrics.stringWidth(result + suffix) > width) {
            result.deleteCharAt(result.length() - 1);
        }
        return result + suffix;
    }

    private static void drawVertical(Graphics2D graphics, String text, Bounds bounds) {
        int[] codePoints = text.codePoints().filter(value -> !Character.isWhitespace(value)).toArray();
        if (codePoints.length == 0) {
            return;
        }
        int fontSize = Math.max(MIN_FONT_SIZE,
                Math.min(bounds.width() - 2, bounds.height() / codePoints.length));
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);
        graphics.setFont(font);
        FontMetrics metrics = graphics.getFontMetrics();
        int step = Math.max(metrics.getHeight(), 1);
        int baseline = bounds.y() + metrics.getAscent();
        for (int codePoint : codePoints) {
            if (baseline > bounds.bottom()) {
                break;
            }
            String glyph = new String(Character.toChars(codePoint));
            int x = bounds.x() + Math.max(0, (bounds.width() - metrics.stringWidth(glyph)) / 2);
            graphics.drawString(glyph, x, baseline);
            baseline += step;
        }
    }

    private static void markLowConfidence(Graphics2D graphics, Bounds bounds) {
        graphics.setColor(LOW_CONFIDENCE);
        graphics.setStroke(new BasicStroke(2f));
        graphics.drawRect(bounds.x(), bounds.y(), Math.max(0, bounds.width() - 1), Math.max(0, bounds.height() - 1));
        graphics.fillOval(bounds.x(), bounds.y(), Math.min(7, bounds.width()), Math.min(7, bounds.height()));
    }

    private static Color sampleBackground(BufferedImage image, Bounds bounds) {
        long red = 0;
        long green = 0;
        long blue = 0;
        long count = 0;
        int left = Math.max(0, bounds.x() - 2);
        int top = Math.max(0, bounds.y() - 2);
        int right = Math.min(image.getWidth() - 1, bounds.right() + 2);
        int bottom = Math.min(image.getHeight() - 1, bounds.bottom() + 2);
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                if (x >= bounds.x() && x <= bounds.right() && y >= bounds.y() && y <= bounds.bottom()) {
                    continue;
                }
                Color color = new Color(image.getRGB(x, y), true);
                red += color.getRed();
                green += color.getGreen();
                blue += color.getBlue();
                count++;
            }
        }
        return count == 0
                ? Color.WHITE
                : new Color((int) (red / count), (int) (green / count), (int) (blue / count));
    }

    private static Color contrastingText(Color background) {
        double luminance = 0.2126 * background.getRed()
                + 0.7152 * background.getGreen()
                + 0.0722 * background.getBlue();
        return luminance < 128 ? Color.WHITE : new Color(25, 25, 25);
    }

    private static BufferedImage copy(BufferedImage source) {
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), type);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static byte[] encode(BufferedImage image) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new IOException("PNG writer is unavailable");
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Translated image could not be encoded", failure);
        }
    }

    private record Layout(Font font, List<String> lines) {
    }

    private record Bounds(int x, int y, int width, int height) {
        static Bounds clamped(OcrService.TextBlock block, int imageWidth, int imageHeight) {
            int x = Math.max(0, Math.min(imageWidth, block.getX()));
            int y = Math.max(0, Math.min(imageHeight, block.getY()));
            int right = Math.max(x, Math.min(imageWidth, block.getX() + Math.max(0, block.getWidth())));
            int bottom = Math.max(y, Math.min(imageHeight, block.getY() + Math.max(0, block.getHeight())));
            return new Bounds(x, y, right - x, bottom - y);
        }

        int right() {
            return x + width - 1;
        }

        int bottom() {
            return y + height - 1;
        }
    }
}
