package com.linetranslate.bot.service.ai;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.config.OpenAiConfig;
import com.linetranslate.bot.model.UserProfile;
import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class OpenAiService implements AiService {

    private static final String TRANSLATION_INSTRUCTIONS =
            "你是一個專業的翻譯助手。請將用戶提供的文本翻譯成%s。只需返回翻譯結果，不要添加任何解釋或額外信息。";
    private static final String OCR_INSTRUCTIONS =
            "你是一個專業的OCR助手。請識別並提取圖片中的所有文字。只需返回文字內容，不要添加任何解釋或說明。";
    private static final String GENERATION_INSTRUCTIONS =
            "你是一個專業的語言助手。請根據用戶的提示生成回應。";

    private final OpenAIClient openAiClient;
    private final OpenAiConfig openAiConfig;
    private final String modelName;

    public OpenAiService(OpenAiConfig openAiConfig,
            @Qualifier("openAiClient") ObjectProvider<OpenAIClient> openAiClientProvider) {
        this.openAiClient = openAiClientProvider.getIfAvailable();
        this.openAiConfig = openAiConfig;
        this.modelName = openAiConfig.getModelName();

        if (openAiClient != null) {
            log.info("OpenAI 服務初始化成功，使用模型: {}", modelName);
        } else {
            log.warn("OpenAI 服務初始化失敗，API 金鑰可能未設置");
        }
    }

    @Override
    public String translateText(String text, String targetLanguage) {
        String correlationId = UUID.randomUUID().toString();
        if (openAiClient == null) {
            throw providerFailure(
                    AiProviderException.Outcome.CONFIGURATION_ERROR,
                    "CLIENT_UNAVAILABLE",
                    correlationId,
                    -1,
                    null);
        }

        try {
            ResponseCreateParams params = ResponseCreateParams.builder()
                    .model(modelName)
                    .instructions(TRANSLATION_INSTRUCTIONS.formatted(targetLanguage))
                    .input(text)
                    .temperature(0.3)
                    .build();
            return createResponse(params, correlationId);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw normalizeFailure(exception, correlationId);
        }
    }

    @Override
    public String processImage(String prompt, String imageUrl) {
        String correlationId = UUID.randomUUID().toString();
        if (openAiClient == null) {
            throw providerFailure(
                    AiProviderException.Outcome.CONFIGURATION_ERROR,
                    "CLIENT_UNAVAILABLE",
                    correlationId,
                    -1,
                    null);
        }

        try {
            ResponseInputImage image = ResponseInputImage.builder()
                    .detail(ResponseInputImage.Detail.AUTO)
                    .imageUrl(imageUrl)
                    .build();
            ResponseInputItem.Message message = ResponseInputItem.Message.builder()
                    .role(ResponseInputItem.Message.Role.USER)
                    .addInputTextContent(prompt)
                    .addContent(image)
                    .build();
            ResponseCreateParams params = ResponseCreateParams.builder()
                    .model(modelName)
                    .instructions(OCR_INSTRUCTIONS)
                    .inputOfResponse(List.of(ResponseInputItem.ofMessage(message)))
                    .temperature(0.3)
                    .maxOutputTokens(1024)
                    .build();
            return createResponse(params, correlationId);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw normalizeFailure(exception, correlationId);
        }
    }

    @Override
    public String getProviderName() {
        return "openai";
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    public String getModelName(UserProfile userProfile) {
        String userModel = userProfile.getOpenaiPreferredModel();
        if (userModel != null && !userModel.isEmpty() && openAiConfig.getAvailableModels().contains(userModel)) {
            return userModel;
        }
        return openAiConfig.getModelName();
    }

    @Override
    public String generateText(String prompt) {
        String correlationId = UUID.randomUUID().toString();
        if (openAiClient == null) {
            throw providerFailure(
                    AiProviderException.Outcome.CONFIGURATION_ERROR,
                    "CLIENT_UNAVAILABLE",
                    correlationId,
                    -1,
                    null);
        }

        try {
            ResponseCreateParams params = ResponseCreateParams.builder()
                    .model(modelName)
                    .instructions(GENERATION_INSTRUCTIONS)
                    .input(prompt)
                    .temperature(0.7)
                    .build();
            return createResponse(params, correlationId);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw normalizeFailure(exception, correlationId);
        }
    }

    private String createResponse(ResponseCreateParams params, String correlationId) {
        Response response = openAiClient.responses().create(params);
        String output = response.output().stream()
                .flatMap(item -> item.message().stream())
                .flatMap(message -> message.content().stream())
                .flatMap(content -> content.outputText().stream())
                .map(outputText -> outputText.text())
                .collect(Collectors.joining())
                .trim();
        if (output.isEmpty()) {
            throw providerFailure(
                    AiProviderException.Outcome.EMPTY_RESPONSE,
                    "NO_OUTPUT_TEXT",
                    correlationId,
                    -1,
                    null);
        }
        return output;
    }

    private AiProviderException normalizeFailure(Exception exception, String correlationId) {
        AiProviderException.Outcome outcome = AiProviderException.Outcome.UNEXPECTED_ERROR;
        int httpStatus = -1;
        String reason = exception.getClass().getSimpleName();

        if (hasCause(exception, SocketTimeoutException.class)) {
            outcome = AiProviderException.Outcome.TIMEOUT;
            reason = "SOCKET_TIMEOUT";
        } else if (exception instanceof RateLimitException rateLimitException) {
            httpStatus = rateLimitException.statusCode();
            Optional<String> errorCode = rateLimitException.code();
            String normalizedCode = errorCode == null ? "" : errorCode.orElse("");
            outcome = normalizedCode.toLowerCase(Locale.ROOT).contains("quota")
                    ? AiProviderException.Outcome.QUOTA_EXCEEDED
                    : AiProviderException.Outcome.RATE_LIMITED;
            reason = normalizedCode.isBlank() ? "RATE_LIMIT" : normalizedCode;
        } else if (exception instanceof UnauthorizedException
                || exception instanceof PermissionDeniedException) {
            outcome = AiProviderException.Outcome.AUTHENTICATION_FAILED;
            httpStatus = ((OpenAIServiceException) exception).statusCode();
            reason = "AUTHENTICATION";
        } else if (exception instanceof OpenAIIoException) {
            outcome = AiProviderException.Outcome.TRANSPORT_ERROR;
            reason = "IO_FAILURE";
        } else if (exception instanceof OpenAIInvalidDataException) {
            outcome = AiProviderException.Outcome.MALFORMED_RESPONSE;
            reason = "INVALID_RESPONSE_DATA";
        } else if (exception instanceof OpenAIServiceException serviceException) {
            outcome = AiProviderException.Outcome.HTTP_ERROR;
            httpStatus = serviceException.statusCode();
            reason = "HTTP_" + httpStatus;
        }

        log.warn(
                "OpenAI request failed: provider=openai, model={}, outcome={}, reason={}, correlation={}",
                modelName,
                outcome,
                reason,
                correlationId);
        return providerFailure(outcome, reason, correlationId, httpStatus, exception);
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
