package com.linetranslate.bot.service.imageproxy;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.linetranslate.bot.logging.SafeLog;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Fetches allowlisted signed targets and creates a LINE-compatible PNG preview. */
@Service
@Slf4j
public class ImageProxyContentService {

    private static final String PNG_CONTENT_TYPE = "image/png";

    private final ImageProxyLinkService linkService;
    private final OkHttpClient httpClient;
    private final int maxOriginalBytes;
    private final int maxPreviewBytes;
    private final int maxDimension;
    private final long maxPixels;
    private final Cache<String, ImageProxyAsset> assets;

    @Autowired
    public ImageProxyContentService(
            ImageProxyLinkService linkService,
            @Value("${app.image-translation.max-file-size-bytes:10485760}") int maxOriginalBytes,
            @Value("${app.image-translation.proxy-preview-max-bytes:1048576}") int maxPreviewBytes,
            @Value("${app.image-translation.max-dimension:4096}") int maxDimension,
            @Value("${app.image-translation.max-pixels:16000000}") long maxPixels,
            @Value("${app.image-translation.proxy-content-cache-ttl:PT5M}") Duration cacheTtl,
            @Value("${app.image-translation.proxy-content-cache-entries:256}") long cacheEntries) {
        this(
                linkService,
                new OkHttpClient.Builder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .readTimeout(Duration.ofSeconds(10))
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build(),
                maxOriginalBytes,
                maxPreviewBytes,
                maxDimension,
                maxPixels,
                cacheTtl,
                cacheEntries);
    }

    ImageProxyContentService(
            ImageProxyLinkService linkService,
            OkHttpClient httpClient,
            int maxOriginalBytes,
            int maxPreviewBytes,
            int maxDimension,
            long maxPixels,
            Duration cacheTtl,
            long cacheEntries) {
        if (maxOriginalBytes <= 0 || maxPreviewBytes <= 0 || maxDimension <= 0 || maxPixels <= 0
                || cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative() || cacheEntries <= 0) {
            throw new IllegalArgumentException("Image proxy bounds must be positive");
        }
        this.linkService = linkService;
        this.httpClient = httpClient;
        this.maxOriginalBytes = maxOriginalBytes;
        this.maxPreviewBytes = maxPreviewBytes;
        this.maxDimension = maxDimension;
        this.maxPixels = maxPixels;
        this.assets = Caffeine.newBuilder()
                .maximumSize(cacheEntries)
                .expireAfterWrite(cacheTtl)
                .build();
    }

    public Optional<ImageProxyAsset> load(String token) {
        Optional<URI> target = linkService.resolve(token);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(assets.get(token, ignored -> fetch(target.get()).orElse(null)));
        } catch (RuntimeException failure) {
            log.warn("Image proxy load failed: failure={}", SafeLog.failure(failure));
            return Optional.empty();
        }
    }

    private Optional<ImageProxyAsset> fetch(URI target) {
        Request request = new Request.Builder()
                .url(target.toString())
                .header("Accept", PNG_CONTENT_TYPE)
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() != 200 || !isPng(response.header("Content-Type"))) {
                return Optional.empty();
            }
            ResponseBody body = response.body();
            if (body == null || body.contentLength() > maxOriginalBytes) {
                return Optional.empty();
            }
            byte[] original = body.byteStream().readNBytes(maxOriginalBytes + 1);
            if (original.length == 0 || original.length > maxOriginalBytes) {
                return Optional.empty();
            }
            BufferedImage decoded = decodeAndValidate(original);
            byte[] preview = createPreview(decoded, original);
            return Optional.of(new ImageProxyAsset(original, preview));
        } catch (Exception failure) {
            log.warn("Image proxy upstream unavailable: failure={}", SafeLog.failure(failure));
            return Optional.empty();
        }
    }

    private BufferedImage decodeAndValidate(byte[] bytes) throws java.io.IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null
                || image.getWidth() <= 0
                || image.getHeight() <= 0
                || image.getWidth() > maxDimension
                || image.getHeight() > maxDimension
                || (long) image.getWidth() * image.getHeight() > maxPixels) {
            throw new java.io.IOException("PNG dimensions are invalid");
        }
        return image;
    }

    private byte[] createPreview(BufferedImage source, byte[] original) throws java.io.IOException {
        if (original.length <= maxPreviewBytes) {
            return original;
        }
        double ratio = Math.min(0.9, Math.sqrt((double) maxPreviewBytes / original.length) * 0.9);
        for (int attempt = 0; attempt < 8; attempt++) {
            int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
            int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
            BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = resized.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(source, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(resized, "png", output)) {
                throw new java.io.IOException("PNG writer unavailable");
            }
            byte[] preview = output.toByteArray();
            if (preview.length <= maxPreviewBytes) {
                return preview;
            }
            ratio *= 0.7;
        }
        throw new java.io.IOException("PNG preview exceeds LINE limit");
    }

    private static boolean isPng(String contentType) {
        return contentType != null
                && PNG_CONTENT_TYPE.equalsIgnoreCase(contentType.split(";", 2)[0].trim());
    }
}
