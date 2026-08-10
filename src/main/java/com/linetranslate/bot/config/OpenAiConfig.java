package com.linetranslate.bot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Configuration
@Getter
@Slf4j
public class OpenAiConfig {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String apiKey;

    @Value("${openai.model.name:${OPENAI_MODEL_NAME:gpt-4o}}")
    private String modelName;

    @Value("${openai.api.url:${OPENAI_API_URL:https://api.openai.com/v1}}")
    private String apiUrl;

    @Value("${openai.available.models:${OPENAI_AVAILABLE_MODELS:gpt-4o,gpt-3.5-turbo}}")
    private String availableModelsString;

    private List<String> availableModels;

    /**
     * 獲取可用模型列表
     * @return 可用模型列表
     */
    public List<String> getAvailableModels() {
        if (availableModels == null) {
            availableModels = Arrays.asList(availableModelsString.split(","));
        }
        return availableModels;
    }

    @Bean(name = "openAiClient")
    public OpenAIClient openAiClient() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.warn("OpenAI API 金鑰未設置");
            return null;
        }

        log.info("初始化 OpenAI 服務, 使用模型: {}", modelName);
        return OpenAIOkHttpClient.builder()
                .apiKey(apiKey.trim())
                .baseUrl(normalizeBaseUrl(apiUrl))
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    static String normalizeBaseUrl(String configuredUrl) {
        if (configuredUrl == null || configuredUrl.isBlank()) {
            return DEFAULT_BASE_URL;
        }

        String normalized = configuredUrl.trim().replaceFirst("/+$", "");
        String legacyEndpoint = "/chat/completions";
        if (normalized.endsWith(legacyEndpoint)) {
            normalized = normalized.substring(0, normalized.length() - legacyEndpoint.length());
        }
        return normalized;
    }
}
