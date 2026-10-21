package com.linetranslate.bot.service.imagetranslator;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Where the image-translator service listens and how long the bot waits for it. The service runs in
 * the same container, so the defaults point at the loopback address it binds to; the token is the
 * shared secret of its {@code X-Service-Token} header. A blank URL or an empty timeout falls back
 * to the default, so an empty environment variable never switches the client off by accident.
 */
@Component
@ConfigurationProperties(prefix = "app.image-translator")
public class ImageTranslatorProperties {

    public static final String DEFAULT_URL = "http://127.0.0.1:4041";

    /** The service's own deadline is 210 s; the bot waits a little longer than that. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(4);

    private boolean enabled = true;
    private String url = DEFAULT_URL;
    private String token = "";
    private Duration timeout = DEFAULT_TIMEOUT;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url == null || url.isBlank() ? DEFAULT_URL : url.trim();
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token == null ? "" : token.trim();
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        if (timeout == null) {
            this.timeout = DEFAULT_TIMEOUT;
            return;
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("The image-translator timeout must be positive");
        }
        this.timeout = timeout;
    }

    /** The service URL without a trailing slash, ready to be followed by a path that starts with one. */
    public String baseUrl() {
        return url.replaceFirst("/+$", "");
    }

    public boolean hasToken() {
        return !token.isBlank();
    }

    /** Never prints the token. */
    @Override
    public String toString() {
        return "ImageTranslatorProperties[enabled=" + enabled + ", url=" + url
                + ", token=" + (hasToken() ? "set" : "unset") + ", timeout=" + timeout + "]";
    }
}
