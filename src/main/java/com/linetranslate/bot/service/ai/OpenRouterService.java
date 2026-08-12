package com.linetranslate.bot.service.ai;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linetranslate.bot.config.OpenRouterConfig;
import com.linetranslate.bot.service.translation.StructuredImageTranslationCodec;
import com.linetranslate.bot.service.translation.TranslationPromptFactory;
import com.linetranslate.bot.service.translation.TranslationStylePreset;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** OpenRouter-only Adapter for text translation, generation and image understanding. */
@Service
public class OpenRouterService implements AiProviderAdapter {

    private static final MediaType JSON = MediaType.get("application/json");
    private static final String GENERATION_INSTRUCTIONS =
            "你是專業語言助手。依照使用者提示簡潔回應。";

    private final OpenRouterConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AiModelCatalog modelCatalog;
    private final TranslationPromptFactory promptFactory;

    @Autowired
    public OpenRouterService(
            OpenRouterConfig config,
            @Qualifier("openRouterHttpClient") OkHttpClient httpClient,
            ObjectMapper objectMapper,
            AiModelCatalog modelCatalog,
            TranslationPromptFactory promptFactory) {
        this.config = config;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.modelCatalog = modelCatalog;
        this.promptFactory = promptFactory;
    }

    public OpenRouterService(
            OpenRouterConfig config,
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            AiModelCatalog modelCatalog) {
        this(config, httpClient, objectMapper, modelCatalog, new TranslationPromptFactory());
    }

    @Override
    public String providerName() {
        return "openrouter";
    }

    @Override
    public String defaultModel() {
        return config.getModelName();
    }

    @Override
    public Set<String> availableModels() {
        return modelCatalog.modelIds();
    }

    @Override
    public Set<AiProviderOperation> capabilities() {
        return Set.of(AiProviderOperation.values());
    }

    @Override
    public boolean supports(AiProviderRequest request) {
        return request != null && modelCatalog.supports(request.model(), request.operation());
    }

    @Override
    public AiProviderResponse execute(AiProviderRequest providerRequest) {
        String correlationId = UUID.randomUUID().toString();
        requireConfigured(providerRequest.model(), correlationId);
        try {
            Request request = httpRequest(providerRequest);
            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody body = response.body();
                String json = body == null ? "" : body.string();
                if (!response.isSuccessful()) {
                    throw httpFailure(response.code(), json, providerRequest.model(), correlationId);
                }
                if (json.isBlank()) {
                    throw failure(AiProviderException.Outcome.EMPTY_RESPONSE,
                            "EMPTY_HTTP_BODY", providerRequest.model(), correlationId, response.code(), null);
                }
                return parseResponse(json, providerRequest.model(), correlationId);
            }
        } catch (AiProviderException failure) {
            throw failure;
        } catch (SocketTimeoutException failure) {
            throw failure(AiProviderException.Outcome.TIMEOUT,
                    "SOCKET_TIMEOUT", providerRequest.model(), correlationId, -1, failure);
        } catch (IOException failure) {
            throw failure(AiProviderException.Outcome.TRANSPORT_ERROR,
                    "IO_FAILURE", providerRequest.model(), correlationId, -1, failure);
        } catch (RuntimeException failure) {
            throw failure(AiProviderException.Outcome.UNEXPECTED_ERROR,
                    failure.getClass().getSimpleName(), providerRequest.model(), correlationId, -1, failure);
        }
    }

    private Request httpRequest(AiProviderRequest request) throws JsonProcessingException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());
        ArrayNode messages = body.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", instructions(request));
        ObjectNode user = messages.addObject().put("role", "user");
        if (request.operation() == AiProviderOperation.PROCESS_IMAGE) {
            ArrayNode content = user.putArray("content");
            content.addObject().put("type", "text").put("text", request.input());
            content.addObject()
                    .put("type", "image_url")
                    .putObject("image_url")
                    .put("url", request.imageData());
            body.put("max_tokens", 1024);
            body.put("temperature", 0.1);
        } else {
            user.put("content", request.input());
            body.put("temperature", request.operation() == AiProviderOperation.TRANSLATE_TEXT ? 0.2 : 0.7);
            if (isStructuredImageTranslation(request)) {
                body.set("response_format", structuredResponseFormat());
                body.put("temperature", 0);
                body.put("max_tokens", 4096);
                if (supportsReasoningEffort(request.model())) {
                    body.putObject("reasoning")
                            .put("effort", "minimal")
                            .put("exclude", true);
                }
            }
        }

        Request.Builder builder = new Request.Builder()
                .url(config.normalizedApiUrl() + "/chat/completions")
                .post(RequestBody.create(objectMapper.writeValueAsBytes(body), JSON))
                .header("Authorization", "Bearer " + config.getApiKey().trim())
                .header("Content-Type", "application/json");
        optionalHeader(builder, "HTTP-Referer", config.getHttpReferer());
        optionalHeader(builder, "X-OpenRouter-Title", config.getAppTitle());
        return builder.build();
    }

    private String instructions(AiProviderRequest request) {
        return switch (request.operation()) {
            case TRANSLATE_TEXT -> isStructuredImageTranslation(request)
                    ? promptFactory.image(request.targetLanguage(), style(request))
                    : promptFactory.text(request.targetLanguage(), style(request));
            case PROCESS_IMAGE -> promptFactory.ocr();
            case GENERATE_TEXT -> GENERATION_INSTRUCTIONS;
        };
    }

    private static TranslationStylePreset style(AiProviderRequest request) {
        return TranslationStylePreset.find(request.translationStyleId())
                .orElse(TranslationStylePreset.defaultPreset());
    }

    private static boolean isStructuredImageTranslation(AiProviderRequest request) {
        return request.operation() == AiProviderOperation.TRANSLATE_TEXT
                && request.input().contains("\"schemaVersion\":\"" + StructuredImageTranslationCodec.SCHEMA_VERSION + "\"");
    }

    private static boolean supportsReasoningEffort(String model) {
        String normalized = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return normalized.startsWith("openai/gpt-5")
                || normalized.startsWith("openai/o1")
                || normalized.startsWith("openai/o3")
                || normalized.startsWith("openai/o4")
                || normalized.startsWith("x-ai/grok");
    }

    private ObjectNode structuredResponseFormat() {
        ObjectNode format = objectMapper.createObjectNode();
        format.put("type", "json_schema");
        ObjectNode wrapper = format.putObject("json_schema");
        wrapper.put("name", "image_region_translations");
        wrapper.put("strict", true);
        ObjectNode schema = wrapper.putObject("schema");
        schema.put("type", "object");
        schema.putArray("required").add("schemaVersion").add("regions");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("schemaVersion").put("type", "string")
                .put("const", StructuredImageTranslationCodec.SCHEMA_VERSION);
        ObjectNode regions = properties.putObject("regions");
        regions.put("type", "array");
        ObjectNode item = regions.putObject("items");
        item.put("type", "object");
        item.putArray("required").add("regionId").add("translatedText");
        item.put("additionalProperties", false);
        ObjectNode itemProperties = item.putObject("properties");
        itemProperties.putObject("regionId").put("type", "string");
        itemProperties.putObject("translatedText").put("type", "string").put("maxLength", 4000);
        return format;
    }

    private AiProviderResponse parseResponse(
            String json,
            String requestedModel,
            String correlationId) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode rootError = root.path("error");
            if (!rootError.isMissingNode() && !rootError.isNull()) {
                throw embeddedFailure(rootError, requestedModel, correlationId);
            }
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw failure(AiProviderException.Outcome.EMPTY_RESPONSE,
                        "NO_CHOICES", requestedModel, correlationId, 200, null);
            }
            JsonNode choice = choices.get(0);
            JsonNode choiceError = choice.path("error");
            if (!choiceError.isMissingNode() && !choiceError.isNull()) {
                throw embeddedFailure(choiceError, requestedModel, correlationId);
            }
            String text = contentText(choice.path("message").path("content")).trim();
            if (text.isEmpty()) {
                throw failure(AiProviderException.Outcome.EMPTY_RESPONSE,
                        "NO_MESSAGE_CONTENT", requestedModel, correlationId, 200, null);
            }
            JsonNode usage = root.path("usage");
            AiTokenUsage tokenUsage = usage.isMissingNode()
                    ? AiTokenUsage.UNKNOWN
                    : new AiTokenUsage(
                            usage.path("prompt_tokens").asLong(-1),
                            usage.path("completion_tokens").asLong(-1),
                            usage.path("total_tokens").asLong(-1));
            return new AiProviderResponse(
                    text,
                    root.path("model").asText(requestedModel),
                    tokenUsage);
        } catch (AiProviderException failure) {
            throw failure;
        } catch (JsonProcessingException failure) {
            throw failure(AiProviderException.Outcome.MALFORMED_RESPONSE,
                    "INVALID_JSON", requestedModel, correlationId, 200, failure);
        }
    }

    private String contentText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return "";
        }
        StringBuilder value = new StringBuilder();
        for (JsonNode part : content) {
            if ("text".equals(part.path("type").asText()) && part.path("text").isTextual()) {
                value.append(part.path("text").asText());
            }
        }
        return value.toString();
    }

    private AiProviderException httpFailure(
            int status,
            String json,
            String model,
            String correlationId) {
        String errorType = errorType(json);
        return failure(mapOutcome(status, errorType),
                errorType.isBlank() ? "HTTP_" + status : errorType,
                model,
                correlationId,
                status,
                null);
    }

    private AiProviderException embeddedFailure(
            JsonNode error,
            String model,
            String correlationId) {
        int status = error.path("code").canConvertToInt() ? error.path("code").asInt() : 200;
        String errorType = error.path("metadata").path("error_type").asText("");
        return failure(mapOutcome(status, errorType),
                errorType.isBlank() ? "PROVIDER_ERROR" : errorType,
                model,
                correlationId,
                status,
                null);
    }

    private String errorType(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonNode error = objectMapper.readTree(json).path("error");
            String type = error.path("metadata").path("error_type").asText("");
            return type.isBlank() ? error.path("type").asText("") : type;
        } catch (JsonProcessingException ignored) {
            return "";
        }
    }

    private static AiProviderException.Outcome mapOutcome(int status, String errorType) {
        String type = errorType == null ? "" : errorType.toLowerCase(Locale.ROOT);
        if (type.contains("content_policy") || type.contains("moderation")) {
            return AiProviderException.Outcome.SAFETY_BLOCKED;
        }
        if (status == 401 || "authentication".equals(type)) {
            return AiProviderException.Outcome.AUTHENTICATION_FAILED;
        }
        if (status == 402 || "payment_required".equals(type) || type.contains("token_limit")) {
            return AiProviderException.Outcome.QUOTA_EXCEEDED;
        }
        if (status == 408 || status == 504 || "timeout".equals(type)) {
            return AiProviderException.Outcome.TIMEOUT;
        }
        if (status == 429 || "rate_limit_exceeded".equals(type)) {
            return AiProviderException.Outcome.RATE_LIMITED;
        }
        return AiProviderException.Outcome.HTTP_ERROR;
    }

    private void requireConfigured(String model, String correlationId) {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw failure(AiProviderException.Outcome.CONFIGURATION_ERROR,
                    "API_KEY_UNAVAILABLE", model, correlationId, -1, null);
        }
    }

    private AiProviderException failure(
            AiProviderException.Outcome outcome,
            String reason,
            String model,
            String correlationId,
            int httpStatus,
            Throwable cause) {
        return new AiProviderException(
                outcome,
                providerName(),
                model,
                reason,
                correlationId,
                httpStatus,
                cause);
    }

    private static void optionalHeader(Request.Builder builder, String name, String value) {
        if (value != null && !value.isBlank()) {
            builder.header(name, value.trim());
        }
    }
}
