package com.linetranslate.bot.controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.linetranslate.bot.config.AdminWebTokenCondition;
import com.linetranslate.bot.service.storage.MinioObjectBrowser;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.Entry;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.InvalidRequestException;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.Kind;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.Page;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.Query;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.StorageFailureException;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.StorageUnavailableException;

import lombok.extern.slf4j.Slf4j;

/**
 * Operator web page for browsing and deleting the images the bot stored in MinIO. It only exists
 * when {@code app.admin.web.token} is set; {@link AdminImageGalleryWebConfiguration} requires that
 * token as a bearer credential on every route except the static page.
 */
@RestController
@RequestMapping(AdminImageGalleryWebConfiguration.PAGE_PATH)
@Conditional(AdminWebTokenCondition.class)
@Slf4j
public class AdminImageGalleryController {

    private static final String NONCE_PLACEHOLDER = "__CSP_NONCE__";
    private static final int MAX_PARAMETER_LENGTH = 64;

    private final MinioObjectBrowser browser;
    private final String pageTemplate;
    private final SecureRandom random = new SecureRandom();

    public AdminImageGalleryController(
            MinioObjectBrowser browser,
            @Value("classpath:admin/image-gallery.html") Resource page,
            @Value("${app.admin.web.token}") String token) {
        this.browser = browser;
        try {
            this.pageTemplate = page.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("Admin image gallery page is unavailable", failure);
        }
        log.info("Admin image gallery is enabled");
        if (token.strip().length() < 16) {
            log.warn("Admin image gallery token is shorter than 16 characters; use a longer random value");
        }
    }

    /** The static shell. It holds no data and no secrets; the token is typed into it, not sent in a URL. */
    @GetMapping
    public ResponseEntity<String> page() {
        byte[] nonceBytes = new byte[18];
        random.nextBytes(nonceBytes);
        String nonce = Base64.getEncoder().encodeToString(nonceBytes);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy",
                        "default-src 'none'; script-src 'nonce-" + nonce + "'; style-src 'nonce-" + nonce + "'; "
                                + "img-src http: https:; connect-src 'self'; base-uri 'none'; "
                                + "form-action 'none'; frame-ancestors 'none'")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .header("Referrer-Policy", "no-referrer")
                .body(pageTemplate.replace(NONCE_PLACEHOLDER, nonce));
    }

    @GetMapping("/api/list")
    public ListResponse list(
            @RequestParam(defaultValue = "all") String kind,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String tz,
            @RequestParam(defaultValue = "" + MinioObjectBrowser.DEFAULT_PAGE_SIZE) String limit,
            @RequestParam(required = false) String cursor) {
        ZoneId zone = parseZone(tz);
        LocalDate fromDate = parseDate(from);
        LocalDate toDate = parseDate(to);
        Query query = new Query(
                parseKind(kind),
                fromDate == null ? null : fromDate.atStartOfDay(zone).toInstant(),
                toDate == null ? null : toDate.plusDays(1).atStartOfDay(zone).toInstant(),
                parseLimit(limit),
                blankToNull(cursor));
        Page page = browser.list(query);
        return new ListResponse(page.items().stream().map(ObjectView::of).toList(), page.nextCursor());
    }

    @GetMapping("/api/url")
    public UrlResponse url(@RequestParam(required = false) String name) {
        return new UrlResponse(browser.previewUrl(name), MinioObjectBrowser.PREVIEW_EXPIRY_SECONDS);
    }

    @DeleteMapping("/api/object")
    public ResponseEntity<DeleteResponse> deleteOne(@RequestParam(required = false) String name) {
        if (name == null) {
            throw new InvalidRequestException();
        }
        MinioObjectBrowser.DeleteOutcome outcome = browser.delete(List.of(name));
        HttpStatus status = outcome.failed().isEmpty() ? HttpStatus.OK : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(new DeleteResponse(outcome.deleted(), outcome.failed()));
    }

    @PostMapping("/api/delete")
    public ResponseEntity<?> deleteMany(@RequestBody DeleteRequest request) {
        List<String> names = request == null ? null : request.names();
        if (names == null || names.isEmpty()) {
            throw new InvalidRequestException();
        }
        if (names.size() > MinioObjectBrowser.MAX_DELETE_BATCH) {
            return ResponseEntity.badRequest().body(new ErrorResponse("too_many_names"));
        }
        MinioObjectBrowser.DeleteOutcome outcome = browser.delete(names);
        return ResponseEntity.ok(new DeleteResponse(outcome.deleted(), outcome.failed()));
    }

    @ExceptionHandler({ InvalidRequestException.class, HttpMessageNotReadableException.class })
    ResponseEntity<ErrorResponse> invalidRequest() {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request"));
    }

    @ExceptionHandler(StorageUnavailableException.class)
    ResponseEntity<ErrorResponse> storageUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse("storage_unavailable"));
    }

    @ExceptionHandler(StorageFailureException.class)
    ResponseEntity<ErrorResponse> storageFailure() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse("storage_error"));
    }

    private static Kind parseKind(String value) {
        return switch (value) {
            case "all" -> null;
            case "original" -> Kind.ORIGINAL;
            case "translated" -> Kind.TRANSLATED;
            default -> throw new InvalidRequestException();
        };
    }

    private static int parseLimit(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException malformed) {
            throw new InvalidRequestException();
        }
    }

    /** Dates are calendar days in the caller's zone; UTC when the page does not say. */
    private static ZoneId parseZone(String value) {
        if (value == null || value.isBlank()) {
            return ZoneId.of("UTC");
        }
        if (value.length() > MAX_PARAMETER_LENGTH) {
            throw new InvalidRequestException();
        }
        try {
            return ZoneId.of(value);
        } catch (DateTimeException malformed) {
            throw new InvalidRequestException();
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException malformed) {
            throw new InvalidRequestException();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record ObjectView(String name, String kind, long size, String lastModified, String contentType) {

        static ObjectView of(Entry entry) {
            Instant modified = entry.lastModified();
            return new ObjectView(
                    entry.name(), entry.kind().wireName(), entry.size(), modified.toString(), entry.contentType());
        }
    }

    public record ListResponse(List<ObjectView> items, String nextCursor) {
    }

    /** The signed URL is a credential, so it is kept out of {@code toString} and any debug logging. */
    public record UrlResponse(String url, int expiresInSeconds) {

        @Override
        public String toString() {
            return "UrlResponse[expiresInSeconds=" + expiresInSeconds + "]";
        }
    }

    public record DeleteRequest(List<String> names) {
    }

    public record DeleteResponse(int deleted, List<String> failed) {
    }

    public record ErrorResponse(String error) {
    }
}
