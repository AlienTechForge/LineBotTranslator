package com.linetranslate.bot.testing;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.linetranslate.bot.service.imagetranslator.ImageTranslatorResult;

/**
 * Documents of the image-translator service ({@code services/image-translator}, {@code /v1}) as the service
 * writes them, and pictures to send through it. The JSON is the shape of the service's README, field for field.
 */
public final class ImageTranslatorFixtures {

    public static final String TOKEN = "test-service-token";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ImageTranslatorFixtures() {
    }

    /** A 200 answer with {@code status: image}: the picture is {@code /v1/translations/<id>/image.png}. */
    public static String imageJson(String requestId, String tier, String reason, String mode, int imageBytes) {
        return """
                {
                  "request_id": "%1$s",
                  "schema_version": 1,
                  "target_lang": "zh-TW",
                  "status": "image",
                  "tier": "%2$s",
                  "reason": "%3$s",
                  "mode": "%4$s",
                  "verdict": "%5$s",
                  "usability": 0.82,
                  "coverage": 0.9,
                  "image": {"path": "/v1/translations/%1$s/image.png", "width": 640, "height": 480, "bytes": %6$d},
                  "text": {
                    "source_language": "en",
                    "recognized_text": "TONKATSU SET MENU NT$120",
                    "translated_text": "炸豬排套餐\\nNT$120"
                  },
                  "judge": {"summary": "Looks right.", "altered_numbers": [], "untranslated_major": [], "legibility_major": []},
                  "costs": {"anthropic_usd": 0.0123, "openrouter_usd": 0.04, "total_usd": 0.0523},
                  "budget": {"month": "2026-10", "openrouter_spent_usd": 1.0, "openrouter_budget_usd": 15.0,
                             "anthropic_spent_usd": 2.0, "anthropic_budget_usd": 80.0},
                  "timings_ms": {"ocr": 9, "mode_a": 97, "mode_b": null, "judge": 0, "total": 118},
                  "attempts": [{"mode": "%4$s", "verdict": "%5$s", "usability": 0.82, "cost_usd": 0.0523,
                                "elapsed_ms": 97, "error": null, "skipped": null, "selected": true}],
                  "warnings": ["a note"],
                  "something_new": {"the": "client ignores it"}
                }
                """.formatted(requestId, tier, reason, mode, "clean".equals(tier) ? "pass" : "numbers+score", imageBytes);
    }

    /** A 200 answer with {@code status: text_only}; {@code translated} may be null (the planner never answered). */
    public static String textOnlyJson(String requestId, String translated) {
        String text = translated == null ? "null" : "\"" + translated + "\"";
        return """
                {
                  "request_id": "%1$s", "schema_version": 1, "target_lang": "zh-TW",
                  "status": "text_only", "tier": null, "reason": "below_threshold", "mode": "B",
                  "verdict": "fail", "usability": 0.31, "coverage": 0.5,
                  "image": null,
                  "text": {"source_language": "ja", "recognized_text": "こんにちは", "translated_text": %2$s},
                  "judge": null,
                  "costs": {"anthropic_usd": 0.02, "openrouter_usd": 0.05, "total_usd": 0.07},
                  "attempts": [], "warnings": []
                }
                """.formatted(requestId, text);
    }

    public static String noTextJson(String requestId) {
        return """
                {
                  "request_id": "%s", "schema_version": 1, "target_lang": "zh-TW",
                  "status": "no_text", "tier": null, "reason": "no_text", "mode": null,
                  "verdict": null, "usability": null, "coverage": null, "image": null,
                  "text": {"source_language": null, "recognized_text": "", "translated_text": null},
                  "judge": null, "costs": {"anthropic_usd": 0.0, "openrouter_usd": 0.0, "total_usd": 0.0},
                  "attempts": [], "warnings": []
                }
                """.formatted(requestId);
    }

    /** A 200 answer with {@code status: error}: the request was fine, the service could not make anything. */
    public static String errorJson(String requestId, String reason) {
        return """
                {
                  "request_id": "%s", "schema_version": 1, "target_lang": "zh-TW",
                  "status": "error", "tier": null, "reason": "%s", "mode": null,
                  "verdict": null, "usability": null, "coverage": null, "image": null,
                  "text": {"source_language": null, "recognized_text": "", "translated_text": null},
                  "judge": null, "costs": {"anthropic_usd": 0.0, "openrouter_usd": 0.0, "total_usd": 0.0},
                  "attempts": [], "warnings": []
                }
                """.formatted(requestId, reason);
    }

    /** The body of an HTTP error of {@code /v1}. */
    public static String httpErrorJson(String reason, String detail) {
        return """
                {"status": "error", "reason": "%s", "detail": "%s", "schema_version": 1}
                """.formatted(reason, detail);
    }

    public static String healthJson() {
        return """
                {"status": "ok", "mock": false, "version": "1.0.0",
                 "budget": {"month": "2026-10", "openrouter_spent_usd": 1.5, "openrouter_budget_usd": 15.0,
                            "anthropic_spent_usd": 2.5, "anthropic_budget_usd": 80.0}}
                """;
    }

    /** The document parsed as the client parses it. */
    public static ImageTranslatorResult parse(String json) {
        try {
            return MAPPER.readValue(json, ImageTranslatorResult.class);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public static ImageTranslatorResult imageResult(String requestId, String tier, String mode) {
        return parse(imageJson(requestId, tier, "clean".equals(tier) ? "a_pass" : "a_better", mode, 1234));
    }

    /** A small opaque PNG of one flat colour. */
    public static byte[] png(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return encode(image);
    }

    /** An opaque PNG of random pixels, so that it is large and JPEG does not make it small. */
    public static byte[] noisyPng(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        return encode(image);
    }

    /** A smooth opaque gradient: a big PNG that JPEG makes much smaller. */
    public static byte[] gradientPng(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(11);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int base = (x * 255 / width) << 16 | (y * 255 / height) << 8 | 128;
                image.setRGB(x, y, base ^ random.nextInt(4));
            }
        }
        return encode(image);
    }

    /** A PNG with an alpha channel. */
    public static byte[] transparentPng(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(13);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        return encode(image);
    }

    private static byte[] encode(BufferedImage image) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
