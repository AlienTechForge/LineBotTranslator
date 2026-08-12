package com.linetranslate.bot.service.imageproxy;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/** Issues bounded opaque capabilities only for this app's translated MinIO objects. */
@Service
public class ImageProxyLinkService {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final Pattern SIGNATURE = Pattern.compile("[a-fA-F0-9]{64}");
    private static final Set<String> REQUIRED_QUERY_KEYS = Set.of(
            "X-Amz-Algorithm",
            "X-Amz-Credential",
            "X-Amz-Date",
            "X-Amz-Expires",
            "X-Amz-SignedHeaders",
            "X-Amz-Signature");

    private final URI publicBaseUri;
    private final URI minioPublicUri;
    private final String translatedPathPrefix;
    private final Cache<String, URI> targets;
    private final Supplier<String> tokenSupplier;

    @Autowired
    public ImageProxyLinkService(
            @Value("${app.image-translation.proxy-public-base-url:}") String publicBaseUrl,
            @Value("${minio.public-endpoint:${MINIO_PUBLIC_ENDPOINT:}}") String minioPublicEndpoint,
            @Value("${minio.bucket-name}") String bucketName,
            @Value("${app.image-translation.proxy-token-ttl:PT55M}") Duration tokenTtl,
            @Value("${app.image-translation.proxy-max-entries:10000}") long maxEntries) {
        this(publicBaseUrl, minioPublicEndpoint, bucketName, tokenTtl, maxEntries,
                secureTokenSupplier());
    }

    ImageProxyLinkService(
            String publicBaseUrl,
            String minioPublicEndpoint,
            String bucketName,
            Duration tokenTtl,
            long maxEntries,
            Supplier<String> tokenSupplier) {
        this.publicBaseUri = validPublicBase(publicBaseUrl).orElse(null);
        this.minioPublicUri = validMinioEndpoint(minioPublicEndpoint).orElse(null);
        String bucket = bucketName == null ? "" : bucketName.trim();
        this.translatedPathPrefix = "/" + bucket + "/translated-images/";
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalArgumentException("Image proxy token TTL must be positive");
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("Image proxy max entries must be positive");
        }
        this.targets = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(tokenTtl)
                .build();
        this.tokenSupplier = tokenSupplier;
    }

    public Optional<ImageProxyLinks> register(String signedImageUrl) {
        Optional<URI> validated = validatedTarget(signedImageUrl);
        if (publicBaseUri == null || validated.isEmpty()) {
            return Optional.empty();
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            String token = tokenSupplier.get();
            if (!validToken(token) || targets.asMap().putIfAbsent(token, validated.get()) != null) {
                continue;
            }
            String root = publicBaseUri.toString() + "/i/" + token;
            return Optional.of(new ImageProxyLinks(URI.create(root), URI.create(root + "/preview")));
        }
        return Optional.empty();
    }

    public Optional<URI> resolve(String token) {
        return validToken(token) ? Optional.ofNullable(targets.getIfPresent(token)) : Optional.empty();
    }

    private Optional<URI> validatedTarget(String rawUrl) {
        if (minioPublicUri == null || rawUrl == null || rawUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            URI target = URI.create(rawUrl);
            String rawPath = target.getRawPath();
            if (!sameOrigin(target, minioPublicUri)
                    || target.getUserInfo() != null
                    || target.getFragment() != null
                    || rawPath == null
                    || !rawPath.startsWith(translatedPathPrefix)
                    || rawPath.length() <= translatedPathPrefix.length()
                    || rawPath.contains("//")
                    || rawPath.toLowerCase(Locale.ROOT).contains("%2e")) {
                return Optional.empty();
            }
            java.util.Map<String, String> query = parseRawQuery(target.getRawQuery());
            if (!query.keySet().containsAll(REQUIRED_QUERY_KEYS)
                    || !"AWS4-HMAC-SHA256".equals(query.get("X-Amz-Algorithm"))
                    || !SIGNATURE.matcher(query.getOrDefault("X-Amz-Signature", "")).matches()) {
                return Optional.empty();
            }
            return Optional.of(target);
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static java.util.Map<String, String> parseRawQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return java.util.Map.of();
        }
        java.util.Map<String, String> values = new java.util.HashMap<>();
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0 || separator == pair.length() - 1) {
                continue;
            }
            values.putIfAbsent(pair.substring(0, separator), pair.substring(separator + 1));
        }
        return values;
    }

    private static Optional<URI> validPublicBase(String raw) {
        try {
            URI uri = URI.create(raw == null ? "" : raw.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                return Optional.empty();
            }
            return Optional.of(URI.create(stripTrailingSlash(uri.toString())));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<URI> validMinioEndpoint(String raw) {
        try {
            URI uri = URI.create(raw == null ? "" : raw.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
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

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme() != null
                && left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost() != null
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean validToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static Supplier<String> secureTokenSupplier() {
        SecureRandom random = new SecureRandom();
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return () -> {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            return encoder.encodeToString(bytes);
        };
    }
}
