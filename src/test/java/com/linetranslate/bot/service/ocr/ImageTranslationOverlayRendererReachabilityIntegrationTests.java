package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.linetranslate.bot.testing.Reachability;

/**
 * The renderer lets go of its source image before it encodes, so the encoder runs with one raster
 * alive instead of two.
 *
 * <p>This is a failsafe-stage class (the {@code IntegrationTests} suffix) on purpose: it measures
 * what the collector can reclaim, which only holds in a JVM where no Mockito inline mock has
 * rewritten {@link ImageTranslationOverlayRenderer}. The inline mock maker copies a method's
 * arguments into a hidden local for as long as the call runs, which keeps the source image alive
 * whatever the renderer does, and the unit-test JVM does mock the renderer
 * ({@code ImageTranslationPipelineUnexpectedFailureTests}). Keep any mock of the renderer out of
 * this class's JVM.
 */
class ImageTranslationOverlayRendererReachabilityIntegrationTests {

    @Test
    void sourceRasterIsCollectableWhenTheEncoderStarts() {
        AtomicReference<ValidatedImage> handedOver = new AtomicReference<>();
        WeakReference<BufferedImage> source = hold(handedOver);
        AtomicBoolean sourceCollected = new AtomicBoolean();
        ImageTranslationOverlayRenderer renderer = observing(source, sourceCollected);

        RenderedImage rendered = renderer.render(
                handedOver.getAndSet(null), new OverlaySafetyPlan(true, "safe", List.of(), 0));

        assertThat(sourceCollected).as("source raster collected before encoding").isTrue();
        assertThat(rendered.bytes()).isNotEmpty();
    }

    @Test
    void sourceRasterStaysReachableWhileTheCallerStillHoldsIt() {
        AtomicReference<ValidatedImage> held = new AtomicReference<>();
        WeakReference<BufferedImage> source = hold(held);
        AtomicBoolean sourceCollected = new AtomicBoolean(true);
        ImageTranslationOverlayRenderer renderer = observing(source, sourceCollected);

        renderer.render(held.get(), new OverlaySafetyPlan(true, "safe", List.of(), 0));

        // The control for the test above: the check can fail, so passing means something.
        assertThat(sourceCollected).isFalse();
        assertThat(held.get()).isNotNull();
    }

    @Test
    void onATurnedPageNeitherUprightRasterIsReachableWhenTheEncoderStarts() {
        AtomicReference<ValidatedImage> handedOver = new AtomicReference<>();
        WeakReference<BufferedImage> source = hold(handedOver);
        AtomicReference<WeakReference<BufferedImage>> uprightOutput = new AtomicReference<>();
        AtomicBoolean sourceCollected = new AtomicBoolean();
        AtomicBoolean uprightOutputCollected = new AtomicBoolean();
        AtomicInteger encodedWidth = new AtomicInteger();
        AtomicInteger encodedHeight = new AtomicInteger();
        ImageTranslationOverlayRenderer renderer =
                new ImageTranslationOverlayRenderer(new ImageTranslationFontProvider(), 10_485_760) {
                    @Override
                    BufferedImage restore(BufferedImage upright, int quarterTurns) {
                        uprightOutput.set(new WeakReference<>(upright));
                        return super.restore(upright, quarterTurns);
                    }

                    @Override
                    RenderedImage.Encoded encode(BufferedImage image) {
                        sourceCollected.set(Reachability.collected(source));
                        uprightOutputCollected.set(Reachability.collected(uprightOutput.get()));
                        encodedWidth.set(image.getWidth());
                        encodedHeight.set(image.getHeight());
                        return super.encode(image);
                    }
                };

        RenderedImage rendered = renderer.render(
                handedOver.getAndSet(null), new OverlaySafetyPlan(true, "safe", List.of(), 0), 1);

        assertThat(sourceCollected).as("upright source collected before encoding").isTrue();
        assertThat(uprightOutputCollected).as("upright output collected before encoding").isTrue();
        // The encoder is given the turned-back raster: the upright page is 200 x 100.
        assertThat(encodedWidth.get()).isEqualTo(100);
        assertThat(encodedHeight.get()).isEqualTo(200);
        assertThat(rendered.raster().getWidth()).isEqualTo(100);
    }

    @Test
    void onATurnedPageTheUprightSourceStaysReachableWhileTheCallerStillHoldsIt() {
        AtomicReference<ValidatedImage> held = new AtomicReference<>();
        WeakReference<BufferedImage> source = hold(held);
        AtomicBoolean sourceCollected = new AtomicBoolean(true);
        ImageTranslationOverlayRenderer renderer = observing(source, sourceCollected);

        renderer.render(held.get(), new OverlaySafetyPlan(true, "safe", List.of(), 0), 1);

        // The control for the test above: the check can fail, so passing means something.
        assertThat(sourceCollected).isFalse();
        assertThat(held.get()).isNotNull();
    }

    /** A renderer that records, as its encoder starts, whether the source raster is collectable. */
    private static ImageTranslationOverlayRenderer observing(
            WeakReference<BufferedImage> source, AtomicBoolean collected) {
        return new ImageTranslationOverlayRenderer(new ImageTranslationFontProvider(), 10_485_760) {
            @Override
            RenderedImage.Encoded encode(BufferedImage image) {
                collected.set(Reachability.collected(source));
                return super.encode(image);
            }
        };
    }

    /** Builds the image where no local of the test can keep it alive, and returns a weak view. */
    private static WeakReference<BufferedImage> hold(AtomicReference<ValidatedImage> holder) {
        BufferedImage raster = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7);
        for (int y = 0; y < raster.getHeight(); y++) {
            for (int x = 0; x < raster.getWidth(); x++) {
                raster.setRGB(x, y, random.nextInt());
            }
        }
        holder.set(new ValidatedImage(new byte[] {1}, "image/png", raster));
        return new WeakReference<>(raster);
    }
}
