package com.linetranslate.bot.service.storage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.config.AdminWebTokenCondition;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;

/**
 * Operator-facing view of the images the bot stored: list, preview and delete. It is separate from
 * {@link MinioStorageService} because it has no circuit breaker and no upload path; an operator
 * should see the real storage error instead of a silently skipped call. It only ever touches keys
 * under the two prefixes the bot writes, and it is only instantiated when the admin web token is
 * configured.
 */
@Service
@Conditional(AdminWebTokenCondition.class)
@Slf4j
public class MinioObjectBrowser {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;
    public static final int MAX_DELETE_BATCH = 200;
    public static final int PREVIEW_EXPIRY_SECONDS = 600;

    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_CURSOR_LENGTH = 400;
    /** Slash separated segments of letters, digits, dot, underscore and hyphen; no empty segments. */
    private static final Pattern SAFE_REMAINDER = Pattern.compile("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*");

    private static final Comparator<Entry> NEWEST_FIRST = Comparator
            .comparing(Entry::lastModified)
            .thenComparing(Entry::name)
            .reversed();
    private static final Comparator<Entry> OLDEST_FIRST = NEWEST_FIRST.reversed();

    private final MinioClient minioClient;
    private final MinioClient publicUrlSigner;
    private final String bucketName;

    @Autowired
    public MinioObjectBrowser(
            @Qualifier("minioClient") ObjectProvider<MinioClient> minioClientProvider,
            @Qualifier("minioPublicClient") ObjectProvider<MinioClient> publicUrlSignerProvider,
            @Value("${minio.bucket-name}") String bucketName,
            @Value("${minio.enabled:${MINIO_ENABLED:true}}") boolean enabled) {
        this(
                enabled ? minioClientProvider.getIfAvailable() : null,
                enabled ? publicUrlSignerProvider.getIfAvailable(minioClientProvider::getIfAvailable) : null,
                bucketName);
    }

    MinioObjectBrowser(MinioClient minioClient, MinioClient publicUrlSigner, String bucketName) {
        this.minioClient = minioClient;
        this.publicUrlSigner = publicUrlSigner == null ? minioClient : publicUrlSigner;
        this.bucketName = bucketName;
    }

    /**
     * Returns one page of stored images, newest first. MinIO lists keys in lexicographic order, so
     * every page walks the matching prefixes once and keeps only the {@code limit + 1} newest
     * entries after the cursor; memory stays bounded and no server-side state is needed.
     */
    public Page list(Query query) {
        MinioClient client = requireClient();
        Cursor after = query.cursor() == null ? null : Cursor.decode(query.cursor());
        List<Kind> kinds = query.kind() == null ? List.of(Kind.values()) : List.of(query.kind());
        int keep = query.limit() + 1;
        PriorityQueue<Entry> newest = new PriorityQueue<>(keep + 1, OLDEST_FIRST);

        try {
            for (Kind kind : kinds) {
                Iterable<Result<Item>> objects = client.listObjects(
                        ListObjectsArgs.builder()
                                .bucket(bucketName)
                                .prefix(kind.prefix())
                                .recursive(true)
                                .build());
                for (Result<Item> result : objects) {
                    Item item = result.get();
                    if (item.isDir() || !isManagedName(item.objectName())) {
                        continue;
                    }
                    Entry entry = toEntry(kind, item);
                    if (inRange(entry, query) && isAfter(entry, after)) {
                        newest.add(entry);
                        if (newest.size() > keep) {
                            newest.poll();
                        }
                    }
                }
            }
        } catch (Exception failure) {
            log.warn("Admin image gallery could not list objects: failure={}",
                    MinioStorageService.safeFailure(failure));
            throw new StorageFailureException();
        }

        List<Entry> ordered = new ArrayList<>(newest);
        ordered.sort(NEWEST_FIRST);
        if (ordered.size() <= query.limit()) {
            return new Page(List.copyOf(ordered), null);
        }
        List<Entry> items = List.copyOf(ordered.subList(0, query.limit()));
        return new Page(items, Cursor.encode(items.get(items.size() - 1)));
    }

    /** Presigns a short-lived GET for previewing one object, signed for the public endpoint. */
    public String previewUrl(String name) {
        requireManagedName(name);
        requireClient();
        try {
            return publicUrlSigner.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .bucket(bucketName)
                            .object(name)
                            .method(Method.GET)
                            .expiry(PREVIEW_EXPIRY_SECONDS, TimeUnit.SECONDS)
                            .build());
        } catch (Exception failure) {
            log.warn("Admin image gallery could not sign a preview: failure={}",
                    MinioStorageService.safeFailure(failure));
            throw new StorageFailureException();
        }
    }

    /**
     * Deletes objects by name. Every name is validated before the first delete so a bad request
     * removes nothing; a storage failure on one object is reported without stopping the others.
     */
    public DeleteOutcome delete(Collection<String> names) {
        if (names == null || names.isEmpty() || names.size() > MAX_DELETE_BATCH) {
            throw new InvalidRequestException();
        }
        Set<String> unique = new LinkedHashSet<>(names);
        unique.forEach(MinioObjectBrowser::requireManagedName);
        MinioClient client = requireClient();

        int deleted = 0;
        List<String> failed = new ArrayList<>();
        for (String name : unique) {
            try {
                client.removeObject(RemoveObjectArgs.builder().bucket(bucketName).object(name).build());
                deleted++;
            } catch (Exception failure) {
                failed.add(name);
                log.warn("Admin image gallery could not delete an object: object={}, failure={}",
                        name, MinioStorageService.safeFailure(failure));
            }
        }
        log.info("Admin image gallery deleted objects: deleted={}, failed={}", deleted, failed.size());
        return new DeleteOutcome(deleted, List.copyOf(failed));
    }

    /** True for keys under a known prefix whose remainder is a plain, traversal-free path. */
    static boolean isManagedName(String name) {
        if (name == null || name.length() > MAX_NAME_LENGTH) {
            return false;
        }
        for (Kind kind : Kind.values()) {
            if (name.startsWith(kind.prefix())) {
                String remainder = name.substring(kind.prefix().length());
                return SAFE_REMAINDER.matcher(remainder).matches() && !hasDotSegment(remainder);
            }
        }
        return false;
    }

    private static void requireManagedName(String name) {
        if (!isManagedName(name)) {
            throw new InvalidRequestException();
        }
    }

    private static boolean hasDotSegment(String remainder) {
        for (String segment : remainder.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private MinioClient requireClient() {
        if (minioClient == null) {
            throw new StorageUnavailableException();
        }
        return minioClient;
    }

    private static Entry toEntry(Kind kind, Item item) {
        Instant lastModified = item.lastModified() == null
                ? Instant.EPOCH
                : item.lastModified().toInstant().truncatedTo(ChronoUnit.MILLIS);
        return new Entry(item.objectName(), kind, item.size(), lastModified, contentTypeOf(item.objectName()));
    }

    /** The bot derives the extension from the upload content type, so the extension is enough. */
    private static String contentTypeOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "application/octet-stream";
    }

    private static boolean inRange(Entry entry, Query query) {
        return (query.from() == null || !entry.lastModified().isBefore(query.from()))
                && (query.to() == null || entry.lastModified().isBefore(query.to()));
    }

    /** True when the entry sorts strictly after the cursor in newest-first order. */
    private static boolean isAfter(Entry entry, Cursor cursor) {
        if (cursor == null) {
            return true;
        }
        int byTime = Long.compare(cursor.epochMillis(), entry.lastModified().toEpochMilli());
        return byTime != 0 ? byTime > 0 : entry.name().compareTo(cursor.name()) < 0;
    }

    /** The two key families the bot writes. */
    public enum Kind {
        ORIGINAL("original", MinioStorageService.ORIGINAL_IMAGE_PREFIX),
        TRANSLATED("translated", MinioStorageService.TRANSLATED_IMAGE_PREFIX);

        private final String wireName;
        private final String prefix;

        Kind(String wireName, String prefix) {
            this.wireName = wireName;
            this.prefix = prefix;
        }

        public String wireName() {
            return wireName;
        }

        String prefix() {
            return prefix;
        }
    }

    /**
     * One list request. {@code kind == null} means every kind; {@code from} is inclusive and
     * {@code to} exclusive; {@code cursor} is the opaque value of a previous {@link Page}.
     */
    public record Query(Kind kind, Instant from, Instant to, int limit, String cursor) {

        public Query {
            if (limit < 1 || limit > MAX_PAGE_SIZE) {
                throw new InvalidRequestException();
            }
            if (from != null && to != null && !from.isBefore(to)) {
                throw new InvalidRequestException();
            }
            if (cursor != null && cursor.length() > MAX_CURSOR_LENGTH) {
                throw new InvalidRequestException();
            }
        }
    }

    public record Entry(String name, Kind kind, long size, Instant lastModified, String contentType) {
    }

    public record Page(List<Entry> items, String nextCursor) {
    }

    public record DeleteOutcome(int deleted, List<String> failed) {
    }

    private record Cursor(long epochMillis, String name) {

        static String encode(Entry last) {
            String raw = last.lastModified().toEpochMilli() + "|" + last.name();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String value) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
                int separator = raw.indexOf('|');
                if (separator < 1) {
                    throw new InvalidRequestException();
                }
                String name = raw.substring(separator + 1);
                requireManagedName(name);
                return new Cursor(Long.parseLong(raw.substring(0, separator)), name);
            } catch (IllegalArgumentException malformed) {
                throw new InvalidRequestException();
            }
        }
    }

    /** The request itself is wrong: bad parameter, unknown prefix, oversized batch. */
    public static final class InvalidRequestException extends RuntimeException {
        public InvalidRequestException() {
            super("Invalid admin image request", null, false, false);
        }
    }

    /** MinIO is disabled or has no credentials, so there is nothing to browse. */
    public static final class StorageUnavailableException extends RuntimeException {
        public StorageUnavailableException() {
            super("Object storage is not available", null, false, false);
        }
    }

    /** A MinIO call failed; the cause is logged in sanitized form and never exposed. */
    public static final class StorageFailureException extends RuntimeException {
        public StorageFailureException() {
            super("Object storage request failed", null, false, false);
        }
    }
}
