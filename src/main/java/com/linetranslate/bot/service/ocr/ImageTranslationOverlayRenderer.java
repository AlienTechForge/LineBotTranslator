package com.linetranslate.bot.service.ocr;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;

/** Deterministic Java2D renderer that replaces reliable OCR regions in place. */
@Component
public class ImageTranslationOverlayRenderer {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ImageTranslationOverlayRenderer.class);
    private static final Color LOW_CONFIDENCE = new Color(230, 138, 0);
    private static final int MIN_FONT_SIZE = 8;
    /** Glyph width scales tried in order; 0.8 is the narrowest that stays comfortably readable. */
    private static final double[] CONDENSE_STEPS = {1d, .9d, .8d};
    private final ImageTranslationFontProvider fontProvider;

    @org.springframework.beans.factory.annotation.Autowired
    public ImageTranslationOverlayRenderer(ImageTranslationFontProvider fontProvider) {
        this.fontProvider = fontProvider;
    }

    public ImageTranslationOverlayRenderer() {
        this(new ImageTranslationFontProvider());
    }

    /** Polygon-aware production path. Refuses plans not approved by safety policy. */
    public RenderedImage render(ValidatedImage source, OverlaySafetyPlan plan) {
        if (plan == null || !plan.safe()) return new RenderedImage(
                encode(copy(source.image())), 0, plan == null ? 0 : plan.skipped(),
                plan == null ? List.of() : plan.decisions());
        BufferedImage output = copy(source.image());
        BufferedImage pristine = source.image();
        Graphics2D graphics = output.createGraphics();
        int rendered = 0;
        int fontSkipped = 0;
        List<OverlayRenderDecision> decisions = new ArrayList<>(plan.decisions());
        List<PreparedOverlay> prepared = new ArrayList<>();
        try {
            // Exact clip invariant: antialiasing may blend pixels just outside a polygon edge.
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (ImageRegionOverlay overlay : plan.overlays()) {
                ImageTranslationTextStyle style = ImageTranslationStyleEstimator.estimate(
                        pristine, overlay.region());
                Font supportedFont = fontProvider.fontFor(
                        overlay.replacement(), 12, style.fontStyle()).orElse(null);
                if (supportedFont == null) {
                    fontSkipped++;
                    decisions.add(new OverlayRenderDecision(
                            overlay.region().id(), OverlayRenderStatus.PRESERVED, "font-coverage"));
                    continue;
                }
                Area layoutMask = OverlaySafetyPolicy.layoutMask(
                        overlay.region(), output.getWidth(), output.getHeight());
                Area cleanupMask = OverlaySafetyPolicy.sourceCleanupMask(
                        overlay.region(), output.getWidth(), output.getHeight());
                java.awt.Rectangle bounds = layoutMask.getBounds();
                if (layoutMask.isEmpty() || cleanupMask.isEmpty()
                        || bounds.width <= 1 || bounds.height <= 1) {
                    decisions.add(new OverlayRenderDecision(
                            overlay.region().id(), OverlayRenderStatus.PRESERVED, "empty-mask"));
                    continue;
                }
                Area modifiedMask = OverlaySafetyPolicy.mask(
                        overlay.region(), output.getWidth(), output.getHeight());
                List<OcrPoint> polygon = overlay.region().polygon();
                OcrPoint origin = polygon.get(0);
                OcrPoint edge = polygon.get(1);
                double angle = Math.atan2(edge.y() - origin.y(), edge.x() - origin.x());
                double localWidth = Math.hypot(edge.x() - origin.x(), edge.y() - origin.y());
                OcrPoint side = polygon.get(polygon.size() - 1);
                double localHeight = Math.hypot(side.x() - origin.x(), side.y() - origin.y());
                int localWidthPixels = Math.max(2, (int) Math.round(localWidth));
                int localHeightPixels = Math.max(2, (int) Math.round(localHeight));
                int inset = Math.min(2, Math.max(0,
                        (Math.min(localWidthPixels, localHeightPixels) - 2) / 2));
                Bounds localBounds = new Bounds(
                        inset, inset,
                        Math.max(2, localWidthPixels - inset * 2),
                        Math.max(2, localHeightPixels - inset * 2));
                Layout layout = fitHorizontal(graphics, overlay.replacement(), localBounds,
                        supportedFont, style.maximumFontSize(), overlay.region().compactLabel());
                if (!layout.fits()) {
                    decisions.add(new OverlayRenderDecision(
                            overlay.region().id(), OverlayRenderStatus.PRESERVED, "text-fit"));
                    log.info("Overlay cell preserved by fit: cellWidth={}, cellHeight={}, "
                                    + "sourceFontSize={}, translatedChars={}, lines={}, compactLabel={}",
                            localBounds.width(), localBounds.height(), style.maximumFontSize(),
                            overlay.replacement().codePointCount(0, overlay.replacement().length()),
                            layout.lines().size(), overlay.region().compactLabel());
                    continue;
                }
                prepared.add(new PreparedOverlay(overlay, style, layoutMask, modifiedMask,
                        OverlaySafetyPolicy.sourceCleanupAreas(
                                overlay.region(), output.getWidth(), output.getHeight()),
                        origin, angle, localBounds, layout));
            }

            prepared = normalizeGroupFontSizes(graphics, prepared);

            Area combinedMask = new Area();
            for (PreparedOverlay item : prepared) combinedMask.add(new Area(item.modifiedMask()));
            for (PreparedOverlay item : prepared) {
                for (Area cleanupArea : item.cleanupAreas()) {
                    graphics.setColor(ImageTranslationStyleEstimator.localBackground(
                            pristine, cleanupArea, item.style().background(), item.style().foreground()));
                    graphics.fill(cleanupArea);
                }
                restoreOutsideMask(output, pristine, combinedMask, item.modifiedMask().getBounds());
            }

            for (PreparedOverlay item : prepared) {
                Shape oldClip = graphics.getClip();
                AffineTransform oldTransform = graphics.getTransform();
                try {
                    graphics.clip(item.layoutMask());
                    graphics.setColor(item.style().foreground());
                    graphics.translate(item.origin().x(), item.origin().y());
                    graphics.rotate(item.angle());
                    drawHorizontal(graphics, item.localBounds(), item.layout());
                } finally {
                    graphics.setTransform(oldTransform);
                    graphics.setClip(oldClip);
                }
                rendered++;
                decisions.add(new OverlayRenderDecision(
                        item.overlay().region().id(), OverlayRenderStatus.RENDERED, "rendered"));
            }
        } finally {
            graphics.dispose();
        }
        return new RenderedImage(encode(output), rendered, plan.skipped() + fontSkipped, decisions);
    }

    /**
     * One visual group must read as one table. Each region independently maximises its own font
     * size, so equal-width cells end up with jumping type whenever their translations differ in
     * length. The group therefore adopts the smallest size that any of its members already fits at.
     */
    private static List<PreparedOverlay> normalizeGroupFontSizes(
            Graphics2D graphics, List<PreparedOverlay> prepared) {
        java.util.Map<String, Integer> smallest = new java.util.HashMap<>();
        for (PreparedOverlay item : prepared) {
            smallest.merge(item.overlay().region().groupId(), item.layout().font().getSize(), Math::min);
        }
        List<PreparedOverlay> result = new ArrayList<>(prepared.size());
        for (PreparedOverlay item : prepared) {
            int target = smallest.getOrDefault(
                    item.overlay().region().groupId(), item.layout().font().getSize());
            if (target >= item.layout().font().getSize()) {
                result.add(item);
                continue;
            }
            Font font = item.layout().font().deriveFont((float) target);
            graphics.setFont(font);
            FontMetrics metrics = graphics.getFontMetrics();
            List<String> lines = wrap(item.overlay().replacement(), metrics,
                    Math.max(1, item.localBounds().width() - 2));
            if (textHeight(metrics, lines.size()) > item.localBounds().height()) {
                result.add(item);
                continue;
            }
            result.add(new PreparedOverlay(item.overlay(), item.style(), item.layoutMask(),
                    item.modifiedMask(), item.cleanupAreas(), item.origin(), item.angle(),
                    item.localBounds(), new Layout(font, lines, true)));
        }
        return result;
    }

    /** Restores the one-pixel rasterization fringe outside the approved overlay geometry. */
    private static void restoreOutsideMask(BufferedImage output, BufferedImage pristine, Area mask) {
        restoreOutsideMask(output, pristine, mask, mask.getBounds());
    }

    private static void restoreOutsideMask(
            BufferedImage output,
            BufferedImage pristine,
            Area mask,
            java.awt.Rectangle localBounds) {
        java.awt.Rectangle bounds = new java.awt.Rectangle(localBounds);
        bounds.grow(1, 1);
        int left = Math.max(0, bounds.x);
        int top = Math.max(0, bounds.y);
        int right = Math.min(output.getWidth(), bounds.x + bounds.width);
        int bottom = Math.min(output.getHeight(), bounds.y + bounds.height);
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if (!mask.contains(x + .5, y + .5)) output.setRGB(x, y, pristine.getRGB(x, y));
            }
        }
    }

    private static void drawHorizontal(
            Graphics2D graphics,
            Bounds bounds,
            Layout layout) {
        graphics.setFont(layout.font());
        FontMetrics metrics = graphics.getFontMetrics();
        int baseline = bounds.y() + metrics.getAscent();
        for (String line : layout.lines()) {
            if (baseline > bounds.bottom()) break;
            graphics.drawString(line, bounds.x() + 1, baseline);
            baseline += metrics.getHeight();
        }
    }

    private static Layout fitHorizontal(
            Graphics2D graphics,
            String text,
            Bounds bounds,
            Font baseFont,
            int maximumFontSize,
            boolean compactLabel) {
        int maximum = Math.max(MIN_FONT_SIZE, Math.min(maximumFontSize, bounds.height() - 2));
        // sourceFontSize measures the OCR glyph box, which is consistently ~1.3x the cell height, so
        // deriving the compact floor from it alone lifts the floor above every size that can fit.
        int compactBasis = Math.min(maximumFontSize, bounds.height());
        int minimum = compactLabel
                ? Math.min(maximum, Math.max(MIN_FONT_SIZE, (int) Math.ceil(compactBasis * .55)))
                : MIN_FONT_SIZE;
        // A fixed-width cell is limited by width, not height, and a translation usually needs more
        // characters than its source. Shrinking type uniformly spends readable height to buy width,
        // so try the full size range at each condensing step before giving the whole cell up.
        for (double widthScale : CONDENSE_STEPS) {
            for (int size = maximum; size >= minimum; size--) {
                Font font = condensed(baseFont, size, widthScale);
                graphics.setFont(font);
                FontMetrics metrics = graphics.getFontMetrics();
                List<String> lines = wrap(text, metrics, Math.max(1, bounds.width() - 2));
                if (textHeight(metrics, lines.size()) <= bounds.height()) {
                    return new Layout(font, lines, true);
                }
            }
        }
        Font font = condensed(baseFont, minimum, CONDENSE_STEPS[CONDENSE_STEPS.length - 1]);
        graphics.setFont(font);
        List<String> lines = wrap(text, graphics.getFontMetrics(), Math.max(1, bounds.width() - 2));
        return new Layout(font, lines, false);
    }

    /**
     * Ink height of a wrapped block. Leading separates one line from the next, so it belongs between
     * lines and never after the last one. Charging every line a full {@code getHeight()} overstates
     * a single line by the whole leading, which for CJK faces is a large fraction of the type size.
     */
    private static int textHeight(FontMetrics metrics, int lineCount) {
        int lines = Math.max(1, lineCount);
        return (lines - 1) * metrics.getHeight() + metrics.getAscent() + metrics.getDescent();
    }

    /** Narrows glyphs without touching their height, which is what a narrow cell actually lacks. */
    private static Font condensed(Font base, int size, double widthScale) {
        Font sized = base.deriveFont((float) size);
        return widthScale >= 1d
                ? sized
                : sized.deriveFont(AffineTransform.getScaleInstance(widthScale, 1d));
    }

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
                return new Layout(font, lines, true);
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
        return new Layout(font, lines, lines.size() <= allowed);
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

    private record Layout(Font font, List<String> lines, boolean fits) {
    }

    private record PreparedOverlay(
            ImageRegionOverlay overlay,
            ImageTranslationTextStyle style,
            Area layoutMask,
            Area modifiedMask,
            List<Area> cleanupAreas,
            OcrPoint origin,
            double angle,
            Bounds localBounds,
            Layout layout) {
        PreparedOverlay {
            cleanupAreas = List.copyOf(cleanupAreas);
        }
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
