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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.linetranslate.bot.model.UserProfile;

@Service
@Slf4j
public class GeminiService implements AiProviderAdapter {

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
        this(
                geminiConfig,
                new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build(),
                new ObjectMapper());
    }

    GeminiService(
            GeminiConfig geminiConfig,
            OkHttpClient httpClient,
            ObjectMapper objectMapper) {
        this.geminiConfig = geminiConfig;
        this.modelName = geminiConfig.getModelName();
        this.apiKey = geminiConfig.getApiKey();
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;

        log.info("Gemini 服務初始化成功，使用模型: {}", modelName);
    }

    public String translateText(String text, String targetLanguage) {
        return execute(AiProviderRequest.translate(modelName, text, targetLanguage)).text();
    }

    public String processImage(String prompt, String imageUrl) {
        return execute(AiProviderRequest.image(modelName, prompt, imageUrl)).text();
    }

    @Override
    public AiProviderResponse execute(AiProviderRequest request) {
        ObjectNode requestBody = switch (request.operation()) {
            case TRANSLATE_TEXT -> textRequest(
                    "請將以下文本翻譯成" + request.targetLanguage()
                            + "。只需返回翻譯結果，不要添加任何解釋或額外信息：\n\n"
                            + request.input(),
                    0.2,
                    40,
                    1024);
            case PROCESS_IMAGE -> imageRequest(request.input(), request.imageData());
            case GENERATE_TEXT -> textRequest(request.input(), 0.7, 40, 1024);
        };
        return executeRequest(requestBody, UUID.randomUUID().toString(), request.model());
    }

    @Override
    public String providerName() {
        return "gemini";
    }

    @Override
    public String defaultModel() {
        return modelName;
    }

    @Override
    public Set<String> availableModels() {
        List<String> configuredModels = geminiConfig.getAvailableModels();
        if (configuredModels == null || configuredModels.isEmpty()) {
            return Set.of(modelName);
        }
        return configuredModels.stream()
                .filter(model -> model != null && !model.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<AiProviderOperation> capabilities() {
        return Set.of(AiProviderOperation.values());
    }

    public String getProviderName() {
        return providerName();
    }

    public String getModelName() {
        return defaultModel();
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

    public String generateText(String prompt) {
        return execute(AiProviderRequest.generate(modelName, prompt)).text();
    }

    private ObjectNode imageRequest(String prompt, String imageUrl) {
        ObjectNode requestBody = textRequest(prompt, 0.1, 32, 1024);
        ArrayNode parts = (ArrayNode) requestBody.path("contents").get(0).path("parts");
        ObjectNode inlineData = parts.addObject().putObject("inlineData");
        String base64Data = imageUrl.contains(";base64,")
                ? imageUrl.substring(imageUrl.indexOf(";base64,") + ";base64,".length())
                : imageUrl;
        inlineData.put("data", base64Data);
        inlineData.put("mimeType", "image/jpeg");
        return requestBody;
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

    private AiProviderResponse executeRequest(
            ObjectNode requestBodyJson,
            String correlationId,
            String requestedModel) {
        try {
            String requestBody = objectMapper.writeValueAsString(requestBodyJson);
            Request request = new Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/"
                            + requestedModel + ":generateContent?key=" + apiKey)
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody body = response.body();
                String responseBody = body == null ? "" : body.string();
                if (!response.isSuccessful()) {
                    throw providerHttpFailure(response.code(), responseBody, correlationId, requestedModel);
                }
                if (responseBody.isBlank()) {
                    throw providerFailure(
                            AiProviderException.Outcome.EMPTY_RESPONSE,
                            "EMPTY_HTTP_BODY",
                            correlationId,
                            response.code(),
                            null,
                            requestedModel);
                }
                return parseProviderResponse(responseBody, correlationId, requestedModel);
            }
        } catch (AiProviderException exception) {
            throw exception;
        } catch (SocketTimeoutException exception) {
            throw providerFailure(
                    AiProviderException.Outcome.TIMEOUT,
                    "SOCKET_TIMEOUT",
                    correlationId,
                    -1,
                    exception,
                    requestedModel);
        } catch (IOException exception) {
            throw providerFailure(
                    AiProviderException.Outcome.TRANSPORT_ERROR,
                    exception.getClass().getSimpleName(),
                    correlationId,
                    -1,
                    exception,
                    requestedModel);
        } catch (RuntimeException exception) {
            throw providerFailure(
                    AiProviderException.Outcome.UNEXPECTED_ERROR,
                    exception.getClass().getSimpleName(),
                    correlationId,
                    -1,
                    exception,
                    requestedModel);
        }
    }

    AiProviderException providerHttpFailure(
            int httpStatus,
            String responseBody,
            String correlationId) {
        return providerHttpFailure(httpStatus, responseBody, correlationId, modelName);
    }

    private AiProviderException providerHttpFailure(
            int httpStatus,
            String responseBody,
            String correlationId,
            String requestedModel) {
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
        return providerFailure(outcome, reason, correlationId, httpStatus, null, requestedModel);
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
        return parseProviderResponse(responseBody, correlationId, modelName).text();
    }

    private AiProviderResponse parseProviderResponse(
            String responseBody,
            String correlationId,
            String requestedModel) {
        try {
            return extractProviderResponse(objectMapper.readTree(responseBody), correlationId, requestedModel);
        } catch (JsonProcessingException exception) {
            throw providerFailure(
                    AiProviderException.Outcome.MALFORMED_RESPONSE,
                    "INVALID_JSON",
                    correlationId,
                    -1,
                    exception,
                    requestedModel);
        }
    }

    private AiProviderResponse extractProviderResponse(
            JsonNode response,
            String correlationId,
            String requestedModel) {
        String promptBlockReason = response.path("promptFeedback").path("blockReason").asText("");
        if (!promptBlockReason.isBlank()
                && !"BLOCK_REASON_UNSPECIFIED".equals(promptBlockReason)) {
            throw providerFailure(
                    AiProviderException.Outcome.SAFETY_BLOCKED,
                    promptBlockReason,
                    correlationId,
                    -1,
                    null,
                    requestedModel);
        }

        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw providerFailure(
                    AiProviderException.Outcome.EMPTY_RESPONSE,
                    "NO_CANDIDATES",
                    correlationId,
                    -1,
                    null,
                    requestedModel);
        }

        JsonNode candidate = candidates.get(0);
        String finishReason = candidate.path("finishReason").asText("");
        if (BLOCKED_FINISH_REASONS.contains(finishReason)) {
            throw providerFailure(
                    AiProviderException.Outcome.SAFETY_BLOCKED,
                    finishReason,
                    correlationId,
                    -1,
                    null,
                    requestedModel);
        }

        JsonNode parts = candidate.path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw providerFailure(
                    AiProviderException.Outcome.EMPTY_RESPONSE,
                    finishReason.isBlank() ? "NO_TEXT_PARTS" : finishReason,
                    correlationId,
                    -1,
                    null,
                    requestedModel);
        }

        String generatedText = parts.get(0).path("text").asText("").trim();
        if (generatedText.isEmpty()) {
            throw providerFailure(
                    AiProviderException.Outcome.EMPTY_RESPONSE,
                    finishReason.isBlank() ? "BLANK_TEXT" : finishReason,
                    correlationId,
                    -1,
                    null,
                    requestedModel);
        }
        String actualModel = response.path("modelVersion").asText(requestedModel);
        JsonNode usage = response.path("usageMetadata");
        AiTokenUsage tokenUsage = usage.isMissingNode()
                ? AiTokenUsage.UNKNOWN
                : new AiTokenUsage(
                        usage.path("promptTokenCount").asLong(-1),
                        usage.path("candidatesTokenCount").asLong(-1),
                        usage.path("totalTokenCount").asLong(-1));
        return new AiProviderResponse(generatedText, actualModel, tokenUsage);
    }

    private AiProviderException providerFailure(
            AiProviderException.Outcome outcome,
            String reason,
            String correlationId,
            int httpStatus,
            Throwable cause,
            String requestedModel) {
        return new AiProviderException(
                outcome,
                providerName(),
                requestedModel,
                reason,
                correlationId,
                httpStatus,
                cause);
    }

}
