package com.linetranslate.bot.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import okhttp3.Response;

@ExtendWith(MockitoExtension.class)
class MinioStorageRecoveryIntegrationTests {

    @Mock
    private MinioClient minioClient;

    @Mock
    private MinioClient publicUrlSigner;

    private AtomicLong clock;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000);
    }

    @Test
    void outageBacksOffAndAutomaticallyRecoversOnTheNextEligibleRequest() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(new IllegalStateException(
                        "connection refused at http://user:secret@minio.internal"))
                .thenReturn(true);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("https://storage.example/image.jpg");
        MinioStorageService service = service(1_000);

        ImageStorageResult first = service.uploadImage(new byte[] {1}, "image/jpeg");
        ImageStorageResult suppressed = service.uploadImage(new byte[] {2}, "image/jpeg");

        assertThat(first.stored()).isFalse();
        assertThat(suppressed.stored()).isFalse();
        verify(minioClient, times(1)).bucketExists(any(BucketExistsArgs.class));
        verify(minioClient, never()).putObject(any(PutObjectArgs.class));

        clock.addAndGet(1_001);
        ImageStorageResult recovered = service.uploadImage(new byte[] {3}, "image/jpeg");

        assertThat(recovered.stored()).isTrue();
        assertThat(recovered.url()).contains("https://storage.example/image.jpg");
        assertThat(service.isAvailable()).isTrue();
        verify(minioClient, times(3)).bucketExists(any(BucketExistsArgs.class));
        verify(minioClient, times(1)).putObject(any(PutObjectArgs.class));
    }

    @Test
    void successfulUploadReturnsOnlyTheUrlIssuedByMinio() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("https://storage.example/signed-image.jpg");
        MinioStorageService service = service(1_000);

        ImageStorageResult result = service.uploadImage(new byte[] {1, 2}, "image/png");

        assertThat(result.stored()).isTrue();
        assertThat(result.url()).contains("https://storage.example/signed-image.jpg");
        assertThat(result.url().orElseThrow()).doesNotContain("192.168.0.10");
    }

    @Test
    void signedUrlFailureNeverReturnsAFakeFallbackUrl() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenThrow(new IllegalStateException("connection refused"));
        MinioStorageService service = service(1_000);

        ImageStorageResult result = service.uploadImage(new byte[] {1, 2}, "image/gif");

        assertThat(result.stored()).isTrue();
        assertThat(result.url()).isEmpty();
    }

    @Test
    void storageTrafficUsesInternalClientWhileSignedUrlsUsePublicEndpointClient() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(publicUrlSigner.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("https://s3.azndev.com/line-bot/images/signed.png?signature=safe");
        MinioStorageService service = new MinioStorageService(
                minioClient,
                publicUrlSigner,
                "line-bot",
                true,
                1_000,
                clock::get);

        ImageStorageResult result = service.uploadImage(new byte[] {1, 2}, "image/png");

        assertThat(result.url()).contains(
                "https://s3.azndev.com/line-bot/images/signed.png?signature=safe");
        verify(minioClient).bucketExists(any(BucketExistsArgs.class));
        verify(minioClient).putObject(any(PutObjectArgs.class));
        verify(minioClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
        verify(publicUrlSigner).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    @Test
    void s3FailureDiagnosticsExposeOnlySafeCodeAndStatus() {
        ErrorResponse responseBody = mock(ErrorResponse.class);
        Response response = mock(Response.class);
        ErrorResponseException failure = mock(ErrorResponseException.class);
        when(responseBody.code()).thenReturn("InvalidAccessKeyId");
        when(response.code()).thenReturn(403);
        when(failure.errorResponse()).thenReturn(responseBody);
        when(failure.response()).thenReturn(response);

        assertThat(MinioStorageService.safeFailure(failure))
                .isEqualTo("ErrorResponseException[s3Code=InvalidAccessKeyId,httpStatus=403]")
                .doesNotContain("secret", "message", "request");
    }

    private MinioStorageService service(long retryIntervalMs) {
        return new MinioStorageService(
                minioClient,
                "linebot-images-test",
                true,
                retryIntervalMs,
                clock::get);
    }
}
