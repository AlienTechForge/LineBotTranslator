package com.linetranslate.bot.service.storage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.imageproxy.ImageProxyLinkService;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class MinioStorageService {

    /** Key prefix of user-submitted originals; shared with the admin object browser. */
    static final String ORIGINAL_IMAGE_PREFIX = "images/";
    /** Key prefix of rendered translations; shared with the admin object browser. */
    static final String TRANSLATED_IMAGE_PREFIX = "translated-images/";
    /**
     * Suffix of the LINE preview stored beside a translated image, sharing its retention folder so
     * the cleanup job removes both together.
     */
    static final String TRANSLATED_PREVIEW_SUFFIX = "-preview.jpg";
    private static final int TRANSLATED_URL_EXPIRY_SECONDS = 3_600;
    private static final long TRANSLATED_RETENTION_SECONDS = 86_400;

    private final MinioClient minioClient;
    private final MinioClient publicUrlSigner;
    private final String bucketName;
    private final long retryIntervalMs;
    private final LongSupplier currentTimeMs;
    private final BooleanSupplier previewRenditionNeeded;
    private final AtomicReference<State> state;
    private final AtomicLong retryAfterMs = new AtomicLong(0);
    private final AtomicBoolean bucketReady = new AtomicBoolean(false);

    @Autowired
    public MinioStorageService(
            @Qualifier("minioClient") ObjectProvider<MinioClient> minioClientProvider,
            @Qualifier("minioPublicClient") ObjectProvider<MinioClient> publicUrlSignerProvider,
            @Value("${minio.bucket-name}") String bucketName,
            @Value("${minio.enabled:${MINIO_ENABLED:true}}") boolean enabled,
            @Value("${minio.retry-interval-ms:${MINIO_RETRY_INTERVAL_MS:30000}}") long retryIntervalMs,
            ObjectProvider<ImageProxyLinkService> imageProxyProvider) {
        this(
                minioClientProvider.getIfAvailable(),
                publicUrlSignerProvider.getIfAvailable(minioClientProvider::getIfAvailable),
                bucketName,
                enabled,
                retryIntervalMs,
                System::currentTimeMillis,
                // The image proxy already serves a bounded preview; only without it does LINE need
                // a separately stored one.
                () -> {
                    ImageProxyLinkService proxy = imageProxyProvider.getIfAvailable();
                    return proxy == null || !proxy.isEnabled();
                });
    }

    MinioStorageService(
            MinioClient minioClient,
            String bucketName,
            boolean enabled,
            long retryIntervalMs,
            LongSupplier currentTimeMs) {
        this(minioClient, minioClient, bucketName, enabled, retryIntervalMs, currentTimeMs);
    }

    MinioStorageService(
            MinioClient minioClient,
            MinioClient publicUrlSigner,
            String bucketName,
            boolean enabled,
            long retryIntervalMs,
            LongSupplier currentTimeMs) {
        this(minioClient, publicUrlSigner, bucketName, enabled, retryIntervalMs, currentTimeMs, () -> true);
    }

    MinioStorageService(
            MinioClient minioClient,
            MinioClient publicUrlSigner,
            String bucketName,
            boolean enabled,
            long retryIntervalMs,
            LongSupplier currentTimeMs,
            BooleanSupplier previewRenditionNeeded) {
        if (retryIntervalMs <= 0) {
            throw new IllegalArgumentException("MinIO retry interval must be greater than zero");
        }
        this.minioClient = minioClient;
        this.publicUrlSigner = publicUrlSigner == null ? minioClient : publicUrlSigner;
        this.bucketName = bucketName;
        this.retryIntervalMs = retryIntervalMs;
        this.currentTimeMs = currentTimeMs;
        this.previewRenditionNeeded = previewRenditionNeeded;
        this.state = new AtomicReference<>(enabled && minioClient != null ? State.UNKNOWN : State.DISABLED);

        if (this.state.get() == State.DISABLED) {
            log.info("MinIO storage is disabled or unconfigured; image translation will continue without storage");
        }
    }

    /**
     * Stores an image when MinIO is available. An open circuit skips network calls until the
     * configured recovery interval has elapsed.
     */
    public ImageStorageResult uploadImage(byte[] imageBytes, String contentType) {
        if (!beginAttempt()) {
            return ImageStorageResult.notStored();
        }

        String objectName = generateObjectName(contentType);
        try {
            ensureBucket();
            try (InputStream inputStream = new ByteArrayInputStream(imageBytes)) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucketName)
                                .object(objectName)
                                .stream(inputStream, Long.valueOf(imageBytes.length), Long.valueOf(-1))
                                .contentType(contentType)
                                .build());
            }

            try {
                String url = publicUrlSigner.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .bucket(bucketName)
                                .object(objectName)
                                .method(Method.GET)
                                .expiry(7, TimeUnit.DAYS)
                                .build());
                markAvailable();
                log.info("MinIO image stored: object={}", objectName);
                return ImageStorageResult.stored(url);
            } catch (Exception failure) {
                markUnavailable(failure);
                return ImageStorageResult.storedWithoutUrl();
            }
        } catch (Exception failure) {
            markUnavailable(failure);
            return ImageStorageResult.notStored();
        }
    }

    /**
     * Stores a generated PNG or JPEG behind a one-hour signed URL and a 24-hour retention key.
     * Any other content type is refused, since the object is served to LINE as an image.
     *
     * <p>Unless the image proxy is serving previews, a 240 px JPEG rendition is stored beside it
     * ({@code <name>-preview.jpg}, same retention folder) because LINE rejects a preview above
     * 1 MB. The rendition is best effort: when it cannot be built or stored, the result simply has
     * no preview URL and the caller reuses the original.
     */
    public ImageStorageResult uploadTranslatedImage(byte[] imageBytes, String contentType) {
        return uploadTranslatedImage(imageBytes, contentType, null);
    }

    /**
     * As {@link #uploadTranslatedImage(byte[], String)}, for a caller that still holds the image
     * decoded. {@code raster} must be the image exactly as {@code imageBytes} encodes it (a
     * lossless PNG of it); the preview is then scaled from it instead of decoding the bytes into
     * a second full-size raster. Pass {@code null} when the bytes are lossy or rescaled, and the
     * preview is built from them as before. The raster is only read, and not kept after the call.
     */
    public ImageStorageResult uploadTranslatedImage(
            byte[] imageBytes, String contentType, BufferedImage raster) {
        String extension = translatedImageExtension(contentType);
        if (imageBytes == null || imageBytes.length == 0 || extension == null || !beginAttempt()) {
            return ImageStorageResult.notStored();
        }

        String objectName = generateTranslatedObjectName(extension);
        try {
            ensureBucket();
            try (InputStream inputStream = new ByteArrayInputStream(imageBytes)) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucketName)
                                .object(objectName)
                                .stream(inputStream, Long.valueOf(imageBytes.length), Long.valueOf(-1))
                                .contentType(contentType)
                                .build());
            }
            try {
                String url = publicUrlSigner.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .bucket(bucketName)
                                .object(objectName)
                                .method(Method.GET)
                                .expiry(TRANSLATED_URL_EXPIRY_SECONDS, TimeUnit.SECONDS)
                                .build());
                markAvailable();
                log.info("MinIO translated image stored: object={}", objectName);
                return ImageStorageResult.stored(
                        url, storeTranslatedPreview(imageBytes, raster, objectName).orElse(null));
            } catch (Exception failure) {
                markUnavailable(failure);
                return ImageStorageResult.storedWithoutUrl();
            }
        } catch (Exception failure) {
            markUnavailable(failure);
            return ImageStorageResult.notStored();
        }
    }

    /**
     * Stores the preview rendition of an already stored translated image. Never throws and never
     * touches the circuit state: the original is safely stored, so a failure here only costs the
     * separate preview. Logs byte sizes and pixel counts, never URLs.
     */
    private Optional<String> storeTranslatedPreview(
            byte[] imageBytes, BufferedImage raster, String objectName) {
        String previewName = previewObjectName(objectName);
        try {
            if (!previewRenditionNeeded.getAsBoolean()) {
                return Optional.empty();
            }
            Optional<TranslatedImagePreview.Rendition> rendition = raster == null
                    ? TranslatedImagePreview.create(imageBytes)
                    : TranslatedImagePreview.create(raster);
            if (rendition.isEmpty()) {
                log.info("MinIO translated image preview skipped: object={}, originalBytes={}",
                        objectName, imageBytes.length);
                return Optional.empty();
            }
            byte[] previewBytes = rendition.get().bytes();
            try (InputStream inputStream = new ByteArrayInputStream(previewBytes)) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucketName)
                                .object(previewName)
                                .stream(inputStream, Long.valueOf(previewBytes.length), Long.valueOf(-1))
                                .contentType(TranslatedImagePreview.CONTENT_TYPE)
                                .build());
            }
            String url = publicUrlSigner.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .bucket(bucketName)
                            .object(previewName)
                            .method(Method.GET)
                            .expiry(TRANSLATED_URL_EXPIRY_SECONDS, TimeUnit.SECONDS)
                            .build());
            log.info("MinIO translated image preview stored: object={}, previewBytes={}, width={}, height={}",
                    previewName, previewBytes.length, rendition.get().width(), rendition.get().height());
            return Optional.of(url);
        } catch (Exception failure) {
            log.warn("MinIO translated image preview unavailable: object={}, failure={}",
                    previewName, safeFailure(failure));
            return Optional.empty();
        }
    }

    /** True for the preview rendition stored beside a translated image. */
    static boolean isTranslatedPreview(String objectName) {
        return objectName != null
                && objectName.startsWith(TRANSLATED_IMAGE_PREFIX)
                && objectName.endsWith(TRANSLATED_PREVIEW_SUFFIX);
    }

    /** Key of the preview rendition of a translated image: same folder and id, JPEG suffix. */
    static String previewObjectName(String translatedObjectName) {
        int extension = translatedObjectName.lastIndexOf('.');
        String stem = extension > translatedObjectName.lastIndexOf('/')
                ? translatedObjectName.substring(0, extension)
                : translatedObjectName;
        return stem + TRANSLATED_PREVIEW_SUFFIX;
    }

    /** Deletes generated-image objects after their retention timestamp encoded in the key. */
    @Scheduled(fixedDelayString = "${app.image-translation.cleanup-interval:PT1H}")
    public void deleteExpiredTranslatedImages() {
        if (!beginAttempt()) {
            return;
        }
        try {
            ensureBucket();
            long nowEpochSeconds = currentTimeMs.getAsLong() / 1_000;
            Iterable<Result<Item>> objects = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucketName)
                            .prefix(TRANSLATED_IMAGE_PREFIX)
                            .recursive(true)
                            .build());
            for (Result<Item> result : objects) {
                String objectName = result.get().objectName();
                if (isExpiredTranslatedImage(objectName, nowEpochSeconds)) {
                    minioClient.removeObject(
                            RemoveObjectArgs.builder().bucket(bucketName).object(objectName).build());
                }
            }
            markAvailable();
        } catch (Exception failure) {
            markUnavailable(failure);
        }
    }

    /** Read-only availability state for diagnostics; an eligible call also probes recovery. */
    public boolean isAvailable() {
        if (!beginAttempt()) {
            return false;
        }

        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                bucketReady.set(false);
                ensureBucket();
            } else {
                bucketReady.set(true);
            }
            markAvailable();
            return true;
        } catch (Exception failure) {
            markUnavailable(failure);
            return false;
        }
    }

    private boolean beginAttempt() {
        while (true) {
            State current = state.get();
            if (current == State.DISABLED || current == State.PROBING) {
                return false;
            }
            if (current == State.AVAILABLE) {
                return true;
            }
            if (current == State.UNAVAILABLE && currentTimeMs.getAsLong() < retryAfterMs.get()) {
                return false;
            }
            if (state.compareAndSet(current, State.PROBING)) {
                return true;
            }
        }
    }

    private void ensureBucket() throws Exception {
        if (bucketReady.get()) {
            return;
        }

        synchronized (bucketReady) {
            if (bucketReady.get()) {
                return;
            }
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
            bucketReady.set(true);
        }
    }

    private void markAvailable() {
        State previous = state.getAndSet(State.AVAILABLE);
        retryAfterMs.set(0);
        if (previous != State.AVAILABLE) {
            log.info("MinIO storage state changed: state=available, bucket={}", bucketName);
        }
    }

    private void markUnavailable(Exception failure) {
        State previous = state.getAndSet(State.UNAVAILABLE);
        bucketReady.set(false);
        retryAfterMs.set(currentTimeMs.getAsLong() + retryIntervalMs);
        if (previous != State.UNAVAILABLE) {
            log.warn(
                    "MinIO storage state changed: state=unavailable, bucket={}, failure={}",
                    bucketName,
                    safeFailure(failure));
        }
    }

    static String safeFailure(Exception failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ErrorResponseException responseFailure) {
                String code = responseFailure.errorResponse() == null
                        ? "unknown"
                        : safeToken(responseFailure.errorResponse().code());
                int status = responseFailure.response() == null
                        ? 0
                        : responseFailure.response().code();
                return "ErrorResponseException[s3Code=" + code + ",httpStatus=" + status + "]";
            }
            current = current.getCause();
        }
        return SafeLog.failure(failure);
    }

    private static String safeToken(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}")) {
            return "unknown";
        }
        return value;
    }

    private String generateObjectName(String contentType) {
        String extension = switch (contentType == null ? "" : contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            default -> ".bin";
        };
        return ORIGINAL_IMAGE_PREFIX + UUID.randomUUID() + extension;
    }

    private static String translatedImageExtension(String contentType) {
        return switch (contentType == null ? "" : contentType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            default -> null;
        };
    }

    private String generateTranslatedObjectName(String extension) {
        long expiresAt = currentTimeMs.getAsLong() / 1_000 + TRANSLATED_RETENTION_SECONDS;
        return TRANSLATED_IMAGE_PREFIX + expiresAt + "/" + UUID.randomUUID() + extension;
    }

    private static boolean isExpiredTranslatedImage(String objectName, long nowEpochSeconds) {
        if (objectName == null || !objectName.startsWith(TRANSLATED_IMAGE_PREFIX)) {
            return false;
        }
        int timestampStart = TRANSLATED_IMAGE_PREFIX.length();
        int timestampEnd = objectName.indexOf('/', timestampStart);
        if (timestampEnd <= timestampStart) {
            return false;
        }
        try {
            return Long.parseLong(objectName.substring(timestampStart, timestampEnd)) <= nowEpochSeconds;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private enum State {
        UNKNOWN,
        PROBING,
        AVAILABLE,
        UNAVAILABLE,
        DISABLED
    }
}
