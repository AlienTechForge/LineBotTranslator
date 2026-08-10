package com.linetranslate.bot.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.config.GeminiConfig;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.linetranslate.bot.model.UserProfile;

@Service
@Slf4j
public class GeminiService implements AiService {

    private static final Set<String> BLOCKED_FINISH_REASONS = Set.of(
            "SAFETY",
            "RECITATION",
            "LANGUAGE",
            "BLOCKLIST",
            "PROHIBITED_CONTENT",
            "SPII",
            "IMAGE_SAFETY");

    private final String modelName;
    private final String apiKey;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GeminiConfig geminiConfig;

    @Autowired
    public GeminiService(GeminiConfig geminiConfig) {
        this.geminiConfig = geminiConfig;
        this.modelName = geminiConfig.getModelName();
        this.apiKey = geminiConfig.getApiKey();

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();

        log.info("Gemini 服務初始化成功，使用模型: {}", modelName);
    }

    @Override
    public String translateText(String text, String targetLanguage) {
        String prompt = "請將以下文本翻譯成" + targetLanguage
                + "。只需返回翻譯結果，不要添加任何解釋或額外信息：\n\n" + text;
        ObjectNode requestBody = textRequest(prompt, 0.2, 40, 1024);
        return executeRequest(requestBody, UUID.randomUUID().toString());
    }

    @Override
    public String processImage(String prompt, String imageUrl) {
        ObjectNode requestBody = textRequest(prompt, 0.1, 32, 1024);
        ArrayNode parts = (ArrayNode) requestBody.path("contents").get(0).path("parts");
        ObjectNode inlineData = parts.addObject().putObject("inlineData");
        String base64Data = imageUrl.contains(";base64,")
                ? imageUrl.substring(imageUrl.indexOf(";base64,") + ";base64,".length())
                : imageUrl;
        inlineData.put("data", base64Data);
        inlineData.put("mimeType", "image/jpeg");
        return executeRequest(requestBody, UUID.randomUUID().toString());
    }

    @Override
    public String getProviderName() {
        return "gemini";
    }

    @Override
    public String getModelName() {
        return modelName;
    }
    
    /**
     * 根據用戶資料取得模型名稱
     * 
     * @param userProfile 用戶資料
     * @return 模型名稱
     */
    public String getModelName(UserProfile userProfile) {
        // 如果用戶有指定 Gemini 模型，則使用用戶指定的模型
        String userModel = userProfile.getGeminiPreferredModel();
        if (userModel != null && !userModel.isEmpty() && geminiConfig.getAvailableModels().contains(userModel)) {
            return userModel;
        }
        return geminiConfig.getModelName();
    }

    @Override
    public String generateText(String prompt) {
        return executeRequest(
                textRequest(prompt, 0.7, 40, 1024),
                UUID.randomUUID().toString());
    }

    private ObjectNode textRequest(
            String prompt,
            double temperature,
            int topK,
            int maxOutputTokens) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        ArrayNode contents = requestBody.putArray("contents");
        ArrayNode parts = contents.addObject().putArray("parts");
        parts.addObject().put("text", prompt);

        ObjectNode generationConfig = requestBody.putObject("generationConfig");
        generationConfig.put("temperature", temperature);
        generationConfig.put("topK", topK);
        generationConfig.put("topP", 0.95);
        generationConfig.put("maxOutputTokens", maxOutputTokens);
        return requestBody;
    }

    private String executeRequest(ObjectNode requestBodyJson, String correlationId) {
        try {
            String requestBody = objectMapper.writeValueAsString(requestBodyJson);
            Request request = new Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/"
                            + modelName + ":generateContent?key=" + apiKey)
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody body = response.body();
                String responseBody = body == null ? "" : body.string();
                if (!response.isSuccessful()) {
                    throw providerHttpFailure(response.code(), responseBody, correlationId);
                }
                if (responseBody.isBlank()) {
                    throw providerFailure(
                            AiProviderException.Outcome.EMPTY_RESPONSE,
                            "EMPTY_HTTP_BODY",
                            correlationId,
                            response.code(),
                            null);
                }
                return parseGeneratedText(responseBody, correlationId);
            }
        } catch (AiProviderException exception) {
            throw exception;
        } catch (SocketTimeoutException exception) {
            throw providerFailure(
                    AiProviderException.Outcome.TIMEOUT,
                    "SOCKET_TIMEOUT",
                    correlationId,
                    -1,
                    exception);
        } catch (IOException exception) {
            throw providerFailure(
                    AiProviderException.Outcome.TRANSPORT_ERROR,
                    exception.getClass().getSimpleName(),
                    correlationId,
                    -1,
                    exception);
        } catch (RuntimeException exception) {
            throw providerFailure(
                    AiProviderException.Outcome.UNEXPECTED_ERROR,
                    exception.getClass().getSimpleName(),
                    correlationId,
                    -1,
                    exception);
        }
    }

    AiProviderException providerHttpFailure(
            int httpStatus,
            String responseBody,
            String correlationId) {
        String providerReason = readProviderErrorReason(responseBody);
        AiProviderException.Outcome outcome;
        if (httpStatus == 401 || httpStatus == 403) {
            outcome = AiProviderException.Outcome.AUTHENTICATION_FAILED;
        } else if (httpStatus == 408 || httpStatus == 504) {
            outcome = AiProviderException.Outcome.TIMEOUT;
        } else if (httpStatus == 429 && "RESOURCE_EXHAUSTED".equals(providerReason)) {
            outcome = AiProviderException.Outcome.QUOTA_EXCEEDED;
        } else if (httpStatus == 429) {
            outcome = AiProviderException.Outcome.RATE_LIMITED;
        } else {
            outcome = AiProviderException.Outcome.HTTP_ERROR;
        }
        String reason = "UNKNOWN".equals(providerReason)
                ? "HTTP_" + httpStatus
                : providerReason;
        return providerFailure(outcome, reason, correlationId, httpStatus, null);
    }

    private String readProviderErrorReason(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "UNKNOWN";
        }
        try {
            String status = objectMapper.readTree(responseBody)
                    .path("error")
                    .path("status")
                    .asText("");
            return status.isBlank() ? "UNKNOWN" : status;
        } catch (JsonProcessingException ignored) {
            return "UNKNOWN";
        }
    }

    String parseGeneratedText(String responseBody, String correlationId) {
        try {
            return extractGeneratedText(objectMapper.readTree(responseBody), correlationId);
        } catch (JsonProcessingException exception) {
            throw providerFailure(
                    AiProviderException.Outcome.MALFORMED_RESPONSE,
                    "INVALID_JSON",
                    correlationId,
                    -1,
                    exception);
        }
    }

    private String extractGeneratedText(JsonNode response, String correlationId) {
        String promptBlockReason = response.path("promptFeedback").path("blockReason").asText("");
        if (!promptBlockReason.isBlank()
                && !"BLOCK_REASON_UNSPECIFIED".equals(promptBlockReason)) {
            throw providerFailure(
                    AiProviderException.Outcome.SAFETY_BLOCKED,
                    promptBlockReason,
                    correlationId,
                    -1,
                    null);
        }

        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw providerFailure(
                    AiProviderException.Outcome.EMPTY_RESPONSE,
                    "NO_CANDIDATES",
                    correlationId,
                    -1,
                    null);
        }

        JsonNode candidate = candidates.get(0);
        String finishReason = candidate.path("finishReason").asText("");
        if (BLOCKED_FINISH_REASONS.contains(finishReason)) {
            throw providerFailure(
                    AiProviderException.Outcome.SAFETY_BLOCKED,
                    finishReason,
                    correlationId,
                    -1,
                    null);
        }

        JsonNode parts = candidate.path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw providerFailure(
                    AiProviderException.Outcome.EMPTY_RESPONSE,
                    finishReason.isBlank() ? "NO_TEXT_PARTS" : finishReason,
                    correlationId,
                    -1,
                    null);
        }

        String generatedText = parts.get(0).path("text").asText("").trim();
        if (generatedText.isEmpty()) {
            throw providerFailure(
                    AiProviderException.Outcome.EMPTY_RESPONSE,
                    finishReason.isBlank() ? "BLANK_TEXT" : finishReason,
                    correlationId,
                    -1,
                    null);
        }
        return generatedText;
    }

    private AiProviderException providerFailure(
            AiProviderException.Outcome outcome,
            String reason,
            String correlationId,
            int httpStatus,
            Throwable cause) {
        return new AiProviderException(
                outcome,
                getProviderName(),
                modelName,
                reason,
                correlationId,
                httpStatus,
                cause);
    }

}
