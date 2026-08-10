package com.linetranslate.bot.service.ai;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.config.OpenAiConfig;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.model.UserProfile;
import com.openai.client.OpenAIClient;
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
        if (openAiClient == null) {
            log.warn("OpenAI 客戶端未初始化，無法進行翻譯");
            return "翻譯失敗: OpenAI API 未正確配置";
        }

        try {
            ResponseCreateParams params = ResponseCreateParams.builder()
                    .model(modelName)
                    .instructions(TRANSLATION_INSTRUCTIONS.formatted(targetLanguage))
                    .input(text)
                    .temperature(0.3)
                    .build();
            return createResponse(params);
        } catch (Exception e) {
            log.error("OpenAI 翻譯失敗: failure={}", SafeLog.failure(e));
            return "翻譯失敗，請稍後再試。";
        }
    }

    @Override
    public String processImage(String prompt, String imageUrl) {
        if (openAiClient == null) {
            log.warn("OpenAI 客戶端未初始化，無法處理圖片");
            return "處理失敗: OpenAI API 未正確配置";
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
            return createResponse(params);
        } catch (Exception e) {
            log.error("OpenAI 圖片處理失敗: failure={}", SafeLog.failure(e));
            return "圖片處理失敗，請稍後再試。";
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
        if (openAiClient == null) {
            log.warn("OpenAI 客戶端未初始化，無法生成文本");
            return "生成失敗: OpenAI API 未正確配置";
        }

        try {
            ResponseCreateParams params = ResponseCreateParams.builder()
                    .model(modelName)
                    .instructions(GENERATION_INSTRUCTIONS)
                    .input(prompt)
                    .temperature(0.7)
                    .build();
            return createResponse(params);
        } catch (Exception e) {
            log.error("OpenAI 文本生成失敗: failure={}", SafeLog.failure(e));
            return "文本生成失敗，請稍後再試。";
        }
    }

    private String createResponse(ResponseCreateParams params) {
        Response response = openAiClient.responses().create(params);
        String output = response.output().stream()
                .flatMap(item -> item.message().stream())
                .flatMap(message -> message.content().stream())
                .flatMap(content -> content.outputText().stream())
                .map(outputText -> outputText.text())
                .collect(Collectors.joining())
                .trim();
        if (output.isEmpty()) {
            throw new IllegalStateException("OpenAI 回應不包含文字內容");
        }
        return output;
    }
}
