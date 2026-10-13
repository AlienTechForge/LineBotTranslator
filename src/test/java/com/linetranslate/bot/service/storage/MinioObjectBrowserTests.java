package com.linetranslate.bot.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.linetranslate.bot.service.storage.MinioObjectBrowser.DeleteOutcome;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.Entry;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.InvalidRequestException;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.Kind;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.Page;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.Query;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.StorageFailureException;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.StorageUnavailableException;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;

@ExtendWith(MockitoExtension.class)
class MinioObjectBrowserTests {

    private static final String BUCKET = "line-bot";
    private static final Instant T0 = Instant.parse("2026-10-10T00:00:00Z");

    private static final String ORIGINAL_A = "images/00000000-0000-4000-8000-00000000000a.jpg";
    private static final String ORIGINAL_B = "images/00000000-0000-4000-8000-00000000000b.png";
    private static final String TRANSLATED_C = "translated-images/1760000000/00000000-0000-4000-8000-00000000000c.png";
    private static final String TRANSLATED_D = "translated-images/1760000100/00000000-0000-4000-8000-00000000000d.png";

    @Mock
    private MinioClient minioClient;

    @Mock
    private MinioClient publicUrlSigner;

    private MinioObjectBrowser browser;

    @BeforeEach
    void setUp() {
        browser = new MinioObjectBrowser(minioClient, publicUrlSigner, BUCKET);
    }

    @Test
    void listsEveryKindNewestFirstWithNameDescendingAsTheTieBreaker() throws Exception {
        stored(Map.of(
                "images/", List.of(item(ORIGINAL_A, 10, T0), item(ORIGINAL_B, 20, T0.plusSeconds(30))),
                "translated-images/", List.of(item(TRANSLATED_C, 30, T0.plusSeconds(30)), item(TRANSLATED_D, 40, T0.plusSeconds(60)))));

        Page page = browser.list(query(null, null, null, 50, null));

        assertThat(page.items()).extracting(Entry::name)
                .containsExactly(TRANSLATED_D, TRANSLATED_C, ORIGINAL_B, ORIGINAL_A);
        assertThat(page.nextCursor()).isNull();
        Entry first = page.items().get(0);
        assertThat(first.kind()).isEqualTo(Kind.TRANSLATED);
        assertThat(first.size()).isEqualTo(40);
        assertThat(first.lastModified()).isEqualTo(T0.plusSeconds(60));
        assertThat(first.contentType()).isEqualTo("image/png");
        assertThat(page.items().get(3).contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void kindFilterOnlyListsItsOwnPrefixRecursively() throws Exception {
        stored(Map.of("images/", List.of(item(ORIGINAL_A, 10, T0))));

        Page page = browser.list(query(Kind.ORIGINAL, null, null, 50, null));

        assertThat(page.items()).extracting(Entry::name).containsExactly(ORIGINAL_A);
        ArgumentCaptor<ListObjectsArgs> args = ArgumentCaptor.forClass(ListObjectsArgs.class);
        verify(minioClient).listObjects(args.capture());
        assertThat(args.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(args.getValue().prefix()).isEqualTo("images/");
        assertThat(args.getValue().recursive()).isTrue();
    }

    @Test
    void dateRangeIncludesFromAndExcludesTo() throws Exception {
        stored(Map.of("images/", List.of(
                item(ORIGINAL_A, 1, T0.minusMillis(1)),
                item(ORIGINAL_B, 1, T0),
                item("images/00000000-0000-4000-8000-00000000000e.gif", 1, T0.plusSeconds(60).minusMillis(1)),
                item("images/00000000-0000-4000-8000-00000000000f.gif", 1, T0.plusSeconds(60)))));

        Page page = browser.list(query(Kind.ORIGINAL, T0, T0.plusSeconds(60), 50, null));

        assertThat(page.items()).extracting(Entry::name).containsExactly(
                "images/00000000-0000-4000-8000-00000000000e.gif", ORIGINAL_B);
    }

    @Test
    void pagesWithAnOpaqueCursorWithoutRepeatsOrGaps() throws Exception {
        List<Item> originals = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            originals.add(item("images/00000000-0000-4000-8000-00000000010" + i + ".jpg", 1, T0.plusSeconds(i)));
        }
        // Two entries share a timestamp so the cursor must also order by name.
        originals.add(item("images/00000000-0000-4000-8000-000000000200.jpg", 1, T0.plusSeconds(2)));
        stored(Map.of("images/", originals));

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Page page = browser.list(query(Kind.ORIGINAL, null, null, 2, cursor));
            assertThat(page.items()).hasSizeLessThanOrEqualTo(2);
            page.items().forEach(entry -> seen.add(entry.name()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactly(
                "images/00000000-0000-4000-8000-000000000104.jpg",
                "images/00000000-0000-4000-8000-000000000103.jpg",
                "images/00000000-0000-4000-8000-000000000200.jpg",
                "images/00000000-0000-4000-8000-000000000102.jpg",
                "images/00000000-0000-4000-8000-000000000101.jpg",
                "images/00000000-0000-4000-8000-000000000100.jpg");
    }

    @Test
    void anExactlyFullLastPageHasNoCursor() throws Exception {
        stored(Map.of("images/", List.of(item(ORIGINAL_A, 1, T0), item(ORIGINAL_B, 1, T0.plusSeconds(1)))));

        Page page = browser.list(query(Kind.ORIGINAL, null, null, 2, null));

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void skipsDirectoriesAndKeysThatCouldNotBeSafelyPreviewedOrDeleted() throws Exception {
        Item directory = item("images/folder/", 0, T0);
        when(directory.isDir()).thenReturn(true);
        stored(Map.of("images/", List.of(
                directory,
                item("images/has space.png", 1, T0),
                item("images/../escape.png", 1, T0),
                item("images//double.png", 1, T0),
                item(ORIGINAL_A, 1, T0))));

        Page page = browser.list(query(Kind.ORIGINAL, null, null, 50, null));

        assertThat(page.items()).extracting(Entry::name).containsExactly(ORIGINAL_A);
    }

    @Test
    void unknownExtensionsAreReportedAsOctetStream() throws Exception {
        stored(Map.of("images/", List.of(item("images/00000000-0000-4000-8000-000000000001.bin", 1, T0))));

        Page page = browser.list(query(Kind.ORIGINAL, null, null, 50, null));

        assertThat(page.items().get(0).contentType()).isEqualTo("application/octet-stream");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "not base64!", "bm8tc2VwYXJhdG9y" /* "no-separator" */ })
    void rejectsMalformedCursors(String cursor) {
        assertThatThrownBy(() -> browser.list(query(null, null, null, 10, cursor)))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(minioClient);
    }

    @Test
    void rejectsACursorThatNamesAKeyOutsideTheManagedPrefixes() {
        String forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("1760000000000|secrets/linebot.json".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> browser.list(query(null, null, null, 10, forged)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void queryRejectsOutOfRangeLimitsAndInvertedRanges() {
        assertThatThrownBy(() -> query(null, null, null, 0, null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> query(null, null, null, MinioObjectBrowser.MAX_PAGE_SIZE + 1, null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> query(null, T0.plusSeconds(1), T0, 10, null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> query(null, null, null, 10, "x".repeat(401)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void listFailureIsReportedWithoutLeakingTheCause() throws Exception {
        when(minioClient.listObjects(any(ListObjectsArgs.class)))
                .thenThrow(new IllegalStateException("connection refused http://user:secret@minio.internal"));

        assertThatThrownBy(() -> browser.list(query(null, null, null, 10, null)))
                .isInstanceOf(StorageFailureException.class)
                .hasNoCause()
                .message().doesNotContain("secret", "minio.internal");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailingListEntryFailsTheWholeRequest() throws Exception {
        Result<Item> broken = mock(Result.class);
        when(broken.get()).thenThrow(new io.minio.errors.MinioException("boom"));
        when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(broken));

        assertThatThrownBy(() -> browser.list(query(Kind.ORIGINAL, null, null, 10, null)))
                .isInstanceOf(StorageFailureException.class);
    }

    @Test
    void previewUrlIsSignedForTenMinutesByThePublicSignerOnly() throws Exception {
        when(publicUrlSigner.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("https://s3.example.com/line-bot/images/a.jpg?X-Amz-Signature=abc");

        String url = browser.previewUrl(ORIGINAL_A);

        assertThat(url).startsWith("https://s3.example.com/");
        ArgumentCaptor<GetPresignedObjectUrlArgs> args = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(publicUrlSigner).getPresignedObjectUrl(args.capture());
        assertThat(args.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(args.getValue().object()).isEqualTo(ORIGINAL_A);
        assertThat(args.getValue().method()).isEqualTo(io.minio.Http.Method.GET);
        assertThat(args.getValue().expiry()).isEqualTo(600);
        verifyNoInteractions(minioClient);
    }

    @Test
    void previewUrlSigningFailureIsReportedWithoutLeakingTheCause() throws Exception {
        when(publicUrlSigner.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenThrow(new IllegalStateException("secret-key-material"));

        assertThatThrownBy(() -> browser.previewUrl(TRANSLATED_C))
                .isInstanceOf(StorageFailureException.class)
                .hasNoCause();
    }

    @ParameterizedTest
    @MethodSource("namesOutsideTheManagedPrefixes")
    void previewUrlRefusesAnythingButManagedKeys(String name) {
        assertThatThrownBy(() -> browser.previewUrl(name)).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(publicUrlSigner, minioClient);
    }

    @Test
    void deletesManagedKeysFromBothPrefixesAndIgnoresDuplicates() throws Exception {
        DeleteOutcome outcome = browser.delete(List.of(ORIGINAL_A, TRANSLATED_C, ORIGINAL_A));

        assertThat(outcome.deleted()).isEqualTo(2);
        assertThat(outcome.failed()).isEmpty();
        ArgumentCaptor<RemoveObjectArgs> args = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minioClient, org.mockito.Mockito.times(2)).removeObject(args.capture());
        assertThat(args.getAllValues()).extracting(RemoveObjectArgs::object)
                .containsExactly(ORIGINAL_A, TRANSLATED_C);
        assertThat(args.getAllValues()).extracting(RemoveObjectArgs::bucket).containsOnly(BUCKET);
    }

    @ParameterizedTest
    @MethodSource("namesOutsideTheManagedPrefixes")
    void deleteRefusesAnythingButManagedKeys(String name) throws Exception {
        assertThatThrownBy(() -> browser.delete(java.util.Collections.singletonList(name)))
                .isInstanceOf(InvalidRequestException.class);
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void oneBadNameInABatchDeletesNothing() throws Exception {
        assertThatThrownBy(() -> browser.delete(List.of(ORIGINAL_A, "linebot.json")))
                .isInstanceOf(InvalidRequestException.class);
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void deleteCapsTheBatchSize() throws Exception {
        List<String> atCap = new ArrayList<>();
        List<String> overCap = new ArrayList<>();
        for (int i = 0; i < MinioObjectBrowser.MAX_DELETE_BATCH + 1; i++) {
            String name = "images/00000000-0000-4000-8000-" + String.format("%012d", i) + ".jpg";
            if (i < MinioObjectBrowser.MAX_DELETE_BATCH) {
                atCap.add(name);
            }
            overCap.add(name);
        }

        assertThatThrownBy(() -> browser.delete(overCap)).isInstanceOf(InvalidRequestException.class);
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
        assertThat(browser.delete(atCap).deleted()).isEqualTo(MinioObjectBrowser.MAX_DELETE_BATCH);
        assertThatThrownBy(() -> browser.delete(List.of())).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> browser.delete(null)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aFailedDeleteIsReportedAndTheRestStillRun() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("backend down"))
                .when(minioClient).removeObject(org.mockito.ArgumentMatchers.argThat(
                        (RemoveObjectArgs args) -> args.object().equals(ORIGINAL_A)));

        DeleteOutcome outcome = browser.delete(List.of(ORIGINAL_A, ORIGINAL_B));

        assertThat(outcome.deleted()).isEqualTo(1);
        assertThat(outcome.failed()).containsExactly(ORIGINAL_A);
    }

    @Test
    void disabledStorageIsReportedAsUnavailableAndNeverTouchesTheNetwork() {
        MinioObjectBrowser disabled = new MinioObjectBrowser(null, null, BUCKET);

        assertThatThrownBy(() -> disabled.list(query(null, null, null, 10, null)))
                .isInstanceOf(StorageUnavailableException.class);
        assertThatThrownBy(() -> disabled.previewUrl(ORIGINAL_A))
                .isInstanceOf(StorageUnavailableException.class);
        assertThatThrownBy(() -> disabled.delete(List.of(ORIGINAL_A)))
                .isInstanceOf(StorageUnavailableException.class);
    }

    @Test
    void theBrowserPrefixesMatchTheKeysTheStorageServiceWrites() {
        assertThat(Kind.ORIGINAL.prefix()).isEqualTo("images/");
        assertThat(Kind.TRANSLATED.prefix()).isEqualTo("translated-images/");
    }

    static Stream<Arguments> namesOutsideTheManagedPrefixes() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of("images/"),
                Arguments.of("translated-images/"),
                Arguments.of("images"),
                Arguments.of("linebot.json"),
                Arguments.of("other/images/a.jpg"),
                Arguments.of("/images/a.jpg"),
                Arguments.of("Images/a.jpg"),
                Arguments.of("images/../secrets.json"),
                Arguments.of("images/a/../b.jpg"),
                Arguments.of("images/./a.jpg"),
                Arguments.of("images//a.jpg"),
                Arguments.of("images/a/"),
                Arguments.of("images/a b.jpg"),
                Arguments.of("images/a%2e%2e.jpg"),
                Arguments.of("images/a\\b.jpg"),
                Arguments.of("images/a\nb.jpg"),
                Arguments.of("images/" + "a".repeat(200)),
                Arguments.of("translated-images"),
                Arguments.of("translated-imagesx/a.png"));
    }

    private static Query query(Kind kind, Instant from, Instant to, int limit, String cursor) {
        return new Query(kind, from, to, limit, cursor);
    }

    private static Item item(String name, long size, Instant lastModified) {
        Item item = mock(Item.class, org.mockito.Mockito.withSettings().lenient());
        when(item.objectName()).thenReturn(name);
        when(item.size()).thenReturn(size);
        when(item.lastModified()).thenReturn(ZonedDateTime.ofInstant(lastModified, ZoneOffset.UTC));
        return item;
    }

    /** Serves each prefix's items from a fake listing, ignoring the argument's other fields. */
    @SuppressWarnings("unchecked")
    private void stored(Map<String, List<Item>> byPrefix) throws Exception {
        when(minioClient.listObjects(any(ListObjectsArgs.class))).thenAnswer(invocation -> {
            ListObjectsArgs args = invocation.getArgument(0);
            List<Result<Item>> results = new ArrayList<>();
            for (Item item : byPrefix.getOrDefault(args.prefix(), List.of())) {
                Result<Item> result = mock(Result.class);
                when(result.get()).thenReturn(item);
                results.add(result);
            }
            return results;
        });
    }
}
