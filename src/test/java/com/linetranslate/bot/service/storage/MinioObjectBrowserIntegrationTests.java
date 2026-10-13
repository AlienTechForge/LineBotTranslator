package com.linetranslate.bot.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.linetranslate.bot.service.storage.MinioObjectBrowser.DeleteOutcome;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.Entry;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.InvalidRequestException;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.Kind;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.Page;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.Query;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.messages.Item;

/** Runs against the MinIO container CI starts; skipped where {@code MINIO_RECOVERY_UPSTREAM_ENDPOINT} is unset. */
@EnabledIfEnvironmentVariable(named = "MINIO_RECOVERY_UPSTREAM_ENDPOINT", matches = ".+")
class MinioObjectBrowserIntegrationTests {

    @Test
    void browsesPreviewsAndDeletesObjectsTheStorageServiceWrote() throws Exception {
        String endpoint = System.getenv("MINIO_RECOVERY_UPSTREAM_ENDPOINT");
        String accessKey = System.getenv().getOrDefault("MINIO_ACCESS_KEY", "linebot-test");
        String secretKey = System.getenv().getOrDefault("MINIO_SECRET_KEY", "linebot-test-secret");
        String region = System.getenv().getOrDefault("MINIO_REGION", "us-east-1");
        String bucket = "linebot-gallery-" + UUID.randomUUID().toString().replace("-", "");
        MinioClient client = client(endpoint, region, accessKey, secretKey);
        MinioClient publicSigner = client("https://s3.example.com", region, accessKey, secretKey);
        MinioStorageService storage = new MinioStorageService(
                client, publicSigner, bucket, true, 1_000, System::currentTimeMillis);
        MinioObjectBrowser browser = new MinioObjectBrowser(client, publicSigner, bucket);

        try {
            assertThat(storage.uploadImage(new byte[] {1, 2, 3}, "image/jpeg").stored()).isTrue();
            assertThat(storage.uploadImage(new byte[] {4, 5}, "image/png").stored()).isTrue();
            assertThat(storage.uploadTranslatedImage(new byte[] {6}).stored()).isTrue();
            put(client, bucket, "other/keep.txt");

            Page everything = browser.list(new Query(null, null, null, 50, null));
            assertThat(everything.items()).hasSize(3);
            assertThat(everything.nextCursor()).isNull();
            assertThat(everything.items()).extracting(Entry::kind)
                    .containsExactlyInAnyOrder(Kind.ORIGINAL, Kind.ORIGINAL, Kind.TRANSLATED);
            assertThat(everything.items()).extracting(Entry::name)
                    .allMatch(name -> name.startsWith("images/") || name.startsWith("translated-images/"));
            assertThat(everything.items()).extracting(Entry::lastModified).isSortedAccordingTo(
                    java.util.Comparator.reverseOrder());

            Page first = browser.list(new Query(null, null, null, 2, null));
            assertThat(first.items()).hasSize(2);
            assertThat(first.nextCursor()).isNotBlank();
            Page second = browser.list(new Query(null, null, null, 2, first.nextCursor()));
            assertThat(second.items()).hasSize(1);
            assertThat(second.nextCursor()).isNull();
            List<String> paged = new ArrayList<>();
            first.items().forEach(entry -> paged.add(entry.name()));
            second.items().forEach(entry -> paged.add(entry.name()));
            assertThat(paged).containsExactlyElementsOf(everything.items().stream().map(Entry::name).toList());

            assertThat(browser.list(new Query(Kind.TRANSLATED, null, null, 50, null)).items()).hasSize(1);
            assertThat(browser.list(new Query(Kind.ORIGINAL, null, null, 50, null)).items()).hasSize(2);

            Instant now = Instant.now();
            assertThat(browser.list(new Query(
                    null, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)), 50, null)).items()).hasSize(3);
            assertThat(browser.list(new Query(
                    null, now.plus(Duration.ofDays(2)), now.plus(Duration.ofDays(3)), 50, null)).items()).isEmpty();

            Entry target = everything.items().get(0);
            URI preview = URI.create(browser.previewUrl(target.name()));
            assertThat(preview.getHost()).isEqualTo("s3.example.com");
            assertThat(preview.getRawQuery()).contains("X-Amz-Expires=600");

            DeleteOutcome outcome = browser.delete(List.of(target.name()));
            assertThat(outcome.deleted()).isEqualTo(1);
            assertThat(outcome.failed()).isEmpty();
            assertThat(browser.list(new Query(null, null, null, 50, null)).items())
                    .extracting(Entry::name).doesNotContain(target.name()).hasSize(2);

            assertThatThrownBy(() -> browser.delete(List.of("other/keep.txt")))
                    .isInstanceOf(InvalidRequestException.class);
            assertThat(client.statObject(
                    StatObjectArgs.builder().bucket(bucket).object("other/keep.txt").build()).size()).isEqualTo(4);
        } finally {
            try {
                removeBucket(client, bucket);
            } catch (Exception cleanupFailure) {
                // Cleanup must not mask the assertion that failed first.
            }
        }
    }

    private static void put(MinioClient client, String bucket, String object) throws Exception {
        byte[] bytes = "keep".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        client.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(object)
                .stream(new ByteArrayInputStream(bytes), Long.valueOf(bytes.length), Long.valueOf(-1))
                .contentType("text/plain")
                .build());
    }

    private static void removeBucket(MinioClient client, String bucket) throws Exception {
        for (Result<Item> result : client.listObjects(
                ListObjectsArgs.builder().bucket(bucket).recursive(true).build())) {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(result.get().objectName()).build());
        }
        client.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
    }

    private static MinioClient client(String endpoint, String region, String accessKey, String secretKey) {
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .region(region)
                .credentials(accessKey, secretKey)
                .build();
        client.setTimeout(3_000, 5_000, 5_000);
        return client;
    }
}
