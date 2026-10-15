package com.linetranslate.bot.service.ocr;

import java.awt.AlphaComposite;
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
import java.util.Iterator;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deterministic Java2D renderer that replaces reliable OCR regions in place. */
@Component
public class ImageTranslationOverlayRenderer {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ImageTranslationOverlayRenderer.class);
    private static final Color LOW_CONFIDENCE = new Color(230, 138, 0);
    private static final int MIN_FONT_SIZE = 8;
    /**
     * Glyph width scales; at each size, tried in order before the size is reduced. 0.8 is the
     * narrowest that stays comfortably readable.
     */
    private static final double[] CONDENSE_STEPS = {1d, .9d, .8d};
    /**
     * Floor for a compact label that cannot fit above its proportional floor. Prose may go down to
     * MIN_FONT_SIZE, but a table cell rendered that small reads as noise, so a label either lands
     * at or above this size or keeps its source text.
     */
    private static final int READABLE_LABEL_FLOOR = 12;
    /** Height-to-width ratio above which a region is treated as a column rather than a line. */
    private static final double NARROW_COLUMN_RATIO = 1.8;
    /** Relative opposite-edge mismatch above which placement error is worth reporting. */
    private static final double SKEW_REPORT_THRESHOLD = .02;
    /** Share of a cleanup area still holding source ink above which the erase is worth reporting. */
    private static final double RESIDUAL_INK_THRESHOLD = .05;
    /** LINE refuses an image message whose original is larger than 10 MB. */
    private static final long DEFAULT_MAX_OUTPUT_BYTES = 10_485_760L;
    private static final String PNG_CONTENT_TYPE = "image/png";
    private static final String JPEG_CONTENT_TYPE = "image/jpeg";
    /**
     * Size an original should stay under to be fetched reliably: LINE downloads it itself, within
     * its own time limit, from this deployment's host (which may sit behind a modest uplink). A
     * lossless PNG of a photo is several megabytes, so opaque output above this is delivered as
     * JPEG when that fits.
     */
    private static final long DEFAULT_DELIVERY_TARGET_BYTES = 2_097_152L;
    /** JPEG qualities tried in order to bring opaque output under the delivery target. */
    private static final float[] DELIVERY_JPEG_QUALITIES = {.92f, .85f};
    /** JPEG qualities tried in order once lossless PNG exceeds the cap; opaque output only. */
    private static final float[] JPEG_QUALITIES = {.92f, .85f, .75f};
    /** Linear scale applied per step when re-encoding alone cannot meet the cap. */
    private static final double DOWNSCALE_STEP = .85;
    private static final int MAX_DOWNSCALE_STEPS = 6;
    private final ImageTranslationFontProvider fontProvider;
    private final long maxOutputBytes;
    private final long deliveryTargetBytes;

    /**
     * @param maxOutputBytes hard cap on the delivered image (LINE refuses an original above 10 MB)
     * @param deliveryTargetBytes size an opaque image is brought under by JPEG when its PNG is
     *        larger; a value at or above {@code maxOutputBytes} switches that step off
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ImageTranslationOverlayRenderer(
            ImageTranslationFontProvider fontProvider,
            @Value("${app.image-translation.max-output-bytes:10485760}") long maxOutputBytes,
            @Value("${app.image-translation.delivery-target-bytes:2097152}") long deliveryTargetBytes) {
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("Rendered image size cap must be positive");
        }
        if (deliveryTargetBytes <= 0) {
            throw new IllegalArgumentException("Rendered image delivery target must be positive");
        }
        this.fontProvider = fontProvider;
        this.maxOutputBytes = maxOutputBytes;
        this.deliveryTargetBytes = Math.min(deliveryTargetBytes, maxOutputBytes);
    }

    public ImageTranslationOverlayRenderer(ImageTranslationFontProvider fontProvider, long maxOutputBytes) {
        this(fontProvider, maxOutputBytes, DEFAULT_DELIVERY_TARGET_BYTES);
    }

    public ImageTranslationOverlayRenderer(ImageTranslationFontProvider fontProvider) {
        this(fontProvider, DEFAULT_MAX_OUTPUT_BYTES);
    }

    public ImageTranslationOverlayRenderer() {
        this(new ImageTranslationFontProvider());
    }

    /**
     * Polygon-aware production path. Refuses plans not approved by safety policy.
     *
     * <p>The source pixels are needed only while drawing. The method lets go of {@code source}
     * before it encodes, so a caller that passes on its last reference (the image pipeline does)
     * has the source raster collectable while the encoder, the largest allocator of the request,
     * runs on the rendered raster alone.
     */
    public RenderedImage render(ValidatedImage source, OverlaySafetyPlan plan) {
        if (plan == null || !plan.safe()) return new RenderedImage(
                encode(copy(source.image())), 0, plan == null ? 0 : plan.skipped(),
                plan == null ? List.of() : plan.decisions());
        Drawn drawn = draw(source.image(), plan);
        source = null;
        return new RenderedImage(
                encode(drawn.output()), drawn.rendered(), drawn.skipped(), drawn.decisions());
    }

    /** Draws the approved overlays onto a copy of {@code pristine}; the source stays untouched. */
    private Drawn draw(BufferedImage pristine, OverlaySafetyPlan plan) {
        BufferedImage output = copy(pristine);
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
                // A vertical CJK column is far taller than it is wide, and Latin text laid out
                // across that width breaks down to one or two characters per line. Turning the
                // drawing frame a quarter turn swaps the axes, so the translation runs along the
                // column instead of across it. Start from the top-right corner: after the extra
                // quarter turn the local x axis points down the column and y points back across it.
                if (localHeightPixels > localWidthPixels * NARROW_COLUMN_RATIO
                        && isMostlyLatin(overlay.replacement())) {
                    origin = polygon.get(1);
                    angle += Math.PI / 2;
                    int rotated = localWidthPixels;
                    localWidthPixels = localHeightPixels;
                    localHeightPixels = rotated;
                }
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
                logPolygonSkew(overlay.region(), localWidthPixels, localHeightPixels);
                prepared.add(new PreparedOverlay(overlay, style, layoutMask, modifiedMask,
                        OverlaySafetyPolicy.sourceCleanupAreas(
                                overlay.region(), output.getWidth(), output.getHeight()),
                        origin, angle, localBounds, layout));
            }

            prepared = normalizeGroupFontSizes(graphics, prepared);

            List<Area> approvedMasks = prepared.stream().map(PreparedOverlay::modifiedMask).toList();
            for (PreparedOverlay item : prepared) {
                for (Area cleanupArea : item.cleanupAreas()) {
                    graphics.setColor(ImageTranslationStyleEstimator.localBackground(
                            pristine, cleanupArea, item.style().background(), item.style().foreground()));
                    graphics.fill(cleanupArea);
                }
                restoreOutsideMask(output, pristine, approvedMasks, item.modifiedMask().getBounds());
                logResidualInk(output, item);
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
        return new Drawn(output, rendered, plan.skipped() + fontSkipped, decisions);
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
            int lineWidth = Math.max(1, item.localBounds().width() - 2);
            List<String> lines = wrap(item.overlay().replacement(), metrics, lineWidth);
            if (textHeight(metrics, lines.size()) > item.localBounds().height()
                    || widestLine(metrics, lines) > lineWidth) {
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
        restoreOutsideMask(output, pristine, List.of(mask), mask.getBounds());
    }

    /**
     * Pixels inside no approved mask return to their source value. The masks are rasterised over
     * the bounds instead of testing each pixel against their union, whose outline grows with every
     * region and made this step quadratic in the region count.
     */
    private static void restoreOutsideMask(
            BufferedImage output,
            BufferedImage pristine,
            List<Area> masks,
            java.awt.Rectangle localBounds) {
        java.awt.Rectangle bounds = new java.awt.Rectangle(localBounds);
        bounds.grow(1, 1);
        int left = Math.max(0, bounds.x);
        int top = Math.max(0, bounds.y);
        int right = Math.min(output.getWidth(), bounds.x + bounds.width);
        int bottom = Math.min(output.getHeight(), bounds.y + bounds.height);
        if (left >= right || top >= bottom) return;
        int width = right - left;
        boolean[] inside = OverlaySafetyPolicy.coverage(
                masks, new java.awt.Rectangle(left, top, width, bottom - top));
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if (!inside[(y - top) * width + x - left]) output.setRGB(x, y, pristine.getRGB(x, y));
            }
        }
    }

    private static void drawHorizontal(
            Graphics2D graphics,
            Bounds bounds,
            Layout layout) {
        graphics.setFont(layout.font());
        FontMetrics metrics = graphics.getFontMetrics();
        // A block shorter than its region is centred so it does not sit on the top edge over blank
        // background. A block taller than the region gets no offset: it starts at the top and the
        // guard below drops the lines that run past the bottom, rather than pushing ink above it.
        int slack = Math.max(0, bounds.height() - textHeight(metrics, layout.lines().size()));
        int baseline = bounds.y() + slack / 2 + metrics.getAscent();
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
        int compactFloor = compactLabel
                ? Math.min(maximum, Math.max(MIN_FONT_SIZE, (int) Math.ceil(compactBasis * .55)))
                : MIN_FONT_SIZE;
        // The compact floor keeps short labels from shrinking far below their source. A long
        // translation in the same cell is not a short label, so when nothing fits above the floor
        // the search continues down to the readability minimum rather than abandoning the cell.
        Layout preferred = fitBetween(graphics, text, bounds, baseFont, maximum, compactFloor);
        if (preferred != null) return preferred;
        if (compactFloor > READABLE_LABEL_FLOOR) {
            Layout smaller = fitBetween(
                    graphics, text, bounds, baseFont, compactFloor - 1, READABLE_LABEL_FLOOR);
            if (smaller != null) return smaller;
        }
        // Nothing fit at any size, condensed or not, by height or by width: the caller keeps the
        // source pixels (text-fit) rather than drawing this layout.
        Font font = condensed(baseFont, MIN_FONT_SIZE, CONDENSE_STEPS[CONDENSE_STEPS.length - 1]);
        graphics.setFont(font);
        List<String> lines = wrap(text, graphics.getFontMetrics(), Math.max(1, bounds.width() - 2));
        return new Layout(font, lines, false);
    }

    /**
     * Share of a cleanup area still holding source-coloured pixels after the background fill. A
     * complete erase leaves this near zero; a high value means the glyph mask did not cover the
     * real ink, which is what would leave the source showing through the translation.
     */
    static double residualInkRatio(
            BufferedImage output, List<Area> cleanupAreas, Color foreground, Color background) {
        long inside = 0;
        long residual = 0;
        int foregroundToBackground = colourDistanceSquared(foreground, background);
        if (foregroundToBackground <= 0) return 0d;
        for (Area area : cleanupAreas) {
            java.awt.Rectangle bounds = area.getBounds();
            int left = Math.max(0, bounds.x);
            int top = Math.max(0, bounds.y);
            int right = Math.min(output.getWidth(), bounds.x + bounds.width);
            int bottom = Math.min(output.getHeight(), bounds.y + bounds.height);
            for (int y = top; y < bottom; y++) {
                for (int x = left; x < right; x++) {
                    if (!area.contains(x + .5, y + .5)) continue;
                    inside++;
                    Color pixel = new Color(output.getRGB(x, y), true);
                    // Closer to the source text colour than to the background means ink survived.
                    if (colourDistanceSquared(pixel, foreground)
                            < colourDistanceSquared(pixel, background)) {
                        residual++;
                    }
                }
            }
        }
        return inside == 0 ? 0d : (double) residual / inside;
    }

    private static int colourDistanceSquared(Color left, Color right) {
        int red = left.getRed() - right.getRed();
        int green = left.getGreen() - right.getGreen();
        int blue = left.getBlue() - right.getBlue();
        return red * red + green * green + blue * blue;
    }

    private static void logResidualInk(BufferedImage output, PreparedOverlay item) {
        double residual = residualInkRatio(output, item.cleanupAreas(),
                item.style().foreground(), item.style().background());
        if (residual < RESIDUAL_INK_THRESHOLD) return;
        java.awt.Rectangle bounds = item.modifiedMask().getBounds();
        log.info("Overlay cleanup left source ink: residual={}, cleanupAreas={}, "
                        + "cellWidth={}, cellHeight={}, skew={}",
                String.format(java.util.Locale.ROOT, "%.3f", residual),
                item.cleanupAreas().size(), bounds.width, bounds.height,
                String.format(java.util.Locale.ROOT, "%.3f",
                        polygonSkew(item.overlay().region().polygon())));
    }

    /**
     * Overlays are placed with translate + rotate, which can only reproduce a parallelogram. A
     * perspective view of a plane yields a trapezoid, and the fourth polygon point is never read,
     * so any deviation from a parallelogram is placement error. Opposite edges of a parallelogram
     * are equal vectors, so their difference relative to edge length measures that deviation.
     */
    static double polygonSkew(List<OcrPoint> polygon) {
        if (polygon == null || polygon.size() != 4) return 0d;
        OcrPoint p0 = polygon.get(0);
        OcrPoint p1 = polygon.get(1);
        OcrPoint p2 = polygon.get(2);
        OcrPoint p3 = polygon.get(3);
        double topX = p1.x() - p0.x();
        double topY = p1.y() - p0.y();
        double bottomX = p2.x() - p3.x();
        double bottomY = p2.y() - p3.y();
        double sideX = p3.x() - p0.x();
        double sideY = p3.y() - p0.y();
        double oppositeX = p2.x() - p1.x();
        double oppositeY = p2.y() - p1.y();
        double topSkew = Math.hypot(topX - bottomX, topY - bottomY)
                / Math.max(1d, Math.hypot(topX, topY));
        double sideSkew = Math.hypot(sideX - oppositeX, sideY - oppositeY)
                / Math.max(1d, Math.hypot(sideX, sideY));
        return Math.max(topSkew, sideSkew);
    }

    private static void logPolygonSkew(OcrRegion region, int localWidth, int localHeight) {
        double skew = polygonSkew(region.polygon());
        if (skew < SKEW_REPORT_THRESHOLD) return;
        log.info("Overlay polygon is not a parallelogram: skew={}, cellWidth={}, cellHeight={}, "
                        + "corners={}",
                String.format(java.util.Locale.ROOT, "%.3f", skew), localWidth, localHeight,
                region.polygon().stream()
                        .map(point -> point.x() + "," + point.y())
                        .reduce((left, right) -> left + " " + right).orElse(""));
    }

    /**
     * Largest layout that fits within a closed size range, or null when nothing in the range fits.
     * A fixed-width cell is limited by width, not height, and a translation usually needs more
     * characters than its source. Narrowing glyphs keeps their height, which shrinking does not,
     * so every condensing step is tried at a size before the next smaller size is. A layout fits
     * only when it is short enough and its widest line is no wider than the cell: wrapping cannot
     * split a single glyph, so in a cell thinner than a glyph the height alone is no evidence.
     */
    private static Layout fitBetween(
            Graphics2D graphics,
            String text,
            Bounds bounds,
            Font baseFont,
            int highest,
            int lowest) {
        if (highest < lowest) return null;
        for (int size = highest; size >= lowest; size--) {
            for (double widthScale : CONDENSE_STEPS) {
                Font font = condensed(baseFont, size, widthScale);
                graphics.setFont(font);
                FontMetrics metrics = graphics.getFontMetrics();
                int lineWidth = Math.max(1, bounds.width() - 2);
                List<String> lines = wrap(text, metrics, lineWidth);
                if (textHeight(metrics, lines.size()) <= bounds.height()
                        && widestLine(metrics, lines) <= lineWidth) {
                    return new Layout(font, lines, true);
                }
            }
        }
        return null;
    }

    /** Width of the widest line as it is drawn, which is what has to fit inside the cell. */
    private static int widestLine(FontMetrics metrics, List<String> lines) {
        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, metrics.stringWidth(line));
        }
        return widest;
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

    /**
     * Whether a replacement is written in a script that cannot be set vertically glyph by glyph.
     * CJK reads fine down a column, Latin does not, so only the latter needs the frame turned.
     */
    private static boolean isMostlyLatin(String value) {
        long latin = value.codePoints().filter(Character::isLetter)
                .filter(code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.LATIN)
                .count();
        long letters = value.codePoints().filter(Character::isLetter).count();
        return letters > 0 && latin * 2 > letters;
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
        int slack = Math.max(0, bounds.height() - textHeight(metrics, layout.lines().size()));
        int baseline = bounds.y() + slack / 2 + metrics.getAscent();
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

    /**
     * Greedy line filling. Han and kana may break between any two glyphs, but a word in a
     * space-separated script, Hangul included, may only break at a space, so it moves to the next
     * line whole. Only a word wider than a line on its own is broken glyph by glyph. A space at a
     * break hangs off the end of the line and is dropped there, so the next line never starts with
     * it.
     */
    static List<String> wrap(String text, FontMetrics metrics, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.replace('\r', '\n').split("\\n+", -1)) {
            StringBuilder line = new StringBuilder();
            for (String token : wrapTokens(paragraph)) {
                if (Character.isWhitespace(token.codePointAt(0)) && !line.toString().isBlank()) {
                    line.append(token);
                    continue;
                }
                if (!line.isEmpty() && metrics.stringWidth(line + token) > width) {
                    lines.add(line.toString().stripTrailing());
                    line.setLength(0);
                }
                if (!line.isEmpty() || metrics.stringWidth(token) <= width) {
                    line.append(token);
                    continue;
                }
                for (int codePoint : token.codePoints().toArray()) {
                    String glyph = new String(Character.toChars(codePoint));
                    if (!line.isEmpty() && metrics.stringWidth(line + glyph) > width) {
                        lines.add(line.toString().stripTrailing());
                        line.setLength(0);
                    }
                    line.append(glyph);
                }
            }
            if (!line.isEmpty() || paragraph.isEmpty()) {
                lines.add(line.toString().strip());
            }
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    /**
     * Units a line may not break inside: each space, each Han or kana glyph, and each run of
     * anything else, which is a word together with its punctuation. A Hangul syllable is part of
     * such a run, so a Korean word keeps together exactly as a Latin one does and a hyphen is not
     * a break point. A run holding no letter or digit protects no word, so it falls apart into
     * glyphs; that keeps ellipses, dashes and quotes between CJK glyphs breakable exactly as before.
     */
    private static List<String> wrapTokens(String paragraph) {
        List<String> tokens = new ArrayList<>();
        int[] codePoints = paragraph.codePoints().toArray();
        int start = 0;
        while (start < codePoints.length) {
            int end = start + 1;
            boolean word = Character.isLetterOrDigit(codePoints[start]);
            if (!Character.isWhitespace(codePoints[start]) && !breaksAtEveryGlyph(codePoints[start])) {
                while (end < codePoints.length && !Character.isWhitespace(codePoints[end])
                        && !breaksAtEveryGlyph(codePoints[end])) {
                    word |= Character.isLetterOrDigit(codePoints[end]);
                    end++;
                }
            }
            if (word) {
                tokens.add(new String(codePoints, start, end - start));
            } else {
                for (int index = start; index < end; index++) {
                    tokens.add(new String(Character.toChars(codePoints[index])));
                }
            }
            start = end;
        }
        return tokens;
    }

    private static boolean breaksAtEveryGlyph(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        // Korean is written with spaces and, like CSS word-break: keep-all, breaks only at them.
        // Checked first so the half-width jamo in the forms block below are not split either.
        if (script == Character.UnicodeScript.HANGUL) {
            return false;
        }
        if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.BOPOMOFO) {
            return true;
        }
        // Shared punctuation and marks such as 。，「」ー・ live in CJK blocks under the common script.
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_FORMS
                || block == Character.UnicodeBlock.VERTICAL_FORMS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA;
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

    /**
     * Lossless PNG while it stays under the delivery target. LINE fetches the original itself and
     * a photo re-encoded as PNG is several times its JPEG size, so an opaque image whose PNG is
     * above the target is delivered as JPEG (the first of {@code DELIVERY_JPEG_QUALITIES} that
     * fits). When no JPEG fits, or the image is transparent
     * (JPEG cannot carry alpha), the PNG is kept while it fits the output cap. Beyond the cap LINE
     * cannot fetch the original at all, so opaque output falls back to JPEG, then any output is
     * downscaled in bounded steps. Output that still does not fit is refused like an unencodable
     * image, which degrades the reply to text.
     *
     * <p>The result carries {@code image} itself only when the bytes are its lossless PNG, so that a
     * consumer wanting the pixels need not decode them again. Package-private so a test can observe
     * what is still reachable when the encoder starts.
     */
    RenderedImage.Encoded encode(BufferedImage image) {
        try {
            byte[] png = writePng(image);
            if (png.length <= deliveryTargetBytes) {
                return new RenderedImage.Encoded(png, PNG_CONTENT_TYPE, image);
            }
            // JPEG cannot carry alpha, so transparent output only ever shrinks as PNG.
            boolean opaque = !image.getColorModel().hasAlpha();
            // A target at the cap leaves nothing to try: the cap fallback below makes the same attempts.
            String lowestQualityJpegBytes = "none";
            if (opaque && deliveryTargetBytes < maxOutputBytes) {
                for (float quality : DELIVERY_JPEG_QUALITIES) {
                    byte[] jpeg = writeJpeg(image, quality);
                    if (jpeg.length <= deliveryTargetBytes) {
                        log.info("Translated image re-encoded to fit the delivery target: width={}, height={}, "
                                        + "pngBytes={}, outputBytes={}, format={}, quality={}, "
                                        + "deliveryTargetBytes={}",
                                image.getWidth(), image.getHeight(), png.length, jpeg.length,
                                JPEG_CONTENT_TYPE, String.format(java.util.Locale.ROOT, "%.2f", quality),
                                deliveryTargetBytes);
                        return new RenderedImage.Encoded(jpeg, JPEG_CONTENT_TYPE, null);
                    }
                    lowestQualityJpegBytes = String.valueOf(jpeg.length);
                }
            }
            if (png.length <= maxOutputBytes) {
                log.info("Translated image kept as PNG above the delivery target: width={}, height={}, "
                                + "pngBytes={}, opaque={}, lowestQualityJpegBytes={}, deliveryTargetBytes={}, "
                                + "maxOutputBytes={}",
                        image.getWidth(), image.getHeight(), png.length, opaque, lowestQualityJpegBytes,
                        deliveryTargetBytes, maxOutputBytes);
                return new RenderedImage.Encoded(png, PNG_CONTENT_TYPE, image);
            }
            String contentType = opaque ? JPEG_CONTENT_TYPE : PNG_CONTENT_TYPE;
            if (opaque) {
                for (float quality : JPEG_QUALITIES) {
                    byte[] jpeg = writeJpeg(image, quality);
                    if (jpeg.length <= maxOutputBytes) {
                        return degraded(image, png.length, jpeg, contentType, 1d);
                    }
                }
            }
            double scale = 1d;
            int lastBytes = png.length;
            for (int step = 0; step < MAX_DOWNSCALE_STEPS; step++) {
                scale *= DOWNSCALE_STEP;
                BufferedImage smaller = scaled(image, scale);
                byte[] bytes = opaque
                        ? writeJpeg(smaller, JPEG_QUALITIES[JPEG_QUALITIES.length - 1])
                        : writePng(smaller);
                if (bytes.length <= maxOutputBytes) {
                    return degraded(image, png.length, bytes, contentType, scale);
                }
                lastBytes = bytes.length;
            }
            log.warn("Translated image exceeds the output cap: width={}, height={}, pngBytes={}, "
                            + "lastBytes={}, format={}, scale={}, maxOutputBytes={}",
                    image.getWidth(), image.getHeight(), png.length, lastBytes, contentType,
                    String.format(java.util.Locale.ROOT, "%.3f", scale), maxOutputBytes);
            throw new IOException("Translated image exceeds the output cap");
        } catch (IOException failure) {
            throw new RenderedImageEncodingException("Translated image could not be encoded", failure);
        }
    }

    private RenderedImage.Encoded degraded(
            BufferedImage image, int pngBytes, byte[] bytes, String contentType, double scale) {
        log.info("Translated image degraded to fit the output cap: width={}, height={}, pngBytes={}, "
                        + "outputBytes={}, format={}, scale={}, maxOutputBytes={}",
                image.getWidth(), image.getHeight(), pngBytes, bytes.length, contentType,
                String.format(java.util.Locale.ROOT, "%.3f", scale), maxOutputBytes);
        return new RenderedImage.Encoded(bytes, contentType, null);
    }

    private static byte[] writePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG writer is unavailable");
        }
        return output.toByteArray();
    }

    private static byte[] writeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG writer is unavailable");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream stream = new MemoryCacheImageOutputStream(output)) {
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private static BufferedImage scaled(BufferedImage source, double scale) {
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage result = new BufferedImage(width, height, type);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private record Layout(Font font, List<String> lines, boolean fits) {
    }

    /** The rendered raster and the bookkeeping of one draw, before it is encoded. */
    private record Drawn(
            BufferedImage output, int rendered, int skipped, List<OverlayRenderDecision> decisions) {
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
