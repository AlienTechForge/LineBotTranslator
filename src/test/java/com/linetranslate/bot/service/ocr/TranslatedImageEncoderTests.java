package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.linetranslate.bot.testing.ImageTranslatorFixtures;

class TranslatedImageEncoderTests {

    private static final long TEN_MB = 10L * 1024 * 1024;

    @Test
    void aPngUnderTheDeliveryTargetIsKeptByteForByte() {
        byte[] png = ImageTranslatorFixtures.png(64, 64);

        Optional<TranslatedImageEncoder.Encoded> fitted = TranslatedImageEncoder.fit(png, 2_097_152, TEN_MB);

        assertThat(fitted).isPresent();
        assertThat(fitted.get().contentType()).isEqualTo("image/png");
        assertThat(fitted.get().bytes()).isSameAs(png);
    }

    @Test
    void anOpaquePngAboveTheTargetIsDeliveredAsJpegWhenThatGetsItUnderTheTarget() throws Exception {
        byte[] png = ImageTranslatorFixtures.gradientPng(600, 600);
        long target = png.length / 3;

        TranslatedImageEncoder.Encoded fitted = TranslatedImageEncoder.fit(png, target, TEN_MB).orElseThrow();

        assertThat(fitted.contentType()).isEqualTo("image/jpeg");
        assertThat(fitted.bytes().length).isLessThanOrEqualTo((int) target);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(fitted.bytes()));
        assertThat(decoded.getWidth()).isEqualTo(600);
        assertThat(decoded.getHeight()).isEqualTo(600);
    }

    @Test
    void aPngAboveTheTargetThatNoJpegBringsUnderItIsKeptWhileItFitsTheCap() {
        byte[] png = ImageTranslatorFixtures.noisyPng(300, 300);

        TranslatedImageEncoder.Encoded fitted = TranslatedImageEncoder.fit(png, 1_000, TEN_MB).orElseThrow();

        assertThat(fitted.contentType()).isEqualTo("image/png");
        assertThat(fitted.bytes()).isSameAs(png);
    }

    @Test
    void aTransparentPngAboveTheTargetStaysPngBecauseJpegCannotCarryAlpha() {
        byte[] png = ImageTranslatorFixtures.transparentPng(200, 200);

        TranslatedImageEncoder.Encoded fitted = TranslatedImageEncoder.fit(png, 1_000, TEN_MB).orElseThrow();

        assertThat(fitted.contentType()).isEqualTo("image/png");
        assertThat(fitted.bytes()).isSameAs(png);
    }

    @Test
    void aTransparentPngAboveTheCapCannotBeDelivered() {
        byte[] png = ImageTranslatorFixtures.transparentPng(200, 200);

        assertThat(TranslatedImageEncoder.fit(png, 1_000, png.length - 1L)).isEmpty();
    }

    @Test
    void anOpaquePngAboveTheCapFallsBackToJpegThatFitsTheCap() {
        byte[] png = ImageTranslatorFixtures.noisyPng(400, 400);
        long cap = png.length - 1L;

        TranslatedImageEncoder.Encoded fitted = TranslatedImageEncoder.fit(png, 1_000, cap).orElseThrow();

        assertThat(fitted.contentType()).isEqualTo("image/jpeg");
        assertThat(fitted.bytes().length).isLessThanOrEqualTo((int) cap);
    }

    @Test
    void anOpaquePngThatNoEncodingBringsUnderTheCapCannotBeDelivered() {
        byte[] png = ImageTranslatorFixtures.noisyPng(200, 200);

        assertThat(TranslatedImageEncoder.fit(png, 500, 1_000)).isEmpty();
    }

    @Test
    void bytesThatAreNotAnImageAreKeptWhileTheyFitTheCapAndRefusedBeyondIt() {
        byte[] garbage = new byte[5_000];
        java.util.Arrays.fill(garbage, (byte) 7);

        assertThat(TranslatedImageEncoder.fit(garbage, 1_000, 10_000)).isPresent()
                .get().extracting(TranslatedImageEncoder.Encoded::contentType).isEqualTo("image/png");
        assertThat(TranslatedImageEncoder.fit(garbage, 1_000, 4_000)).isEmpty();
    }
}
