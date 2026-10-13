package com.linetranslate.bot.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.linetranslate.bot.config.AdminWebTokenCondition;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Guards everything under {@code /admin/images} except the HTML page itself with the shared admin
 * token. An interceptor runs before argument resolution, so an unauthenticated request is answered
 * with 401 before any body is parsed, and a handler added later is protected without remembering to
 * check. The page is excluded because a browser navigation cannot send an {@code Authorization}
 * header; it is a static shell with no data, and every call it makes carries the token.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(AdminWebTokenCondition.class)
public class AdminImageGalleryWebConfiguration implements WebMvcConfigurer {

    static final String PAGE_PATH = "/admin/images";
    static final String GUARDED_PATTERN = "/admin/images/**";

    private final HandlerInterceptor tokenInterceptor;

    public AdminImageGalleryWebConfiguration(@Value("${app.admin.web.token}") String token) {
        this.tokenInterceptor = new TokenInterceptor(token.strip());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenInterceptor)
                .addPathPatterns(GUARDED_PATTERN)
                .excludePathPatterns(PAGE_PATH);
    }

    /** Compares SHA-256 digests of the token so neither value nor length leaks through timing. */
    @Slf4j
    static final class TokenInterceptor implements HandlerInterceptor {

        private static final String BEARER_PREFIX = "Bearer ";
        private static final String UNAUTHORIZED_BODY = "{\"error\":\"unauthorized\"}";

        private final byte[] expectedDigest;

        TokenInterceptor(String token) {
            this.expectedDigest = sha256(token);
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws IOException {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Referrer-Policy", "no-referrer");
            if (isAuthorized(request.getHeader(HttpHeaders.AUTHORIZATION))) {
                return true;
            }
            log.warn("Admin image gallery rejected an unauthenticated request");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"admin-images\"");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(UNAUTHORIZED_BODY);
            return false;
        }

        private boolean isAuthorized(String authorization) {
            if (authorization == null
                    || authorization.length() <= BEARER_PREFIX.length()
                    || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                return false;
            }
            String presented = authorization.substring(BEARER_PREFIX.length()).strip();
            return MessageDigest.isEqual(sha256(presented), expectedDigest);
        }

        private static byte[] sha256(String value) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }
    }
}
