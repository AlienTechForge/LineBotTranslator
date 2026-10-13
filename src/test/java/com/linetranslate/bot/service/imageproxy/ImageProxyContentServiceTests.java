package com.linetranslate.bot.service.imageproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import okhttp3.OkHttpClient;

class ImageProxyContentServiceTests {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void fetchesValidatedPngAndBuildsPreviewWithinLineLimit() throws Exception {
        byte[] png = noisyPng(400, 400);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            exchange.close();
        });
        server.start();
        URI target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/image");
        ImageProxyLinkService links = mock(ImageProxyLinkService.class);
        when(links.resolve("0123456789abcdefghij-_"))
                .thenReturn(Optional.of(target));
        ImageProxyContentService service = new ImageProxyContentService(
                links,
                new OkHttpClient.Builder().followRedirects(false).build(),
                1_000_000,
                5_000,
                1_024,
                1_000_000,
                Duration.ofMinutes(1),
                10);

        ImageProxyAsset asset = service.load("0123456789abcdefghij-_").orElseThrow();

        assertThat(asset.original()).isEqualTo(png);
        assertThat(asset.preview().length).isLessThanOrEqualTo(5_000);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(asset.preview()))).isNotNull();
    }

    @Test
    void servesJpegOutputAsJpegWithAJpegPreview() throws Exception {
        BufferedImage source = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7);
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 200; x++) {
                source.setRGB(x, y, random.nextInt());
            }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "jpeg", encoded);
        byte[] jpeg = encoded.toByteArray();
        ImageProxyContentService service = serving(jpeg, "image/jpeg");

        ImageProxyAsset asset = service.load("0123456789abcdefghij-_").orElseThrow();

        assertThat(asset.contentType()).isEqualTo("image/jpeg");
        assertThat(asset.original()).isEqualTo(jpeg);
        assertThat(asset.preview().length).isLessThanOrEqualTo(5_000);
        assertThat(asset.preview()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(asset.preview()[1] & 0xFF).isEqualTo(0xD8);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(asset.preview()))).isNotNull();
    }

    @Test
    void refusesUpstreamTypesOtherThanPngAndJpeg() throws Exception {
        ImageProxyContentService service = serving(noisyPng(40, 40), "image/gif");

        assertThat(service.load("0123456789abcdefghij-_")).isEmpty();
    }

    private ImageProxyContentService serving(byte[] body, String contentType) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        URI target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/image");
        ImageProxyLinkService links = mock(ImageProxyLinkService.class);
        when(links.resolve("0123456789abcdefghij-_"))
                .thenReturn(Optional.of(target));
        return new ImageProxyContentService(
                links,
                new OkHttpClient.Builder().followRedirects(false).build(),
                1_000_000, 5_000, 1_024, 1_000_000,
                Duration.ofMinutes(1), 10);
    }

    @Test
    void doesNotFollowUpstreamRedirects() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1/private");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        URI target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect");
        ImageProxyLinkService links = mock(ImageProxyLinkService.class);
        when(links.resolve("0123456789abcdefghij-_"))
                .thenReturn(Optional.of(target));
        ImageProxyContentService service = new ImageProxyContentService(
                links,
                new OkHttpClient.Builder().followRedirects(false).build(),
                1_000_000, 5_000, 1_024, 1_000_000,
                Duration.ofMinutes(1), 10);

        assertThat(service.load("0123456789abcdefghij-_")).isEmpty();
    }

    private static byte[] noisyPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
