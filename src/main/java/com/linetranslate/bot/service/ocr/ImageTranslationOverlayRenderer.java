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
    static final int MIN_FONT_SIZE = 8;
    /**
     * Glyph width scales; at each size, tried in order before the size is reduced. 0.8 is the
     * narrowest that stays comfortably readable.
     */
    static final double[] CONDENSE_STEPS = {1d, .9d, .8d};
    /**
     * Floor for a compact label that cannot fit above its proportional floor. Prose may go down to
     * MIN_FONT_SIZE, but a table cell rendered that small reads as noise, so a label either lands
     * at or above this size or keeps its source text.
     */
    static final int READABLE_LABEL_FLOOR = 12;
    /**
     * A cell may take the empty space under it only to get its type up to this share of the source
     * type size. Below it a layout is a last resort (or none at all, and the source text stays).
     */
    private static final double GROWTH_TARGET_SHARE = .55;
    /** The target above is never lower than this, whatever the source size. */
    private static final int GROWTH_TARGET_MINIMUM = 9;
    /** Share of a row under the cell that must still be background for the row to count as free. */
    private static final double FREE_ROW_BACKGROUND_SHARE = .95;
    /** Height-to-width ratio above which a region is treated as a column rather than a line. */
    private static final double NARROW_COLUMN_RATIO = 1.8;
    /**
     * A Latin translation in a column is set upright, across the column, unless that is smaller than
     * this share of the size it reaches when turned to run along the column: the turned layout wins
     * only when it is clearly larger, so an upright one is never given up for a marginal gain.
     */
    private static final double UPRIGHT_PREFERENCE = .8;
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

    /**
     * As {@link #render(ValidatedImage, OverlaySafetyPlan)}, for a page that was brought upright:
     * {@code source} is the upright raster, the plan is in its frame, and the output is turned
     * back clockwise by {@code restoreQuarterTurns} before it is encoded, so the delivered image
     * has the dimensions of the original and differs from it only where text was replaced.
     *
     * <p>Memory stays at two rasters. While drawing there are the upright source and the upright
     * output; once the output is drawn the source is let go, and the output is turned into the
     * delivered raster and let go in turn, so the encoder sees the delivered raster alone. This is
     * a method of its own, not a call from the one above, because a caller that delegates keeps
     * the source reachable in its own frame for as long as the callee runs.
     */
    public RenderedImage render(ValidatedImage source, OverlaySafetyPlan plan, int restoreQuarterTurns) {
        if (plan == null || !plan.safe()) {
            // Turning gives a new raster; only an unturned one has to be copied.
            BufferedImage unchanged = restoreQuarterTurns == 0
                    ? copy(source.image()) : restore(source.image(), restoreQuarterTurns);
            return new RenderedImage(
                    encode(unchanged), 0, plan == null ? 0 : plan.skipped(),
                    plan == null ? List.of() : plan.decisions());
        }
        Drawn drawn = draw(source.image(), plan);
        source = null;
        if (restoreQuarterTurns != 0) {
            // The assignment drops the record that holds the upright output, so only the delivered
            // raster is reachable from here on.
            drawn = drawn.withOutput(restore(drawn.output(), restoreQuarterTurns));
        }
        return new RenderedImage(
                encode(drawn.output()), drawn.rendered(), drawn.skipped(), drawn.decisions());
    }

    /**
     * Turns an upright output back clockwise into the frame of the original image. Package-private
     * so a test can see what is still reachable when the turned raster exists.
     */
    BufferedImage restore(BufferedImage upright, int quarterTurns) {
        return QuarterTurns.restore(upright, quarterTurns);
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
            // A cell that grows must stay clear of every other region the plan will modify.
            List<java.awt.Rectangle> approvedBounds = plan.overlays().stream()
                    .map(approved -> OverlaySafetyPolicy.mask(
                            approved.region(), output.getWidth(), output.getHeight()).getBounds())
                    .toList();
            // What growing may still spend: enlarged masks are charged against the plan's total
            // limit and its single-region limit, and each band granted is kept clear of by the next.
            double imageArea = (double) output.getWidth() * output.getHeight();
            double remainingRatio = plan.remainingRatio();
            List<java.awt.Rectangle> grantedBands = new ArrayList<>();
            for (int overlayIndex = 0; overlayIndex < plan.overlays().size(); overlayIndex++) {
                ImageRegionOverlay overlay = plan.overlays().get(overlayIndex);
                OcrRegion region = overlay.region();
                ImageTranslationTextStyle style = ImageTranslationStyleEstimator.estimate(pristine, region);
                // A Chinese or Japanese translation of vertical writing is set in columns, and the
                // font has to cover the glyphs drawn there: the vertical presentation forms, not the
                // punctuation they stand in for.
                boolean columnar = region.writingMode().vertical() && isMostlyCjk(overlay.replacement());
                Font supportedFont = fontProvider.fontFor(
                        columnar ? VerticalTextLayout.mapped(overlay.replacement()) : overlay.replacement(),
                        12, style.fontStyle()).orElse(null);
                if (supportedFont == null) {
                    fontSkipped++;
                    decisions.add(new OverlayRenderDecision(
                            region.id(), OverlayRenderStatus.PRESERVED, "font-coverage"));
                    continue;
                }
                Area layoutMask = OverlaySafetyPolicy.layoutMask(
                        region, output.getWidth(), output.getHeight());
                Area cleanupMask = OverlaySafetyPolicy.sourceCleanupMask(
                        region, output.getWidth(), output.getHeight());
                java.awt.Rectangle bounds = layoutMask.getBounds();
                if (layoutMask.isEmpty() || cleanupMask.isEmpty()
                        || bounds.width <= 1 || bounds.height <= 1) {
                    decisions.add(new OverlayRenderDecision(
                            region.id(), OverlayRenderStatus.PRESERVED, "empty-mask"));
                    continue;
                }
                Area modifiedMask = OverlaySafetyPolicy.mask(
                        region, output.getWidth(), output.getHeight());
                Frame frame = Frame.of(region);
                if (columnar) {
                    List<VerticalTextLayout.Cell> cells = columnCells(region, frame);
                    VerticalTextLayout.Layout columns = VerticalTextLayout.fit(graphics,
                            overlay.replacement(), cells, supportedFont, style.maximumFontSize(),
                            region.compactLabel());
                    Bounds frameBounds = frame.bounds();
                    if (!columns.fits()) {
                        decisions.add(new OverlayRenderDecision(
                                region.id(), OverlayRenderStatus.PRESERVED, "text-fit"));
                        logPreservedByFit(overlay, style, frameBounds, columns.columns().size(), 0, 0, 0,
                                "vertical");
                        continue;
                    }
                    logPolygonSkew(region, frame.width(), frame.height());
                    prepared.add(PreparedOverlay.columns(overlay, style, layoutMask, modifiedMask,
                            OverlaySafetyPolicy.sourceCleanupAreas(
                                    region, output.getWidth(), output.getHeight()),
                            frame.origin(), frame.angle(), frameBounds, cells, columns));
                    continue;
                }
                // A tall cell of Latin text, and every cell of vertical writing, is laid out both ways:
                // upright across the cell, and turned a quarter so that the text runs along it. A
                // column of a vertical menu is too narrow for the first and a flow of columns is too
                // broad for the second, so neither is right for all of them. A cell whose frame is
                // itself turned or skewed keeps the turn it always had.
                WritingMode mode = region.writingMode();
                boolean tall = frame.height() > frame.width() * NARROW_COLUMN_RATIO
                        && isMostlyLatin(overlay.replacement());
                Candidate chosen;
                if (mode.vertical() || (mode == WritingMode.HORIZONTAL && tall)) {
                    chosen = prefer(
                            candidate(graphics, overlay.replacement(), frame, supportedFont, style, region),
                            candidate(graphics, overlay.replacement(), frame.turn(), supportedFont, style, region));
                } else {
                    chosen = candidate(graphics, overlay.replacement(), tall ? frame.turn() : frame,
                            supportedFont, style, region);
                }
                boolean turned = chosen.frame().turned();
                OcrPoint origin = chosen.frame().origin();
                double angle = chosen.frame().angle();
                int localWidthPixels = chosen.frame().width();
                int localHeightPixels = chosen.frame().height();
                Bounds localBounds = chosen.bounds();
                Layout layout = chosen.layout();
                int freeRows = 0;
                int affordableRows = 0;
                Growth growth = null;
                // Growing downward is a rule of horizontal cells; a cell of vertical writing keeps its bounds.
                if (!layout.fits() && !mode.vertical() && remainingRatio > 0 && plan.maxRegionRatio() > 0) {
                    List<java.awt.Rectangle> others = new ArrayList<>(approvedBounds);
                    others.remove(overlayIndex);
                    others.addAll(grantedBands);
                    freeRows = freeRowsBelow(pristine, style.background(), origin, angle,
                            localWidthPixels, localHeightPixels,
                            Math.min(localHeightPixels, style.maximumFontSize()), others);
                    affordableRows = affordableRows(region, layoutMask, modifiedMask,
                            origin, angle, localWidthPixels, localHeightPixels, freeRows,
                            output.getWidth(), output.getHeight(), plan.maxRegionRatio(), remainingRatio);
                    Grown grown = growDownward(graphics, overlay.replacement(), localBounds,
                            supportedFont, style.maximumFontSize(), region.compactLabel(),
                            affordableRows);
                    if (grown != null) {
                        Area band = band(origin, angle, localWidthPixels, localHeightPixels,
                                grown.rows(), output.getWidth(), output.getHeight());
                        double charged = OverlaySafetyPolicy.area(subtract(band, modifiedMask)) / imageArea;
                        remainingRatio = Math.max(0, remainingRatio - charged);
                        grantedBands.add(band.getBounds());
                        growth = new Growth(grown.rows(), layoutMask, modifiedMask, localBounds,
                                localWidthPixels, localHeightPixels,
                                output.getWidth(), output.getHeight());
                        layoutMask = union(layoutMask, band);
                        modifiedMask = union(modifiedMask, band);
                        log.info("Overlay cell grown into free space: cellWidth={}, cellHeight={}, "
                                        + "grownBy={}, fontSize={}, sourceFontSize={}, translatedChars={}, "
                                        + "grownRatio={}, remainingRatio={}",
                                localBounds.width(), localBounds.height(), grown.rows(),
                                grown.layout().font().getSize(), style.maximumFontSize(),
                                overlay.replacement().codePointCount(0, overlay.replacement().length()),
                                ratio(charged), ratio(remainingRatio));
                        localBounds = localBounds.taller(grown.rows());
                        layout = grown.layout();
                    }
                }
                if (!layout.fits()) {
                    decisions.add(new OverlayRenderDecision(
                            region.id(), OverlayRenderStatus.PRESERVED, "text-fit"));
                    logPreservedByFit(overlay, style, localBounds, layout.lines().size(), freeRows,
                            affordableRows, remainingRatio, turned ? "turned" : "horizontal");
                    continue;
                }
                logPolygonSkew(region, localWidthPixels, localHeightPixels);
                prepared.add(PreparedOverlay.lines(overlay, style, layoutMask, modifiedMask,
                        OverlaySafetyPolicy.sourceCleanupAreas(
                                region, output.getWidth(), output.getHeight()),
                        origin, angle, localBounds, layout, growth));
            }

            prepared = normalizeGroupFontSizes(graphics, prepared, decisions);

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
                    if (item.kind() == LayoutKind.COLUMNS) {
                        VerticalTextLayout.draw(graphics, item.columns(), item.cells());
                    } else {
                        drawHorizontal(graphics, item.localBounds(), item.layout());
                    }
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

    /** The frame a region is drawn in: its origin, its line direction and its size along and across it. */
    private record Frame(
            OcrPoint origin, OcrPoint turnOrigin, double angle, int width, int height, boolean turned) {

        /**
         * The region's reading frame. Upright vertical writing is read in the frame of the image,
         * whatever corner the provider started at, so its width is the width across the column.
         */
        static Frame of(OcrRegion region) {
            List<OcrPoint> polygon = region.writingMode().vertical() ? region.uprightPolygon() : region.polygon();
            OcrPoint origin = polygon.get(0);
            OcrPoint edge = polygon.get(1);
            OcrPoint side = polygon.get(polygon.size() - 1);
            double angle = Math.atan2(edge.y() - origin.y(), edge.x() - origin.x());
            double width = Math.hypot(edge.x() - origin.x(), edge.y() - origin.y());
            double height = Math.hypot(side.x() - origin.x(), side.y() - origin.y());
            return new Frame(origin, edge, angle, Math.max(2, (int) Math.round(width)),
                    Math.max(2, (int) Math.round(height)), false);
        }

        /**
         * The frame turned a quarter turn about the top-right corner, so that its x axis runs down
         * the cell and its y axis back across it, and the axes swap. Text laid out in it runs along
         * a column, and its lines stack towards the left of the image.
         */
        Frame turn() {
            return new Frame(turnOrigin, turnOrigin, angle + Math.PI / 2, height, width, true);
        }

        /** The margin the horizontal engine leaves round a cell: two pixels where the cell has them to spare. */
        int margin() {
            return Math.min(2, Math.max(0, (Math.min(width, height) - 2) / 2));
        }

        /** The area text may use, inside the margin. */
        Bounds bounds() {
            int inset = margin();
            return new Bounds(inset, inset, Math.max(2, width - inset * 2), Math.max(2, height - inset * 2));
        }
    }

    /** A layout of the horizontal engine in one frame of the cell. */
    private record Candidate(Frame frame, Bounds bounds, Layout layout) {
        int size() {
            return layout.font().getSize();
        }
    }

    private static Candidate candidate(Graphics2D graphics, String replacement, Frame frame, Font font,
            ImageTranslationTextStyle style, OcrRegion region) {
        Bounds bounds = frame.bounds();
        return new Candidate(frame, bounds,
                fitHorizontal(graphics, replacement, bounds, font, style.maximumFontSize(), region.compactLabel()));
    }

    /**
     * The upright candidate if it fits without breaking a word and is not clearly smaller than the
     * turned one, and the turned one otherwise: the frame a tall cell was always drawn in, so that
     * growing and the log describe what they always did. An upright layout that only fits because
     * a word is split between its letters, one letter to a line in a narrow cell, is not a layout
     * the turn gave up for; the turn reads along the column and keeps the word whole.
     */
    private static Candidate prefer(Candidate upright, Candidate turned) {
        if (upright.layout().fits() && !upright.layout().splitsWords()
                && (!turned.layout().fits() || upright.size() >= UPRIGHT_PREFERENCE * turned.size())) {
            return upright;
        }
        return turned;
    }

    /**
     * The cells vertical writing is set in, in the drawing frame and inside the inset: the cells of
     * a flow, or the whole region when it is one cell.
     */
    private static List<VerticalTextLayout.Cell> columnCells(OcrRegion region, Frame frame) {
        Bounds whole = frame.bounds();
        List<VerticalTextLayout.Cell> single = List.of(
                new VerticalTextLayout.Cell(whole.x(), whole.y(), whole.width(), whole.height()));
        if (region.flowCells().isEmpty()) return single;
        int inset = frame.margin();
        double cos = Math.cos(frame.angle());
        double sin = Math.sin(frame.angle());
        List<VerticalTextLayout.Cell> cells = new ArrayList<>(region.flowCells().size());
        for (List<OcrPoint> polygon : region.flowCells()) {
            double left = Double.POSITIVE_INFINITY;
            double top = Double.POSITIVE_INFINITY;
            double right = Double.NEGATIVE_INFINITY;
            double bottom = Double.NEGATIVE_INFINITY;
            for (OcrPoint point : polygon) {
                double dx = point.x() - frame.origin().x();
                double dy = point.y() - frame.origin().y();
                double x = dx * cos + dy * sin;
                double y = -dx * sin + dy * cos;
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
            if (!Double.isFinite(left) || !Double.isFinite(top)) continue;
            int x0 = Math.max(0, (int) Math.floor(left)) + inset;
            int y0 = Math.max(0, (int) Math.floor(top)) + inset;
            int x1 = Math.min(frame.width(), (int) Math.ceil(right)) - inset;
            int y1 = Math.min(frame.height(), (int) Math.ceil(bottom)) - inset;
            if (x1 - x0 >= 2 && y1 - y0 >= 2) cells.add(new VerticalTextLayout.Cell(x0, y0, x1 - x0, y1 - y0));
        }
        return cells.isEmpty() ? single : List.copyOf(cells);
    }

    private static void logPreservedByFit(ImageRegionOverlay overlay, ImageTranslationTextStyle style,
            Bounds localBounds, int lines, int freeRows, int affordableRows, double remainingRatio,
            String layoutName) {
        log.info("Overlay cell preserved by fit: cellWidth={}, cellHeight={}, "
                        + "sourceFontSize={}, translatedChars={}, lines={}, compactLabel={}, "
                        + "freeRowsBelow={}, affordableRows={}, remainingRatio={}, "
                        + "writingMode={}, layout={}",
                localBounds.width(), localBounds.height(), style.maximumFontSize(),
                overlay.replacement().codePointCount(0, overlay.replacement().length()),
                lines, overlay.region().compactLabel(), freeRows,
                affordableRows, ratio(remainingRatio), overlay.region().writingMode(), layoutName);
    }

    /**
     * One visual group must read as one table. Each region independently maximises its own font
     * size, so equal-width cells end up with jumping type whenever their translations differ in
     * length. The group therefore adopts the smallest size that any of its members already fits at.
     */
    private static List<PreparedOverlay> normalizeGroupFontSizes(
            Graphics2D graphics, List<PreparedOverlay> prepared, List<OverlayRenderDecision> decisions) {
        java.util.Map<String, Integer> smallest = new java.util.HashMap<>();
        for (PreparedOverlay item : prepared) {
            smallest.merge(item.overlay().region().groupId(), item.fontSize(), Math::min);
        }
        List<PreparedOverlay> result = new ArrayList<>(prepared.size());
        for (PreparedOverlay item : prepared) {
            int target = smallest.getOrDefault(item.overlay().region().groupId(), item.fontSize());
            if (target >= item.fontSize()) {
                result.add(item);
                continue;
            }
            if (item.kind() == LayoutKind.COLUMNS) {
                // Each cell is re-fitted by its own engine at the group size. Cells of vertical
                // writing never grew, so there is no band to give back.
                VerticalTextLayout.Layout refitted = VerticalTextLayout.layoutAt(graphics,
                        item.columns().units(), item.cells(), item.columns().font(), target,
                        item.columns().condense());
                result.add(refitted.fits() ? item.withColumns(refitted) : item);
                continue;
            }
            Font font = item.layout().font().deriveFont((float) target);
            graphics.setFont(font);
            FontMetrics metrics = graphics.getFontMetrics();
            int lineWidth = Math.max(1, item.localBounds().width() - 2);
            boolean[] split = new boolean[1];
            List<String> lines = wrap(item.overlay().replacement(), metrics, lineWidth, split);
            if (textHeight(metrics, lines.size()) > item.localBounds().height()
                    || widestLine(metrics, lines) > lineWidth) {
                result.add(item);
                continue;
            }
            Layout layout = new Layout(font, lines, true, split[0]);
            if (item.growth() == null) {
                result.add(item.withLayout(layout));
                continue;
            }
            // A cell that only has a layout because it grew must still be worth its band at the
            // size the group settles on, and takes only the rows that size uses.
            int sourceSize = item.style().maximumFontSize();
            if (target < growthTarget(sourceSize)) {
                decisions.add(new OverlayRenderDecision(
                        item.overlay().region().id(), OverlayRenderStatus.PRESERVED, "text-fit"));
                log.info("Overlay grown cell preserved by group size: groupFontSize={}, "
                                + "growthTarget={}, sourceFontSize={}, grownBy={}, translatedChars={}",
                        target, growthTarget(sourceSize), sourceSize, item.growth().rows(),
                        item.overlay().replacement().codePointCount(
                                0, item.overlay().replacement().length()));
                continue;
            }
            result.add(item.withLayout(layout).trimmedTo(
                    Math.max(0, textHeight(metrics, lines.size()) - item.growth().baseBounds().height())));
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
        boolean[] split = new boolean[1];
        List<String> lines = wrap(text, graphics.getFontMetrics(), Math.max(1, bounds.width() - 2), split);
        return new Layout(font, lines, false, split[0]);
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
                boolean[] split = new boolean[1];
                List<String> lines = wrap(text, metrics, lineWidth, split);
                if (textHeight(metrics, lines.size()) <= bounds.height()
                        && widestLine(metrics, lines) <= lineWidth) {
                    return new Layout(font, lines, true, split[0]);
                }
            }
        }
        return null;
    }

    /**
     * Smallest type size a grown cell may end up at: a share of the source size, never under a
     * floor. Growing is how a cell that would keep its source text gets a layout; a layout that
     * small is not worth the pixels it takes, and the source text stays.
     */
    private static int growthTarget(int sourceFontSize) {
        return Math.max(GROWTH_TARGET_MINIMUM, (int) Math.ceil(sourceFontSize * GROWTH_TARGET_SHARE));
    }

    /**
     * A one-line cell is about as tall as the source glyphs, but a line box (ascent plus descent) is
     * taller than a glyph, and for a CJK face by about 45% of the size. The smallest permitted type
     * then needs more height than the cell has, whatever the width, and the label keeps its source
     * text next to translated neighbours. When the space under the cell is empty the cell may take
     * it, so this lays the text out in the cell grown by the fewest rows that reach the best size
     * the free rows allow. Only a cell that does not fit as it is grows: one that fits is left at
     * its own bounds, however small its type. Null when there is nothing to gain: no free row, or
     * no layout at the target size.
     *
     * @param freeRows rows under the cell that may be taken
     */
    private static Grown growDownward(
            Graphics2D graphics,
            String text,
            Bounds bounds,
            Font baseFont,
            int sourceFontSize,
            boolean compactLabel,
            int freeRows) {
        if (freeRows <= 0) return null;
        Layout best = fitHorizontal(graphics, text, bounds.taller(freeRows), baseFont,
                sourceFontSize, compactLabel);
        int size = best.font().getSize();
        if (!best.fits() || size < growthTarget(sourceFontSize)) return null;
        // More height never lowers the size a layout reaches, so the fewest rows that still reach it
        // are found by halving; every probe is checked, so the result reaches the size either way.
        int low = 1;
        int high = freeRows;
        Layout result = best;
        while (low < high) {
            int middle = (low + high) / 2;
            Layout candidate = fitHorizontal(graphics, text, bounds.taller(middle), baseFont,
                    sourceFontSize, compactLabel);
            if (candidate.fits() && candidate.font().getSize() >= size) {
                high = middle;
                result = candidate;
            } else {
                low = middle + 1;
            }
        }
        return new Grown(high, result);
    }

    /**
     * Rows directly under a cell that nothing else lives in, at most {@code limit}. A row is free
     * when it lies inside the image, touches the mask of no other region the plan modifies, and is
     * background (the estimated cell background) across at least FREE_ROW_BACKGROUND_SHARE of its
     * width; the pixels read are the source's, so a region the plan leaves alone (a label kept for
     * its fit, an icon, a rule) ends the free space just as an approved one does. Rows follow the
     * cell's own frame, so they sit under it however the cell is turned.
     */
    private static int freeRowsBelow(
            BufferedImage source,
            Color background,
            OcrPoint origin,
            double angle,
            int frameWidth,
            int frameHeight,
            int limit,
            List<java.awt.Rectangle> others) {
        AffineTransform frame = AffineTransform.getTranslateInstance(origin.x(), origin.y());
        frame.rotate(angle);
        java.awt.Rectangle reach = frame.createTransformedShape(
                new java.awt.Rectangle(0, frameHeight, frameWidth, Math.max(1, limit))).getBounds();
        reach.grow(1, 1);
        List<java.awt.Rectangle> near = others.stream().filter(reach::intersects).toList();
        int tolerance = ImageTranslationStyleEstimator.INK_DISTANCE_SQUARED;
        double[] point = new double[2];
        for (int row = 0; row < limit; row++) {
            int matching = 0;
            for (int column = 0; column < frameWidth; column++) {
                point[0] = column + .5;
                point[1] = frameHeight + row + .5;
                frame.transform(point, 0, point, 0, 1);
                int x = (int) Math.floor(point[0]);
                int y = (int) Math.floor(point[1]);
                if (x < 0 || y < 0 || x >= source.getWidth() || y >= source.getHeight()) return row;
                for (java.awt.Rectangle other : near) {
                    if (other.contains(x, y)) return row;
                }
                if (colourDistanceSquared(new Color(source.getRGB(x, y), true), background) < tolerance) {
                    matching++;
                }
            }
            if (matching < frameWidth * FREE_ROW_BACKGROUND_SHARE) return row;
        }
        return Math.max(0, limit);
    }

    /**
     * The most rows, at most {@code freeRows}, a cell may grow without the enlarged region passing
     * the plan's single-region limit (as the policy measures it, on the region polygon) or the part
     * of the plan's total limit that is left (on the mask, so what the cell's own padding already
     * covers is not charged twice). Cost only rises with rows, so the answer is found by halving.
     */
    private static int affordableRows(
            OcrRegion region,
            Area layoutMask,
            Area modifiedMask,
            OcrPoint origin,
            double angle,
            int frameWidth,
            int frameHeight,
            int freeRows,
            int imageWidth,
            int imageHeight,
            double maxRegionRatio,
            double remainingRatio) {
        double imageArea = (double) imageWidth * imageHeight;
        int low = 0;
        int high = Math.max(0, freeRows);
        while (low < high) {
            int middle = (low + high + 1) / 2;
            Area band = band(origin, angle, frameWidth, frameHeight, middle, imageWidth, imageHeight);
            boolean withinTotal = OverlaySafetyPolicy.area(subtract(band, modifiedMask)) / imageArea
                    <= remainingRatio;
            boolean withinRegion = (region.area() + OverlaySafetyPolicy.area(subtract(band, layoutMask)))
                    / imageArea <= maxRegionRatio;
            if (withinTotal && withinRegion) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    private static Area subtract(Area shape, Area removed) {
        Area result = new Area(shape);
        result.subtract(removed);
        return result;
    }

    private static String ratio(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    /** The rows a cell grew into, in image coordinates, with a row of overlap with the cell. */
    private static Area band(
            OcrPoint origin, double angle, int frameWidth, int frameHeight, int rows,
            int imageWidth, int imageHeight) {
        AffineTransform frame = AffineTransform.getTranslateInstance(origin.x(), origin.y());
        frame.rotate(angle);
        Area band = new Area(frame.createTransformedShape(
                new java.awt.Rectangle(0, frameHeight - 1, frameWidth, rows + 1)));
        band.intersect(new Area(new java.awt.Rectangle(0, 0, imageWidth, imageHeight)));
        return band;
    }

    private static Area union(Area mask, Area addition) {
        Area result = new Area(mask);
        result.add(addition);
        return result;
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

    /** Whether a replacement is Chinese, Japanese or Korean text, which reads upright down a column. */
    private static boolean isMostlyCjk(String value) {
        return WritingModeClassifier.isMostlyCjk(List.of(value));
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
        return wrap(text, metrics, width, new boolean[1]);
    }

    /**
     * {@link #wrap(String, FontMetrics, int)} that also says whether it had to break a word between
     * its characters: {@code splitWord[0]} is set when a word of more than one character was wider
     * than a line on its own. A single glyph wider than the line, and the glyph-by-glyph breaks of
     * Han and kana, which are never a word, do not set it.
     */
    static List<String> wrap(String text, FontMetrics metrics, int width, boolean[] splitWord) {
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
                if (token.codePointCount(0, token.length()) > 1) splitWord[0] = true;
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

    static byte[] writePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG writer is unavailable");
        }
        return output.toByteArray();
    }

    static byte[] writeJpeg(BufferedImage image, float quality) throws IOException {
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

    /** @param splitsWords a word had to be broken between its characters to make the lines fit */
    private record Layout(Font font, List<String> lines, boolean fits, boolean splitsWords) {
        Layout(Font font, List<String> lines, boolean fits) {
            this(font, lines, fits, false);
        }
    }

    /** A cell that took {@code rows} rows of the empty space under it, and the layout that gave. */
    private record Grown(int rows, Layout layout) {
    }

    /** The rendered raster and the bookkeeping of one draw, before it is encoded. */
    private record Drawn(
            BufferedImage output, int rendered, int skipped, List<OverlayRenderDecision> decisions) {

        Drawn withOutput(BufferedImage replacement) {
            return new Drawn(replacement, rendered, skipped, decisions);
        }
    }

    /** Which engine laid a cell out: horizontal lines, or the columns of vertical writing. */
    private enum LayoutKind {
        LINES, COLUMNS
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
            Layout layout,
            Growth growth,
            LayoutKind kind,
            List<VerticalTextLayout.Cell> cells,
            VerticalTextLayout.Layout columns) {
        PreparedOverlay {
            cleanupAreas = List.copyOf(cleanupAreas);
            cells = List.copyOf(cells);
        }

        static PreparedOverlay lines(ImageRegionOverlay overlay, ImageTranslationTextStyle style,
                Area layoutMask, Area modifiedMask, List<Area> cleanupAreas, OcrPoint origin,
                double angle, Bounds localBounds, Layout layout, Growth growth) {
            return new PreparedOverlay(overlay, style, layoutMask, modifiedMask, cleanupAreas, origin,
                    angle, localBounds, layout, growth, LayoutKind.LINES, List.of(), null);
        }

        /** Vertical writing never grows: the cells are the region's own. */
        static PreparedOverlay columns(ImageRegionOverlay overlay, ImageTranslationTextStyle style,
                Area layoutMask, Area modifiedMask, List<Area> cleanupAreas, OcrPoint origin,
                double angle, Bounds localBounds, List<VerticalTextLayout.Cell> cells,
                VerticalTextLayout.Layout columns) {
            return new PreparedOverlay(overlay, style, layoutMask, modifiedMask, cleanupAreas, origin,
                    angle, localBounds, null, null, LayoutKind.COLUMNS, cells, columns);
        }

        /** The type size the cell was laid out at, whichever engine laid it out. */
        int fontSize() {
            return kind == LayoutKind.COLUMNS ? columns.size() : layout.font().getSize();
        }

        PreparedOverlay withLayout(Layout replacement) {
            return new PreparedOverlay(overlay, style, layoutMask, modifiedMask, cleanupAreas,
                    origin, angle, localBounds, replacement, growth, kind, cells, columns);
        }

        PreparedOverlay withColumns(VerticalTextLayout.Layout replacement) {
            return new PreparedOverlay(overlay, style, layoutMask, modifiedMask, cleanupAreas,
                    origin, angle, localBounds, layout, growth, kind, cells, replacement);
        }

        /**
         * The same cell holding only {@code rows} of the rows it grew into, none when the layout
         * now fits without them. Never more than it grew, so the masks only shrink.
         */
        PreparedOverlay trimmedTo(int rows) {
            if (growth == null || rows >= growth.rows()) return this;
            if (rows <= 0) {
                return new PreparedOverlay(overlay, style, growth.baseLayoutMask(),
                        growth.baseModifiedMask(), cleanupAreas, origin, angle,
                        growth.baseBounds(), layout, null, kind, cells, columns);
            }
            Area band = band(origin, angle, growth.frameWidth(), growth.frameHeight(), rows,
                    growth.imageWidth(), growth.imageHeight());
            return new PreparedOverlay(overlay, style, union(growth.baseLayoutMask(), band),
                    union(growth.baseModifiedMask(), band), cleanupAreas, origin, angle,
                    growth.baseBounds().taller(rows), layout,
                    new Growth(rows, growth.baseLayoutMask(), growth.baseModifiedMask(),
                            growth.baseBounds(), growth.frameWidth(), growth.frameHeight(),
                            growth.imageWidth(), growth.imageHeight()),
                    kind, cells, columns);
        }
    }

    /** What a cell had before it grew, and the frame it grew in, so the growth can be given back. */
    private record Growth(
            int rows,
            Area baseLayoutMask,
            Area baseModifiedMask,
            Bounds baseBounds,
            int frameWidth,
            int frameHeight,
            int imageWidth,
            int imageHeight) {
    }

    private record Bounds(int x, int y, int width, int height) {
        Bounds taller(int rows) {
            return new Bounds(x, y, width, height + rows);
        }

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
