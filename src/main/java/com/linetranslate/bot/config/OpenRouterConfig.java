package com.linetranslate.bot.config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import okhttp3.OkHttpClient;

@Configuration
@Getter
public class OpenRouterConfig {

    private static final String DEFAULT_API_URL = "https://openrouter.ai/api/v1";

    @Value("${openrouter.api.key:${OPEN_ROUTE_API_KEY:}}")
    private String apiKey;

    @Value("${openrouter.model.name:${OPEN_ROUTE_MODEL_NAME:openai/gpt-4o-mini}}")
    private String modelName;

    @Value("${openrouter.api.url:${OPEN_ROUTE_API_URL:https://openrouter.ai/api/v1}}")
    private String apiUrl;

    @Value("${openrouter.http-referer:${OPEN_ROUTE_HTTP_REFERER:https://github.com/AlienTechForge/LineBotTranslator}}")
    private String httpReferer;

    @Value("${openrouter.app-title:${OPEN_ROUTE_APP_TITLE:LineBotTranslator}}")
    private String appTitle;

    @Value("${openrouter.catalog.ttl:${OPEN_ROUTE_CATALOG_TTL:PT15M}}")
    private Duration catalogTtl;

    @Bean(name = "openRouterHttpClient")
    OkHttpClient openRouterHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public String normalizedApiUrl() {
        String value = apiUrl == null || apiUrl.isBlank() ? DEFAULT_API_URL : apiUrl.trim();
        return value.replaceFirst("/+$", "");
    }
}
