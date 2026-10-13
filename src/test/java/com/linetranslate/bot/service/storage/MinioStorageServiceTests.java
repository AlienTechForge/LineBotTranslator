package com.linetranslate.bot.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.linetranslate.bot.service.imageproxy.ImageProxyLinkService;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;

class MinioStorageServiceTests {

    private static final String BUCKET = "line-bot";
    /** 2023-11-14T22:13:20Z; the 24 hour retention folder is therefore 1700086400. */
    private static final long NOW_MS = 1_700_000_000_000L;
    private static final String RETENTION_FOLDER = "translated-images/1700086400/";

    private MinioClient client;
    private MinioClient publicSigner;
    private AtomicBoolean previewNeeded;
    private MinioStorageService storage;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(MinioClient.class);
        publicSigner = mock(MinioClient.class);
        when(client.bucketExists(any())).thenReturn(true);
        when(publicSigner.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenAnswer(invocation -> {
            GetPresignedObjectUrlArgs args = invocation.getArgument(0);
            return "https://s3.example.com/" + args.bucket() + "/" + args.object()
                    + "?X-Amz-Expires=" + args.expiry() + "&X-Amz-Signature=" + "a".repeat(64);
        });
        previewNeeded = new AtomicBoolean(true);
        storage = new MinioStorageService(
                client, publicSigner, BUCKET, true, 1_000, () -> NOW_MS, previewNeeded::get);
    }

    @Test
    void storesAnOriginalAboveLinesPreviewLimitWithASeparateBoundedJpegPreviewBesideIt() throws Exception {
        byte[] original = noisyPng(900, 700);
        assertThat(original.length).as("fixture must exceed LINE's 1 MB preview limit").isGreaterThan(1_048_576);

        ImageStorageResult result = storage.uploadTranslatedImage(original, "image/png");

        assertThat(result.stored()).isTrue();
        assertThat(result.url()).isPresent();
        assertThat(result.previewUrl()).isPresent();
        assertThat(result.previewUrl().get()).isNotEqualTo(result.url().get());

        List<PutObjectArgs> puts = puts();
        assertThat(puts).hasSize(2);
        PutObjectArgs storedOriginal = puts.get(0);
        PutObjectArgs storedPreview = puts.get(1);
        assertThat(storedOriginal.object()).startsWith(RETENTION_FOLDER).endsWith(".png");
        String stem = storedOriginal.object().substring(0, storedOriginal.object().length() - ".png".length());
        assertThat(storedPreview.object()).isEqualTo(stem + "-preview.jpg");
        assertThat(storedPreview.contentType().toString()).isEqualTo("image/jpeg");
        assertThat(storedOriginal.contentType().toString()).isEqualTo("image/png");

        byte[] previewBytes = storedPreview.stream().readAllBytes();
        assertThat(storedPreview.objectSize()).isEqualTo(previewBytes.length);
        assertThat(previewBytes.length).isLessThan(1_048_576).isLessThan(original.length);
        BufferedImage preview = ImageIO.read(new ByteArrayInputStream(previewBytes));
        assertThat(Math.max(preview.getWidth(), preview.getHeight())).isEqualTo(240);
        // Aspect ratio is kept: 900 x 700 scales to 240 x 187 (or 186).
        assertThat((double) preview.getWidth() / preview.getHeight()).isBetween(1.27, 1.30);
        assertThat(previewBytes).startsWith((byte) 0xFF, (byte) 0xD8);
    }

    @Test
    void bothUrlsKeepTheTranslatedImageSigningLifetime() throws Exception {
        storage.uploadTranslatedImage(noisyPng(300, 300), "image/png");

        ArgumentCaptor<GetPresignedObjectUrlArgs> signed = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(publicSigner, times(2)).getPresignedObjectUrl(signed.capture());
        assertThat(signed.getAllValues()).extracting(GetPresignedObjectUrlArgs::expiry)
                .containsOnly(3_600);
        assertThat(signed.getAllValues()).extracting(GetPresignedObjectUrlArgs::object)
                .anyMatch(name -> name.endsWith(".png"))
                .anyMatch(name -> name.endsWith("-preview.jpg"));
    }

    @Test
    void jpegOriginalGetsAPreviewNamedFromItsOwnStem() throws Exception {
        ImageStorageResult result = storage.uploadTranslatedImage(jpeg(600, 300), "image/jpeg");

        assertThat(result.previewUrl()).isPresent();
        List<PutObjectArgs> puts = puts();
        assertThat(puts).hasSize(2);
        assertThat(puts.get(0).object()).endsWith(".jpg").doesNotEndWith("-preview.jpg");
        assertThat(puts.get(1).object()).endsWith("-preview.jpg");
        assertThat(puts.get(1).object()).isNotEqualTo(puts.get(0).object());
    }

    @Test
    void anImageSmallerThanThePreviewBoxIsNotUpscaled() throws Exception {
        storage.uploadTranslatedImage(png(120, 80, Color.BLUE), "image/png");

        BufferedImage preview = ImageIO.read(new ByteArrayInputStream(puts().get(1).stream().readAllBytes()));
        assertThat(preview.getWidth()).isEqualTo(120);
        assertThat(preview.getHeight()).isEqualTo(80);
    }

    @Test
    void transparencyIsFlattenedOntoWhiteBecauseJpegHasNoAlpha() throws Exception {
        BufferedImage transparent = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);

        storage.uploadTranslatedImage(encode(transparent, "png"), "image/png");

        BufferedImage preview = ImageIO.read(new ByteArrayInputStream(puts().get(1).stream().readAllBytes()));
        Color corner = new Color(preview.getRGB(5, 5));
        assertThat(corner.getRed()).isGreaterThan(240);
        assertThat(corner.getGreen()).isGreaterThan(240);
        assertThat(corner.getBlue()).isGreaterThan(240);
    }

    @Test
    void noPreviewIsStoredWhenTheImageProxyAlreadyServesOne() throws Exception {
        previewNeeded.set(false);

        ImageStorageResult result = storage.uploadTranslatedImage(noisyPng(300, 300), "image/png");

        assertThat(result.stored()).isTrue();
        assertThat(result.url()).isPresent();
        assertThat(result.previewUrl()).isEmpty();
        assertThat(puts()).hasSize(1);
        verify(publicSigner, times(1)).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    @Test
    void springWiringStoresAPreviewOnlyWhenTheImageProxyIsNotEnabled() throws Exception {
        ImageProxyLinkService proxy = mock(ImageProxyLinkService.class);
        ObjectProvider<ImageProxyLinkService> proxyProvider = providerOf(proxy);

        when(proxy.isEnabled()).thenReturn(true);
        assertThat(wired(proxyProvider).uploadTranslatedImage(noisyPng(300, 300), "image/png").previewUrl())
                .isEmpty();
        verify(client, times(1)).putObject(any(PutObjectArgs.class));

        when(proxy.isEnabled()).thenReturn(false);
        assertThat(wired(proxyProvider).uploadTranslatedImage(noisyPng(300, 300), "image/png").previewUrl())
                .isPresent();
        verify(client, times(3)).putObject(any(PutObjectArgs.class));

        // No proxy bean at all behaves like a disabled proxy.
        assertThat(wired(providerOf(null)).uploadTranslatedImage(noisyPng(300, 300), "image/png").previewUrl())
                .isPresent();
    }

    @Test
    void anUndecodableOutputStillStoresTheOriginalWithoutAPreview() throws Exception {
        ImageStorageResult result = storage.uploadTranslatedImage(new byte[] {1, 2, 3}, "image/png");

        assertThat(result.stored()).isTrue();
        assertThat(result.url()).isPresent();
        assertThat(result.previewUrl()).isEmpty();
        assertThat(puts()).hasSize(1);
    }

    @Test
    void aSuppliedRasterBuildsThePreviewWithoutDecodingTheStoredBytes() throws Exception {
        // The stored bytes are not an image at all, so a preview can only have come from the raster.
        byte[] undecodable = {1, 2, 3};

        ImageStorageResult result = storage.uploadTranslatedImage(
                undecodable, "image/png", noisyRaster(900, 700));

        assertThat(result.stored()).isTrue();
        assertThat(result.previewUrl()).isPresent();
        List<PutObjectArgs> puts = puts();
        assertThat(puts).hasSize(2);
        assertThat(puts.get(0).stream().readAllBytes()).isEqualTo(undecodable);
        assertThat(puts.get(1).object()).endsWith("-preview.jpg");
        BufferedImage preview = ImageIO.read(new ByteArrayInputStream(puts.get(1).stream().readAllBytes()));
        assertThat(Math.max(preview.getWidth(), preview.getHeight())).isEqualTo(240);
    }

    @Test
    void aSuppliedRasterGivesTheSamePreviewAsDecodingItsLosslessBytes() throws Exception {
        BufferedImage raster = noisyRaster(900, 700);

        storage.uploadTranslatedImage(encode(raster, "png"), "image/png", raster);
        storage.uploadTranslatedImage(encode(raster, "png"), "image/png");

        List<PutObjectArgs> puts = puts();
        assertThat(puts).hasSize(4);
        assertThat(puts.get(1).stream().readAllBytes()).isEqualTo(puts.get(3).stream().readAllBytes());
    }

    @Test
    void aSuppliedRasterIsNotUsedWhenTheImageProxyAlreadyServesAPreview() throws Exception {
        previewNeeded.set(false);

        ImageStorageResult result = storage.uploadTranslatedImage(
                noisyPng(300, 300), "image/png", noisyRaster(300, 300));

        assertThat(result.stored()).isTrue();
        assertThat(result.previewUrl()).isEmpty();
        assertThat(puts()).hasSize(1);
    }

    @Test
    void aFailedPreviewUploadKeepsTheStoredOriginalAndDoesNotTripTheCircuit() throws Exception {
        doThrow(new IllegalStateException("backend hiccup")).when(client).putObject(
                org.mockito.ArgumentMatchers.argThat((PutObjectArgs args) ->
                        args != null && args.object().endsWith("-preview.jpg")));

        ImageStorageResult result = storage.uploadTranslatedImage(noisyPng(300, 300), "image/png");

        assertThat(result.stored()).isTrue();
        assertThat(result.url()).isPresent();
        assertThat(result.previewUrl()).isEmpty();
        // The circuit stayed closed: the next upload is attempted instead of skipped.
        assertThat(storage.uploadTranslatedImage(noisyPng(300, 300), "image/png").stored()).isTrue();
        verify(client, times(4)).putObject(any(PutObjectArgs.class));
    }

    @Test
    void aFailedPreviewSigningStillReturnsTheOriginalUrl() throws Exception {
        when(publicSigner.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenAnswer(invocation -> {
            GetPresignedObjectUrlArgs args = invocation.getArgument(0);
            if (args.object().endsWith("-preview.jpg")) {
                throw new IllegalStateException("signer down");
            }
            return "https://s3.example.com/" + args.object() + "?X-Amz-Signature=" + "b".repeat(64);
        });

        ImageStorageResult result = storage.uploadTranslatedImage(noisyPng(300, 300), "image/png");

        assertThat(result.stored()).isTrue();
        assertThat(result.url()).isPresent();
        assertThat(result.previewUrl()).isEmpty();
    }

    @Test
    void aFailedOriginalUploadIsStillNotStoredAndWritesNoPreview() throws Exception {
        doThrow(new IllegalStateException("backend down")).when(client).putObject(any(PutObjectArgs.class));

        ImageStorageResult result = storage.uploadTranslatedImage(noisyPng(300, 300), "image/png");

        assertThat(result.stored()).isFalse();
        assertThat(result.previewUrl()).isEmpty();
        verify(client, times(1)).putObject(any(PutObjectArgs.class));
    }

    @Test
    void unsupportedContentTypesStillWriteNothing() throws Exception {
        assertThat(storage.uploadTranslatedImage(noisyPng(300, 300), "image/gif").stored()).isFalse();

        verify(client, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void theOriginalsUploadStaysUntouchedAndHasNoPreview() throws Exception {
        ImageStorageResult result = storage.uploadImage(noisyPng(300, 300), "image/png");

        assertThat(result.stored()).isTrue();
        assertThat(result.previewUrl()).isEmpty();
        ArgumentCaptor<GetPresignedObjectUrlArgs> signed = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(publicSigner).getPresignedObjectUrl(signed.capture());
        assertThat(signed.getValue().expiry()).isEqualTo((int) TimeUnit.DAYS.toSeconds(7));
        assertThat(puts()).singleElement().satisfies(put -> assertThat(put.object()).startsWith("images/"));
    }

    @Test
    void previewsExpireWithTheirFolderSoTheCleanupJobRemovesBoth() throws Exception {
        String expiredOriginal = "translated-images/1699999999/aaaa.png";
        String expiredPreview = "translated-images/1699999999/aaaa-preview.jpg";
        String freshPreview = RETENTION_FOLDER + "bbbb-preview.jpg";
        listing(expiredOriginal, expiredPreview, freshPreview);

        storage.deleteExpiredTranslatedImages();

        ArgumentCaptor<RemoveObjectArgs> removed = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(client, times(2)).removeObject(removed.capture());
        assertThat(removed.getAllValues()).extracting(RemoveObjectArgs::object)
                .containsExactlyInAnyOrder(expiredOriginal, expiredPreview);
    }

    @Test
    void previewKeysAreRecognisedAndDerivedFromTheOriginalKey() {
        assertThat(MinioStorageService.previewObjectName("translated-images/1/abc.png"))
                .isEqualTo("translated-images/1/abc-preview.jpg");
        assertThat(MinioStorageService.previewObjectName("translated-images/1/abc.jpg"))
                .isEqualTo("translated-images/1/abc-preview.jpg");
        assertThat(MinioStorageService.isTranslatedPreview("translated-images/1/abc-preview.jpg")).isTrue();
        assertThat(MinioStorageService.isTranslatedPreview("translated-images/1/abc.jpg")).isFalse();
        assertThat(MinioStorageService.isTranslatedPreview("images/abc-preview.jpg")).isFalse();
        assertThat(MinioStorageService.isTranslatedPreview(null)).isFalse();
    }

    @SuppressWarnings("unchecked")
    private MinioStorageService wired(ObjectProvider<ImageProxyLinkService> proxyProvider) {
        ObjectProvider<MinioClient> internal = mock(ObjectProvider.class);
        when(internal.getIfAvailable()).thenReturn(client);
        ObjectProvider<MinioClient> external = mock(ObjectProvider.class);
        when(external.getIfAvailable(any())).thenReturn(publicSigner);
        return new MinioStorageService(internal, external, BUCKET, true, 1_000, proxyProvider);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ImageProxyLinkService> providerOf(ImageProxyLinkService proxy) {
        ObjectProvider<ImageProxyLinkService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(proxy);
        return provider;
    }

    private List<PutObjectArgs> puts() throws Exception {
        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client, org.mockito.Mockito.atLeastOnce()).putObject(captor.capture());
        return captor.getAllValues();
    }

    @SuppressWarnings("unchecked")
    private void listing(String... names) {
        List<Result<Item>> results = new java.util.ArrayList<>();
        for (String name : names) {
            try {
                Item item = mock(Item.class);
                when(item.objectName()).thenReturn(name);
                when(item.lastModified()).thenReturn(ZonedDateTime.of(2023, 11, 14, 0, 0, 0, 0, ZoneOffset.UTC));
                Result<Item> result = mock(Result.class);
                when(result.get()).thenReturn(item);
                results.add(result);
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(results);
    }

    /** Random pixels do not compress, so the PNG is large without a large raster. */
    private static byte[] noisyPng(int width, int height) throws IOException {
        return encode(noisyRaster(width, height), "png");
    }

    private static BufferedImage noisyRaster(int width, int height) {
        Random random = new Random(7);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        return image;
    }

    private static byte[] png(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return encode(image, "png");
    }

    private static byte[] jpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.ORANGE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return encode(image, "jpg");
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }
}
