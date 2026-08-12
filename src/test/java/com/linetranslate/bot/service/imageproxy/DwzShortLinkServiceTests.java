package com.linetranslate.bot.service.imageproxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import okhttp3.OkHttpClient;

class DwzShortLinkServiceTests {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void createsAuthenticatedExpiringDwzLinksForCleanProxyTargets() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/short_links", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                    .isEqualTo("Bearer test-token");
            assertThat(body)
                    .contains("\"original_url\":\"https://translate.azndev.com/i/")
                    .contains("\"domain\":\"s.azndev.com\"")
                    .contains("\"expire_at\":\"2026-08-12T08:12:00Z\"")
                    .doesNotContain("X-Amz");
            int call = calls.incrementAndGet();
            byte[] response = ("{\"code\":0,\"message\":\"success\",\"data\":{"
                    + "\"short_url\":\"https://s.azndev.com/code" + call + "\"}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        DwzShortLinkService service = new DwzShortLinkService(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-token",
                "s.azndev.com",
                "",
                Duration.ofMinutes(55),
                new OkHttpClient.Builder().followRedirects(false).build(),
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-12T07:17:00Z"), ZoneOffset.UTC));
        ImageProxyLinks proxy = new ImageProxyLinks(
                URI.create("https://translate.azndev.com/i/0123456789abcdefghij-_"),
                URI.create("https://translate.azndev.com/i/0123456789abcdefghij-_/preview"));

        ImageProxyLinks shortened = service.shorten(proxy).orElseThrow();

        assertThat(shortened.original().toString()).isEqualTo("https://s.azndev.com/code1");
        assertThat(shortened.preview().toString()).isEqualTo("https://s.azndev.com/code2");
    }

    @Test
    void missingConfigurationLeavesCallerFreeToUseProxyFallback() {
        DwzShortLinkService service = new DwzShortLinkService(
                "", "", "", "", Duration.ofMinutes(55),
                new OkHttpClient(), new ObjectMapper(), Clock.systemUTC());
        ImageProxyLinks proxy = new ImageProxyLinks(
                URI.create("https://translate.azndev.com/i/0123456789abcdefghij-_"),
                URI.create("https://translate.azndev.com/i/0123456789abcdefghij-_/preview"));

        assertThat(service.shorten(proxy)).isEmpty();
    }

    @Test
    void shortensSignedMinioImageWithoutConfiguredShortDomain() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/short_links", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertThat(body)
                    .contains("\"original_url\":\"https://s3.azndev.com/line-bot/translated-images/")
                    .contains("X-Amz-Signature")
                    .doesNotContain("\"domain\"");
            byte[] response = ("{\"code\":0,\"data\":{"
                    + "\"short_url\":\"https://s.azndev.com/image\"}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        DwzShortLinkService service = new DwzShortLinkService(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-token", "", "", Duration.ofMinutes(55),
                new OkHttpClient.Builder().followRedirects(false).build(),
                new ObjectMapper(), Clock.systemUTC());
        URI signed = URI.create("https://s3.azndev.com/line-bot/translated-images/1/image.png"
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=test%2F20260812%2Fus-east-1%2Fs3%2Faws4_request"
                + "&X-Amz-Date=20260812T071753Z&X-Amz-Expires=3600"
                + "&X-Amz-SignedHeaders=host&X-Amz-Signature=" + "a".repeat(64));

        assertThat(service.shortenSignedImage(signed))
                .contains(URI.create("https://s.azndev.com/image"));
    }
}
