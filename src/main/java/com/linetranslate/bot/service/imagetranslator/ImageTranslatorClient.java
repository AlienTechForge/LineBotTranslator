package com.linetranslate.bot.service.imagetranslator;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.imagetranslator.ImageTranslatorException.Kind;

import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * HTTP client of the image-translator service ({@code services/image-translator}): one multipart
 * {@code POST /v1/translations} that answers with the JSON of the finished translation, then a
 * {@code GET} of the picture it names. Both carry the {@code X-Service-Token} header. The client
 * owns its OkHttp instance (connect 5 s, read and write as long as the configured timeout, since the
 * service answers only when the whole translation is done) and its own JSON mapper, so nothing
 * else's configuration changes how the service's snake_case documents are read.
 *
 * <p>Nothing here logs the token, an image, a text or a URL; a failure is an
 * {@link ImageTranslatorException} that carries the HTTP status and the service's reason code.
 */
@Component
@Slf4j
public class ImageTranslatorClient {

    static final String TOKEN_HEADER = "X-Service-Token";
    private static final String TRANSLATIONS_PATH = "/v1/translations";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(3);
    private static final long MAX_JSON_BYTES = 2L * 1024 * 1024;
    /** The service refuses uploads above 20 MB and writes PNG pictures of 2K at most; this is a sanity bound. */
    private static final long MAX_IMAGE_BYTES = 64L * 1024 * 1024;

    private final ImageTranslatorProperties properties;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    public ImageTranslatorClient(ImageTranslatorProperties properties) {
        this(properties, new OkHttpClient.Builder()
                // The service (uvicorn) speaks HTTP/1.1 only; asking for h2c makes it log upgrade warnings.
                .protocols(List.of(Protocol.HTTP_1_1))
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(properties.getTimeout())
                .writeTimeout(properties.getTimeout())
                .build());
    }

    ImageTranslatorClient(ImageTranslatorProperties properties, OkHttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    /** The OkHttp instance of this client, for tests. */
    OkHttpClient httpClient() {
        return httpClient;
    }

    /**
     * Asks the service to translate one picture into {@code targetLang} (a BCP-47 code). The
     * {@code requestId} ({@code [A-Za-z0-9_-]{1,64}}) makes the request idempotent on the service's
     * side: sending it again while its record is kept is a 409.
     *
     * @throws ImageTranslatorException when the service cannot be reached, answers with an HTTP error, or
     *         answers with something that is not its JSON
     */
    public ImageTranslatorResult translate(byte[] image, String contentType, String targetLang, String requestId) {
        MediaType mediaType = MediaType.parse(contentType == null ? "application/octet-stream" : contentType);
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", fileName(contentType), RequestBody.create(image, mediaType))
                .addFormDataPart("target_lang", targetLang)
                .addFormDataPart("request_id", requestId)
                .build();
        Request request = authorized(properties.baseUrl() + TRANSLATIONS_PATH).post(body).build();
        try (Response response = call(httpClient, request)) {
            String json = readJson(response);
            if (response.code() != 200) {
                throw httpFailure(response.code(), json);
            }
            ImageTranslatorResult result = parse(json, ImageTranslatorResult.class);
            if (result.status() == null || result.status().isBlank()) {
                throw new ImageTranslatorException(Kind.MALFORMED, 200, null, null, null);
            }
            return result;
        }
    }

    /**
     * Fetches the picture a successful translation names ({@code image.path}, a path on the service) as
     * PNG bytes.
     *
     * @throws ImageTranslatorException as {@link #translate}, and when the path is not a translation path
     *         of the service
     */
    public byte[] fetchImage(ImageTranslatorResult.Image image) {
        String path = image == null ? null : image.path();
        if (path == null || !path.startsWith(TRANSLATIONS_PATH + "/") || path.contains("..") || path.contains("?")) {
            throw new ImageTranslatorException(Kind.MALFORMED, 200, null, null, null);
        }
        Request request = authorized(properties.baseUrl() + path).get().build();
        try (Response response = call(httpClient, request)) {
            if (response.code() != 200) {
                throw httpFailure(response.code(), readJson(response));
            }
            ResponseBody body = response.body();
            if (body == null || body.contentLength() > MAX_IMAGE_BYTES) {
                throw new ImageTranslatorException(Kind.MALFORMED, 200, null, null, null);
            }
            byte[] bytes = body.bytes();
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                throw new ImageTranslatorException(Kind.MALFORMED, 200, null, null, null);
            }
            return bytes;
        } catch (IOException failure) {
            throw ImageTranslatorException.of(kindOf(failure), failure);
        } catch (ImageTranslatorException failure) {
            throw failure;
        } catch (Exception failure) {
            throw unchecked(failure);
        }
    }

    /**
     * {@code GET /healthz} within three seconds, no token needed. A service that answers anything but
     * its health JSON is an {@link ImageTranslatorException}.
     */
    public ImageTranslatorHealth health() {
        Request request = new Request.Builder().url(url(properties.baseUrl() + "/healthz")).get().build();
        OkHttpClient probe = httpClient.newBuilder().callTimeout(HEALTH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).build();
        try (Response response = call(probe, request)) {
            String json = readJson(response);
            if (response.code() != 200) {
                throw httpFailure(response.code(), json);
            }
            return parse(json, ImageTranslatorHealth.class);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private Request.Builder authorized(String url) {
        return new Request.Builder().url(url(url)).header(TOKEN_HEADER, properties.getToken());
    }

    private static HttpUrl url(String value) {
        HttpUrl parsed = HttpUrl.parse(value);
        if (parsed == null) {
            throw new ImageTranslatorException(Kind.UNREACHABLE, 0, null, null,
                    new IllegalArgumentException("the image-translator URL is not a valid http(s) URL"));
        }
        return parsed;
    }

    private static Response call(OkHttpClient client, Request request) {
        try {
            return client.newCall(request).execute();
        } catch (IOException failure) {
            throw ImageTranslatorException.of(kindOf(failure), failure);
        } catch (Exception failure) {
            throw unchecked(failure);
        }
    }

    /**
     * OkHttp is Kotlin: on an interrupted thread it can throw a bare {@link InterruptedException} that
     * no signature announces. That is a call that did not finish, not a defect: the flag is set again
     * and the failure is the ordinary one, which the caller recognises as cancellation by the flag.
     */
    private static RuntimeException unchecked(Exception failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return ImageTranslatorException.of(Kind.UNREACHABLE, failure);
    }

    /**
     * A timeout of OkHttp is an {@link InterruptedIOException}; so is the interruption of this thread,
     * which the caller sees on the thread's flag and which is not a timeout.
     */
    private static Kind kindOf(IOException failure) {
        return failure instanceof InterruptedIOException && !Thread.currentThread().isInterrupted()
                ? Kind.TIMEOUT : Kind.UNREACHABLE;
    }

    private static String readJson(Response response) {
        ResponseBody body = response.body();
        if (body == null) {
            return "";
        }
        try {
            return response.peekBody(MAX_JSON_BYTES).string();
        } catch (IOException failure) {
            throw ImageTranslatorException.of(kindOf(failure), failure);
        } catch (Exception failure) {
            throw unchecked(failure);
        }
    }

    private <T> T parse(String json, Class<T> type) {
        try {
            T value = mapper.readValue(json, type);
            if (value == null) {
                throw new ImageTranslatorException(Kind.MALFORMED, 200, null, null, null);
            }
            return value;
        } catch (JsonProcessingException failure) {
            throw new ImageTranslatorException(Kind.MALFORMED, 200, null, null, failure);
        }
    }

    /** The service's error JSON is {@code {"status": "error", "reason": ..., "detail": ...}}; anything else has no reason. */
    private ImageTranslatorException httpFailure(int status, String json) {
        String reason = null;
        String detail = null;
        try {
            JsonNode node = mapper.readTree(json);
            if (node != null && node.isObject() && "error".equals(node.path("status").asText())) {
                reason = text(node, "reason");
                detail = text(node, "detail");
            }
        } catch (JsonProcessingException notTheServicesJson) {
            // a proxy or a crash page: the status alone has to do
        }
        log.warn("The image-translator answered with an error: status={}, reason={}",
                SafeLog.httpStatus(status), SafeLog.metadata(reason));
        return ImageTranslatorException.http(status, reason, detail);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String fileName(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("png")) {
            return "image.png";
        }
        if (type.contains("webp")) {
            return "image.webp";
        }
        return "image.jpg";
    }
}
