package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * LINE fetches the original itself, within its own timeout, so an opaque image whose lossless PNG
 * is larger than the delivery target is sent as JPEG when that fits. These tests use noise, the
 * worst case for PNG, so that the PNG is far above the target while the JPEG is well under it.
 */
class RenderedImageDeliveryTargetTests {

    private static final long TWO_MIB = 2_097_152L;
    private static final long TEN_MIB = 10_485_760L;
    private static final int SIDE = 1_000;

    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(ImageTranslationOverlayRenderer.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void opaquePngAboveTheTargetIsDeliveredAsJpegWithoutARaster() throws Exception {
        BufferedImage source = noise(SIDE, SIDE, BufferedImage.TYPE_INT_RGB);
        byte[] png = png(source);
        assertThat(png.length).as("the noise PNG is the case this guards").isGreaterThan((int) TWO_MIB);

        RenderedImage rendered = renderer(TEN_MIB, TWO_MIB).render(validated(source), null);

        assertThat(rendered.contentType()).isEqualTo("image/jpeg");
        assertThat(rendered.raster()).as("the bytes are lossy, so there is no raster they encode").isNull();
        assertThat(rendered.bytes().length).isLessThanOrEqualTo((int) TWO_MIB);
        assertThat(rendered.bytes()).isEqualTo(jpeg(source, .92f));
        assertThat(rendered.bytes()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(rendered.bytes()[1] & 0xFF).isEqualTo(0xD8);
        BufferedImage decoded = decode(rendered.bytes());
        assertThat(decoded.getWidth()).isEqualTo(SIDE);
        assertThat(decoded.getHeight()).isEqualTo(SIDE);
    }

    @Test
    void defaultRendererUsesTheTwoMibTarget() {
        BufferedImage source = noise(SIDE, SIDE, BufferedImage.TYPE_INT_RGB);

        RenderedImage rendered = new ImageTranslationOverlayRenderer().render(validated(source), null);

        assertThat(rendered.contentType()).isEqualTo("image/jpeg");
        assertThat(rendered.raster()).isNull();
        assertThat(rendered.bytes().length).isLessThanOrEqualTo((int) TWO_MIB);
    }

    @Test
    void lowerQualityIsUsedOnlyWhenTheFirstDoesNotFitTheTarget() throws Exception {
        BufferedImage source = noise(SIDE, SIDE, BufferedImage.TYPE_INT_RGB);
        byte[] high = jpeg(source, .92f);
        byte[] lower = jpeg(source, .85f);
        assertThat(lower.length).isLessThan(high.length);
        long target = (high.length + lower.length) / 2;

        RenderedImage.Encoded encoded = renderer(TEN_MIB, target).encode(source);

        assertThat(encoded.contentType()).isEqualTo("image/jpeg");
        assertThat(encoded.bytes()).isEqualTo(lower);
        assertThat(encoded.raster()).isNull();
    }

    @Test
    void pngIsKeptUnderTheCapWhenNoJpegFitsTheTarget() throws Exception {
        BufferedImage source = noise(SIDE, SIDE, BufferedImage.TYPE_INT_RGB);
        long target = jpeg(source, .85f).length - 1;

        RenderedImage.Encoded encoded = renderer(TEN_MIB, target).encode(source);

        assertThat(encoded.contentType()).isEqualTo("image/png");
        assertThat(encoded.bytes()).isEqualTo(png(source));
        assertThat(encoded.raster()).as("lossless PNG still carries its raster").isSameAs(source);
    }

    @Test
    void existingCapFallbackStillApplies() throws Exception {
        BufferedImage source = noise(400, 400, BufferedImage.TYPE_INT_RGB);
        byte[] png = png(source);
        long cap = png.length - 1L;
        long target = jpeg(source, .85f).length - 1L;

        RenderedImage.Encoded encoded = renderer(cap, target).encode(source);

        assertThat(encoded.contentType()).isEqualTo("image/jpeg");
        assertThat(encoded.bytes()).isEqualTo(jpeg(source, .92f));
        assertThat(encoded.bytes().length).isLessThanOrEqualTo((int) cap);
        assertThat(encoded.raster()).isNull();
    }

    @Test
    void transparentOutputAboveTheTargetStaysPng() throws Exception {
        BufferedImage source = noise(SIDE, SIDE, BufferedImage.TYPE_INT_ARGB);
        assertThat(png(source).length).isGreaterThan((int) TWO_MIB);

        RenderedImage rendered = renderer(TEN_MIB, TWO_MIB).render(validated(source), null);

        assertThat(rendered.contentType()).isEqualTo("image/png");
        assertThat(rendered.bytes().length).isGreaterThan((int) TWO_MIB);
        assertThat(rendered.raster()).isNotNull();
        assertThat(rendered.raster().getColorModel().hasAlpha()).isTrue();
        assertThat(rendered.bytes()).isEqualTo(png(rendered.raster()));
    }

    @Test
    void smallPngIsUntouched() throws Exception {
        BufferedImage source = noise(64, 64, BufferedImage.TYPE_INT_RGB);

        RenderedImage.Encoded encoded = renderer(TEN_MIB, TWO_MIB).encode(source);

        assertThat(encoded.contentType()).isEqualTo("image/png");
        assertThat(encoded.bytes()).isEqualTo(png(source));
        assertThat(encoded.raster()).isSameAs(source);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void aTargetAtOrAboveTheCapSwitchesTheStepOff() throws Exception {
        BufferedImage source = noise(SIDE, SIDE, BufferedImage.TYPE_INT_RGB);
        byte[] png = png(source);

        RenderedImage.Encoded abovePng = renderer(TEN_MIB, 20 * TEN_MIB).encode(source);
        RenderedImage.Encoded overCap = renderer(png.length - 1L, Long.MAX_VALUE).encode(source);

        assertThat(abovePng.contentType()).isEqualTo("image/png");
        assertThat(abovePng.raster()).isSameAs(source);
        assertThat(overCap.contentType()).as("the cap still falls back to JPEG").isEqualTo("image/jpeg");
        assertThat(overCap.bytes()).isEqualTo(jpeg(source, .92f));
        assertThat(overCap.raster()).isNull();
    }

    @Test
    void rejectsNonPositiveTarget() {
        assertThatThrownBy(() -> renderer(TEN_MIB, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> renderer(TEN_MIB, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theChoiceIsLoggedWithSizesAndFormatOnly() throws Exception {
        BufferedImage source = noise(SIDE, SIDE, BufferedImage.TYPE_INT_RGB);
        long target = jpeg(source, .85f).length - 1L;

        renderer(TEN_MIB, TWO_MIB).encode(source);
        renderer(TEN_MIB, target).encode(source);
        renderer(TEN_MIB, TWO_MIB).encode(noise(SIDE, SIDE, BufferedImage.TYPE_INT_ARGB));

        List<String> lines = logs.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0))
                .startsWith("Translated image re-encoded to fit the delivery target")
                .contains("width=1000", "height=1000", "pngBytes=", "outputBytes=",
                        "format=image/jpeg", "quality=0.92", "deliveryTargetBytes=" + TWO_MIB);
        assertThat(lines.get(1))
                .startsWith("Translated image kept as PNG above the delivery target")
                .contains("opaque=true", "lowestQualityJpegBytes=" + (target + 1),
                        "deliveryTargetBytes=" + target);
        assertThat(lines.get(2))
                .startsWith("Translated image kept as PNG above the delivery target")
                .contains("opaque=false", "lowestQualityJpegBytes=none");
    }

    private static ImageTranslationOverlayRenderer renderer(long maxOutputBytes, long deliveryTargetBytes) {
        return new ImageTranslationOverlayRenderer(
                new ImageTranslationFontProvider(), maxOutputBytes, deliveryTargetBytes);
    }

    private static ValidatedImage validated(BufferedImage image) {
        return new ValidatedImage(new byte[] {1}, "image/png", image);
    }

    private static BufferedImage decode(byte[] bytes) throws Exception {
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(decoded).isNotNull();
        return decoded;
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", output)).isTrue();
        return output.toByteArray();
    }

    /** The renderer's JPEG settings, so a test can name the exact bytes a quality must produce. */
    private static byte[] jpeg(BufferedImage image, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(output)) {
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

    private static BufferedImage noise(int width, int height, int type) {
        Random random = new Random(7);
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        return image;
    }
}
