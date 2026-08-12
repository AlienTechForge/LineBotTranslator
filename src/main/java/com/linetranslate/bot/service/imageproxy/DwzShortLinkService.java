package com.linetranslate.bot.service.imageproxy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linetranslate.bot.logging.SafeLog;

import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Optional authenticated adapter for the self-hosted 木雷 short-link API. */
@Service
@Slf4j
public class DwzShortLinkService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Pattern PROXY_PATH = Pattern.compile("/i/[A-Za-z0-9_-]{22}(?:/preview)?");
    private static final Pattern SIGNATURE = Pattern.compile("[a-fA-F0-9]{64}");
    private static final Set<String> REQUIRED_SIGNED_QUERY_KEYS = Set.of(
            "X-Amz-Algorithm", "X-Amz-Credential", "X-Amz-Date", "X-Amz-Expires",
            "X-Amz-SignedHeaders", "X-Amz-Signature");
    private static final int MAX_RESPONSE_BYTES = 65_536;

    private final URI apiBaseUri;
    private final String bearerToken;
    private final String shortDomain;
    private final String workspaceId;
    private final Duration linkTtl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public DwzShortLinkService(
            @Value("${app.short-url.dwz-api-base-url:${DWZ_API_BASE_URL:}}") String apiBaseUrl,
            @Value("${app.short-url.dwz-api-token:${DWZ_API_TOKEN:}}") String bearerToken,
            @Value("${app.short-url.dwz-domain:${DWZ_SHORT_DOMAIN:}}") String shortDomain,
            @Value("${app.short-url.dwz-workspace-id:${DWZ_WORKSPACE_ID:}}") String workspaceId,
            @Value("${app.short-url.ttl:PT55M}") Duration linkTtl,
            ObjectMapper objectMapper) {
        this(
                apiBaseUrl,
                bearerToken,
                shortDomain,
                workspaceId,
                linkTtl,
                new OkHttpClient.Builder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .readTimeout(Duration.ofSeconds(5))
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build(),
                objectMapper,
                Clock.systemUTC());
    }

    DwzShortLinkService(
            String apiBaseUrl,
            String bearerToken,
            String shortDomain,
            String workspaceId,
            Duration linkTtl,
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock) {
        this.apiBaseUri = validApiBase(apiBaseUrl).orElse(null);
        this.bearerToken = bearerToken == null ? "" : bearerToken.trim();
        this.shortDomain = validDomain(shortDomain).orElse("");
        this.workspaceId = workspaceId == null ? "" : workspaceId.trim();
        if (linkTtl == null || linkTtl.isZero() || linkTtl.isNegative()) {
            throw new IllegalArgumentException("Short URL TTL must be positive");
        }
        this.linkTtl = linkTtl;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Optional<ImageProxyLinks> shorten(ImageProxyLinks proxyLinks) {
        if (!configured() || proxyLinks == null
                || !isCleanProxyUrl(proxyLinks.original())
                || !isCleanProxyUrl(proxyLinks.preview())) {
            return Optional.empty();
        }
        Optional<URI> original = create(proxyLinks.original());
        if (original.isEmpty()) {
            return Optional.empty();
        }
        Optional<URI> preview = create(proxyLinks.preview());
        return preview.map(uri -> new ImageProxyLinks(original.get(), uri));
    }

    /** Shortens one internally generated MinIO presigned translated-image URL. */
    public Optional<URI> shortenSignedImage(URI signedImageUrl) {
        if (!configured() || !isSignedTranslatedImageUrl(signedImageUrl)) {
            return Optional.empty();
        }
        return create(signedImageUrl);
    }

    private Optional<URI> create(URI target) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("original_url", target.toString());
        if (!shortDomain.isBlank()) {
            payload.put("domain", shortDomain);
        }
        payload.put("title", "LINE translated image");
        payload.put("expire_at", clock.instant().plus(linkTtl).toString());

        Request.Builder request = new Request.Builder()
                .url(apiBaseUri + "/api/v1/short_links")
                .header("Authorization", "Bearer " + bearerToken)
                .header("Accept", "application/json")
                .post(RequestBody.create(payload.toString(), JSON));
        if (!workspaceId.isBlank()) {
            request.header("X-Workspace-Id", workspaceId);
        }

        try (Response response = httpClient.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) {
                return Optional.empty();
            }
            ResponseBody body = response.body();
            if (body == null || body.contentLength() > MAX_RESPONSE_BYTES) {
                return Optional.empty();
            }
            byte[] bytes = body.byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(bytes);
            if (root.path("code").asInt(-1) != 0) {
                return Optional.empty();
            }
            return validatedShortUrl(root.path("data").path("short_url").asText(""));
        } catch (Exception failure) {
            log.warn("DWZ short-link creation unavailable: failure={}", SafeLog.failure(failure));
            return Optional.empty();
        }
    }

    private boolean configured() {
        return apiBaseUri != null && !bearerToken.isBlank();
    }

    private Optional<URI> validatedShortUrl(String raw) {
        try {
            URI uri = URI.create(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || (!shortDomain.isBlank()
                            && !uri.getHost().equalsIgnoreCase(shortDomain))
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                return Optional.empty();
            }
            return Optional.of(uri);
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static boolean isCleanProxyUrl(URI uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && uri.getUserInfo() == null
                && uri.getQuery() == null
                && uri.getFragment() == null
                && PROXY_PATH.matcher(uri.getPath()).matches();
    }

    private static boolean isSignedTranslatedImageUrl(URI uri) {
        if (uri == null
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getRawPath() == null
                || !uri.getRawPath().contains("/translated-images/")) {
            return false;
        }
        Map<String, String> query = parseRawQuery(uri.getRawQuery());
        return query.keySet().containsAll(REQUIRED_SIGNED_QUERY_KEYS)
                && "AWS4-HMAC-SHA256".equals(query.get("X-Amz-Algorithm"))
                && SIGNATURE.matcher(query.getOrDefault("X-Amz-Signature", "")).matches();
    }

    private static Map<String, String> parseRawQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        Map<String, String> values = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0 && separator < pair.length() - 1) {
                values.putIfAbsent(pair.substring(0, separator), pair.substring(separator + 1));
            }
        }
        return values;
    }

    private static Optional<URI> validApiBase(String raw) {
        try {
            URI uri = URI.create(raw == null ? "" : raw.trim());
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                return Optional.empty();
            }
            boolean secure = "https".equalsIgnoreCase(scheme);
            boolean localHttp = "http".equalsIgnoreCase(scheme)
                    && ("localhost".equalsIgnoreCase(uri.getHost())
                            || "127.0.0.1".equals(uri.getHost())
                            || !uri.getHost().contains("."));
            if (!secure && !localHttp) {
                return Optional.empty();
            }
            String value = uri.toString();
            return Optional.of(URI.create(value.endsWith("/")
                    ? value.substring(0, value.length() - 1) : value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<String> validDomain(String raw) {
        String value = raw == null ? "" : raw.trim();
        try {
            URI uri = URI.create("https://" + value);
            if (value.isBlank() || uri.getHost() == null || !value.equalsIgnoreCase(uri.getHost())
                    || uri.getPort() >= 0 || uri.getUserInfo() != null) {
                return Optional.empty();
            }
            return Optional.of(value.toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
