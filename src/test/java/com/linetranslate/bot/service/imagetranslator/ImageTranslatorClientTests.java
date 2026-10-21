package com.linetranslate.bot.service.imagetranslator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.linetranslate.bot.service.imagetranslator.ImageTranslatorException.Kind;
import com.linetranslate.bot.testing.ImageTranslatorFixtures;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

class ImageTranslatorClientTests {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

    private MockWebServer server;
    private ImageTranslatorProperties properties;
    private ImageTranslatorClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        properties = new ImageTranslatorProperties();
        properties.setUrl(server.url("/").toString());
        properties.setToken(ImageTranslatorFixtures.TOKEN);
        client = new ImageTranslatorClient(properties);
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    // ------------------------------------------------------------------ the request

    @Test
    void postsTheMultipartFieldsWithTheTokenToTheTranslationsPath() throws Exception {
        server.enqueue(json(200, ImageTranslatorFixtures.imageJson("msg-1", "clean", "a_pass", "A", 99)));

        client.translate(JPEG, "image/jpeg", "ja", "msg-1");

        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/v1/translations");
        assertThat(request.getHeader("X-Service-Token")).isEqualTo(ImageTranslatorFixtures.TOKEN);
        assertThat(request.getHeader("Content-Type")).startsWith("multipart/form-data");
        String body = request.getBody().readString(StandardCharsets.ISO_8859_1);
        assertThat(body)
                .contains("name=\"image\"; filename=\"image.jpg\"")
                .contains("Content-Type: image/jpeg")
                .contains("name=\"target_lang\"")
                .contains("name=\"request_id\"");
        assertThat(fieldValue(body, "target_lang")).isEqualTo("ja");
        assertThat(fieldValue(body, "request_id")).isEqualTo("msg-1");
        assertThat(body).contains(new String(JPEG, StandardCharsets.ISO_8859_1));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void aPngUploadKeepsItsMediaTypeAndFileName() throws Exception {
        server.enqueue(json(200, ImageTranslatorFixtures.noTextJson("msg-2")));

        client.translate(ImageTranslatorFixtures.png(4, 4), "image/png", "zh-TW", "msg-2");

        String body = server.takeRequest(5, TimeUnit.SECONDS).getBody().readString(StandardCharsets.ISO_8859_1);
        assertThat(body).contains("filename=\"image.png\"").contains("Content-Type: image/png");
    }

    @Test
    void speaksHttp11OnlyAndNeverAsksForAnUpgrade() throws Exception {
        server.enqueue(json(200, ImageTranslatorFixtures.noTextJson("msg-h1")));
        server.enqueue(json(200, ImageTranslatorFixtures.healthJson()));

        client.translate(JPEG, "image/jpeg", "zh-TW", "msg-h1");
        client.health();

        assertThat(client.httpClient().protocols()).containsExactly(Protocol.HTTP_1_1);
        for (int i = 0; i < 2; i++) {
            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertThat(request.getRequestLine()).endsWith("HTTP/1.1");
            assertThat(request.getHeader("Upgrade")).isNull();
            assertThat(request.getHeader("HTTP2-Settings")).isNull();
        }
    }

    // ------------------------------------------------------------------ the answers

    @Test
    void readsACleanPictureAnswerFieldForField() {
        server.enqueue(json(200, ImageTranslatorFixtures.imageJson("msg-3", "clean", "a_pass", "A", 18117)));

        ImageTranslatorResult result = client.translate(JPEG, "image/jpeg", "zh-TW", "msg-3");

        assertThat(result.requestId()).isEqualTo("msg-3");
        assertThat(result.status()).isEqualTo("image");
        assertThat(result.tier()).isEqualTo("clean");
        assertThat(result.reason()).isEqualTo("a_pass");
        assertThat(result.mode()).isEqualTo("A");
        assertThat(result.verdict()).isEqualTo("pass");
        assertThat(result.usability()).isEqualTo(0.82);
        assertThat(result.coverage()).isEqualTo(0.9);
        assertThat(result.image()).isEqualTo(
                new ImageTranslatorResult.Image("/v1/translations/msg-3/image.png", 640, 480, 18117));
        assertThat(result.text().sourceLanguage()).isEqualTo("en");
        assertThat(result.text().recognizedText()).isEqualTo("TONKATSU SET MENU NT$120");
        assertThat(result.text().translatedText()).isEqualTo("炸豬排套餐\nNT$120");
        assertThat(result.judge().summary()).isEqualTo("Looks right.");
        assertThat(result.costs()).isEqualTo(new ImageTranslatorResult.Costs(0.0123, 0.04, 0.0523));
        assertThat(result.warnings()).containsExactly("a note");
        assertThat(result.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.mode()).isEqualTo("A");
            assertThat(attempt.selected()).isTrue();
            assertThat(attempt.costUsd()).isEqualTo(0.0523);
            assertThat(attempt.elapsedMs()).isEqualTo(97L);
        });
    }

    @Test
    void readsAWarnedPictureAnswer() {
        server.enqueue(json(200, ImageTranslatorFixtures.imageJson("msg-4", "warned", "a_better", "B", 5)));

        ImageTranslatorResult result = client.translate(JPEG, "image/jpeg", "zh-TW", "msg-4");

        assertThat(result.status()).isEqualTo("image");
        assertThat(result.tier()).isEqualTo("warned");
        assertThat(result.mode()).isEqualTo("B");
        assertThat(result.verdict()).isEqualTo("numbers+score");
    }

    @Test
    void readsATextOnlyAnswerWithItsTextAndNoPicture() {
        server.enqueue(json(200, ImageTranslatorFixtures.textOnlyJson("msg-5", "你好")));

        ImageTranslatorResult result = client.translate(JPEG, "image/jpeg", "zh-TW", "msg-5");

        assertThat(result.status()).isEqualTo("text_only");
        assertThat(result.tier()).isNull();
        assertThat(result.reason()).isEqualTo("below_threshold");
        assertThat(result.image()).isNull();
        assertThat(result.judge()).isNull();
        assertThat(result.text().translatedText()).isEqualTo("你好");
        assertThat(result.text().sourceLanguage()).isEqualTo("ja");
    }

    @Test
    void aTextOnlyAnswerWithoutATranslationKeepsItNull() {
        server.enqueue(json(200, ImageTranslatorFixtures.textOnlyJson("msg-6", null)));

        assertThat(client.translate(JPEG, "image/jpeg", "zh-TW", "msg-6").text().translatedText()).isNull();
    }

    @Test
    void readsANoTextAnswer() {
        server.enqueue(json(200, ImageTranslatorFixtures.noTextJson("msg-7")));

        ImageTranslatorResult result = client.translate(JPEG, "image/jpeg", "zh-TW", "msg-7");

        assertThat(result.status()).isEqualTo("no_text");
        assertThat(result.image()).isNull();
        assertThat(result.usability()).isNull();
        assertThat(result.text().recognizedText()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"anthropic_budget", "openrouter_budget", "both_failed", "internal_error"})
    void readsAnErrorAnswerWithItsReasonAsAnAnswerNotAnException(String reason) {
        server.enqueue(json(200, ImageTranslatorFixtures.errorJson("msg-8", reason)));

        ImageTranslatorResult result = client.translate(JPEG, "image/jpeg", "zh-TW", "msg-8");

        assertThat(result.status()).isEqualTo("error");
        assertThat(result.reason()).isEqualTo(reason);
    }

    @Test
    void aSparseAnswerHasEmptyListsAndTextInsteadOfNulls() {
        server.enqueue(json(200, "{\"status\": \"no_text\"}"));

        ImageTranslatorResult result = client.translate(JPEG, "image/jpeg", "zh-TW", "msg-9");

        assertThat(result.warnings()).isEmpty();
        assertThat(result.attempts()).isEmpty();
        assertThat(result.text()).isNotNull();
        assertThat(result.text().translatedText()).isNull();
        assertThat(result.costs().totalUsd()).isZero();
    }

    // ------------------------------------------------------------------ the picture

    @Test
    void fetchesThePictureWithTheTokenAsPngBytes() throws Exception {
        byte[] png = ImageTranslatorFixtures.png(8, 8);
        server.enqueue(new MockResponse().setResponseCode(200).addHeader("Content-Type", "image/png")
                .setBody(new okio.Buffer().write(png)));

        byte[] fetched = client.fetchImage(
                new ImageTranslatorResult.Image("/v1/translations/msg-10/image.png", 8, 8, png.length));

        assertThat(fetched).isEqualTo(png);
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/v1/translations/msg-10/image.png");
        assertThat(request.getHeader("X-Service-Token")).isEqualTo(ImageTranslatorFixtures.TOKEN);
    }

    @Test
    void anExpiredPictureIs404WithTheServicesReason() {
        server.enqueue(json(404, ImageTranslatorFixtures.httpErrorJson("not_found", "no such translation")));

        assertThatThrownBy(() -> client.fetchImage(
                new ImageTranslatorResult.Image("/v1/translations/msg-11/image.png", 1, 1, 1)))
                .isInstanceOfSatisfying(ImageTranslatorException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(Kind.HTTP);
                    assertThat(failure.httpStatus()).isEqualTo(404);
                    assertThat(failure.reason()).isEqualTo("not_found");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/etc/passwd",
            "/v1/translations/../../healthz",
            "http://evil.example/v1/translations/x/image.png",
            "v1/translations/x/image.png",
            "/v1/translations/x/image.png?token=abc",
            ""})
    void refusesAPathThatIsNotATranslationPictureWithoutCallingAnything(String path) {
        assertThatThrownBy(() -> client.fetchImage(new ImageTranslatorResult.Image(path, 1, 1, 1)))
                .isInstanceOfSatisfying(ImageTranslatorException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(Kind.MALFORMED));
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void anEmptyPictureBodyIsMalformed() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(""));

        assertThatThrownBy(() -> client.fetchImage(
                new ImageTranslatorResult.Image("/v1/translations/msg-12/image.png", 1, 1, 1)))
                .isInstanceOfSatisfying(ImageTranslatorException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(Kind.MALFORMED));
    }

    // ------------------------------------------------------------------ errors

    @Test
    void a409CarriesTheServicesReasonAndIsADuplicateRequest() {
        server.enqueue(json(409, ImageTranslatorFixtures.httpErrorJson(
                "request_id_in_use", "a request with this request_id is running or still retained")));

        assertThatThrownBy(() -> client.translate(JPEG, "image/jpeg", "zh-TW", "msg-13"))
                .isInstanceOfSatisfying(ImageTranslatorException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(Kind.HTTP);
                    assertThat(failure.httpStatus()).isEqualTo(409);
                    assertThat(failure.reason()).isEqualTo("request_id_in_use");
                    assertThat(failure.detail()).contains("request_id");
                    assertThat(failure.isDuplicateRequest()).isTrue();
                });
    }

    @ParameterizedTest
    @CsvSource({
            "401, unauthorized, the X-Service-Token header is missing",
            "403, forbidden, the service token is not valid",
            "503, service_token_missing, SERVICE_TOKEN is not set",
            "400, bad_request, the `image` file is missing",
            "413, payload_too_large, the upload is larger than 20 MB",
            "415, unsupported_media_type, only image/jpeg are accepted"})
    void anHttpErrorCarriesItsStatusAndTheReasonOfTheServicesErrorJson(int status, String reason, String detail) {
        server.enqueue(json(status, ImageTranslatorFixtures.httpErrorJson(reason, detail)));

        assertThatThrownBy(() -> client.translate(JPEG, "image/jpeg", "zh-TW", "msg-14"))
                .isInstanceOfSatisfying(ImageTranslatorException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(Kind.HTTP);
                    assertThat(failure.httpStatus()).isEqualTo(status);
                    assertThat(failure.reason()).isEqualTo(reason);
                    assertThat(failure.detail()).isEqualTo(detail);
                    assertThat(failure.isDuplicateRequest()).isFalse();
                    assertThat(failure.getMessage()).contains("HTTP " + status).contains(reason)
                            .doesNotContain(ImageTranslatorFixtures.TOKEN);
                });
    }

    @Test
    void anHttpErrorThatIsNotTheServicesJsonHasAStatusAndNoReason() {
        server.enqueue(new MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>"));

        assertThatThrownBy(() -> client.translate(JPEG, "image/jpeg", "zh-TW", "msg-15"))
                .isInstanceOfSatisfying(ImageTranslatorException.class, failure -> {
                    assertThat(failure.httpStatus()).isEqualTo(502);
                    assertThat(failure.reason()).isNull();
                    assertThat(failure.detail()).isNull();
                });
    }

    @Test
    void anErrorStatusInsideA200IsNotMistakenForAnHttpError() {
        server.enqueue(json(200, ImageTranslatorFixtures.errorJson("msg-16", "anthropic_budget")));

        assertThat(client.translate(JPEG, "image/jpeg", "zh-TW", "msg-16").reason()).isEqualTo("anthropic_budget");
    }

    @Test
    void aServiceThatDoesNotAnswerInTimeIsATimeout() {
        OkHttpClient impatient = new OkHttpClient.Builder()
                .readTimeout(Duration.ofMillis(300)).writeTimeout(Duration.ofSeconds(5)).build();
        ImageTranslatorClient impatientClient = new ImageTranslatorClient(properties, impatient);
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        assertThatThrownBy(() -> impatientClient.translate(JPEG, "image/jpeg", "zh-TW", "msg-17"))
                .isInstanceOfSatisfying(ImageTranslatorException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(Kind.TIMEOUT);
                    assertThat(failure.httpStatus()).isZero();
                });
    }

    @Test
    void theReadTimeoutOfTheDefaultClientIsTheConfiguredOne() throws Exception {
        properties.setTimeout(Duration.ofMillis(400));
        ImageTranslatorClient configured = new ImageTranslatorClient(properties);
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        long started = System.nanoTime();
        assertThatThrownBy(() -> configured.translate(JPEG, "image/jpeg", "zh-TW", "msg-18"))
                .isInstanceOfSatisfying(ImageTranslatorException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(Kind.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void aServiceThatIsNotListeningIsUnreachable() throws IOException {
        server.shutdown();

        assertThatThrownBy(() -> client.translate(JPEG, "image/jpeg", "zh-TW", "msg-19"))
                .isInstanceOfSatisfying(ImageTranslatorException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(Kind.UNREACHABLE);
                    assertThat(failure.getMessage()).doesNotContain(ImageTranslatorFixtures.TOKEN);
                });
    }

    @Test
    void anInterruptedCallIsNotATimeout() {
        OkHttpClient bounded = new OkHttpClient.Builder()
                .readTimeout(Duration.ofSeconds(2)).writeTimeout(Duration.ofSeconds(2)).build();
        ImageTranslatorClient boundedClient = new ImageTranslatorClient(properties, bounded);
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> boundedClient.translate(JPEG, "image/jpeg", "zh-TW", "msg-20"))
                    .isInstanceOfSatisfying(ImageTranslatorException.class,
                            failure -> assertThat(failure.kind()).isNotEqualTo(Kind.TIMEOUT));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @CsvSource(value = {"this is not json", "{\"status\": ", "[]", "{}", "{\"status\": \"\"}"})
    void aBodyThatIsNotTheServicesJsonIsMalformed(String body) {
        server.enqueue(json(200, body));

        assertThatThrownBy(() -> client.translate(JPEG, "image/jpeg", "zh-TW", "msg-21"))
                .isInstanceOfSatisfying(ImageTranslatorException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(Kind.MALFORMED);
                    assertThat(failure.httpStatus()).isEqualTo(200);
                });
    }

    @Test
    void anUnusableConfiguredUrlIsReportedAsUnreachableNotAsACrash() {
        properties.setUrl("not a url");

        assertThatThrownBy(() -> client.translate(JPEG, "image/jpeg", "zh-TW", "msg-22"))
                .isInstanceOfSatisfying(ImageTranslatorException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(Kind.UNREACHABLE));
    }

    // ------------------------------------------------------------------ health

    @Test
    void healthReadsTheBudgetNumbersWithoutSendingTheToken() throws Exception {
        server.enqueue(json(200, ImageTranslatorFixtures.healthJson()));

        ImageTranslatorHealth health = client.health();

        assertThat(health.up()).isTrue();
        assertThat(health.mock()).isFalse();
        assertThat(health.version()).isEqualTo("1.0.0");
        assertThat(health.budget()).isEqualTo(new ImageTranslatorHealth.Budget("2026-10", 1.5, 15.0, 2.5, 80.0));
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/healthz");
        assertThat(request.getHeader("X-Service-Token")).isNull();
    }

    @Test
    void healthOfAServiceThatAnswersAnErrorOrGarbageIsAnException() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("oops"));
        server.enqueue(json(200, "not json"));

        assertThatThrownBy(() -> client.health()).isInstanceOfSatisfying(ImageTranslatorException.class,
                failure -> assertThat(failure.kind()).isEqualTo(Kind.HTTP));
        assertThatThrownBy(() -> client.health()).isInstanceOfSatisfying(ImageTranslatorException.class,
                failure -> assertThat(failure.kind()).isEqualTo(Kind.MALFORMED));
    }

    @Test
    void healthOfAStoppedServiceIsUnreachable() throws IOException {
        server.shutdown();

        assertThatThrownBy(() -> client.health()).isInstanceOfSatisfying(ImageTranslatorException.class,
                failure -> assertThat(failure.kind()).isEqualTo(Kind.UNREACHABLE));
    }

    // ------------------------------------------------------------------ helpers

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).addHeader("Content-Type", "application/json").setBody(body);
    }

    /** The text of one form field of a multipart body: the line after the blank line that follows its header. */
    private static String fieldValue(String body, String name) {
        int header = body.indexOf("name=\"" + name + "\"");
        int start = body.indexOf("\r\n\r\n", header) + 4;
        return body.substring(start, body.indexOf("\r\n", start));
    }
}
