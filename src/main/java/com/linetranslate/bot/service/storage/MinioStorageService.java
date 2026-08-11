package com.linetranslate.bot.service.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.logging.SafeLog;

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

    private static final String TRANSLATED_IMAGE_PREFIX = "translated-images/";
    private static final int TRANSLATED_URL_EXPIRY_SECONDS = 3_600;
    private static final long TRANSLATED_RETENTION_SECONDS = 86_400;

    private final MinioClient minioClient;
    private final MinioClient publicUrlSigner;
    private final String bucketName;
    private final long retryIntervalMs;
    private final LongSupplier currentTimeMs;
    private final AtomicReference<State> state;
    private final AtomicLong retryAfterMs = new AtomicLong(0);
    private final AtomicBoolean bucketReady = new AtomicBoolean(false);

    @Autowired
    public MinioStorageService(
            @Qualifier("minioClient") ObjectProvider<MinioClient> minioClientProvider,
            @Qualifier("minioPublicClient") ObjectProvider<MinioClient> publicUrlSignerProvider,
            @Value("${minio.bucket-name}") String bucketName,
            @Value("${minio.enabled:${MINIO_ENABLED:true}}") boolean enabled,
            @Value("${minio.retry-interval-ms:${MINIO_RETRY_INTERVAL_MS:30000}}") long retryIntervalMs) {
        this(
                minioClientProvider.getIfAvailable(),
                publicUrlSignerProvider.getIfAvailable(minioClientProvider::getIfAvailable),
                bucketName,
                enabled,
                retryIntervalMs,
                System::currentTimeMillis);
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
        if (retryIntervalMs <= 0) {
            throw new IllegalArgumentException("MinIO retry interval must be greater than zero");
        }
        this.minioClient = minioClient;
        this.publicUrlSigner = publicUrlSigner == null ? minioClient : publicUrlSigner;
        this.bucketName = bucketName;
        this.retryIntervalMs = retryIntervalMs;
        this.currentTimeMs = currentTimeMs;
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

    /** Stores a generated PNG behind a one-hour signed URL and a 24-hour retention key. */
    public ImageStorageResult uploadTranslatedImage(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0 || !beginAttempt()) {
            return ImageStorageResult.notStored();
        }

        String objectName = generateTranslatedObjectName();
        try {
            ensureBucket();
            try (InputStream inputStream = new ByteArrayInputStream(imageBytes)) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucketName)
                                .object(objectName)
                                .stream(inputStream, Long.valueOf(imageBytes.length), Long.valueOf(-1))
                                .contentType("image/png")
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
        return "images/" + UUID.randomUUID() + extension;
    }

    private String generateTranslatedObjectName() {
        long expiresAt = currentTimeMs.getAsLong() / 1_000 + TRANSLATED_RETENTION_SECONDS;
        return TRANSLATED_IMAGE_PREFIX + expiresAt + "/" + UUID.randomUUID() + ".png";
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
