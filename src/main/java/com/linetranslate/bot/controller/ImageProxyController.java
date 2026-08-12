package com.linetranslate.bot.controller;

import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.linetranslate.bot.service.imageproxy.ImageProxyAsset;
import com.linetranslate.bot.service.imageproxy.ImageProxyContentService;

@RestController
@RequestMapping("/i")
public class ImageProxyController {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final CacheControl CACHE = CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePrivate();

    private final ImageProxyContentService contentService;

    public ImageProxyController(ImageProxyContentService contentService) {
        this.contentService = contentService;
    }

    @GetMapping("/{token}")
    public ResponseEntity<byte[]> original(@PathVariable String token) {
        return response(token, false);
    }

    @GetMapping("/{token}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable String token) {
        return response(token, true);
    }

    private ResponseEntity<byte[]> response(String token, boolean preview) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return ResponseEntity.notFound().build();
        }
        Optional<ImageProxyAsset> asset = contentService.load(token);
        if (asset.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        byte[] body = preview ? asset.get().preview() : asset.get().original();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CACHE)
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(body);
    }
}
