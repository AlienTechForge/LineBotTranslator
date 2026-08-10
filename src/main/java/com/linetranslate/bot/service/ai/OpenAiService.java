package com.linetranslate.bot.service.ai;

import com.theokanning.openai.completion.chat.*;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.config.OpenAiConfig;
import com.linetranslate.bot.logging.SafeLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.linetranslate.bot.model.UserProfile;

@Service
@Slf4j
public class OpenAiService implements AiService {

    private final com.theokanning.openai.service.OpenAiService openAiClient;
    private final String modelName;

    @Autowired
    public OpenAiService(OpenAiConfig openAiConfig, @Qualifier("openAiClient") @Autowired(required = false) com.theokanning.openai.service.OpenAiService openAiClient) {
        this.openAiClient = openAiClient;
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
            List<ChatMessage> messages = new ArrayList<>();

            // 系統訊息設定翻譯任務
            ChatMessage systemMessage = new ChatMessage(ChatMessageRole.SYSTEM.value(),
                    "你是一個專業的翻譯助手。請將用戶提供的文本翻譯成" + targetLanguage + "。只需返回翻譯結果，不要添加任何解釋或額外信息。");
            messages.add(systemMessage);

            // 用戶訊息包含要翻譯的文本
            ChatMessage userMessage = new ChatMessage(ChatMessageRole.USER.value(), text);
            messages.add(userMessage);

            // 建立請求
            ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
                    .model(modelName)
                    .messages(messages)
                    .temperature(0.3)  // 較低的溫度使輸出更加確定性和準確
                    .build();

            // 執行請求並獲取回應
            String response = openAiClient.createChatCompletion(chatCompletionRequest)
                    .getChoices().get(0).getMessage().getContent();

            return response.trim();
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
            List<ChatMessage> messages = new ArrayList<>();

            // 系統訊息設定OCR任務
            ChatMessage systemMessage = new ChatMessage(ChatMessageRole.SYSTEM.value(),
                    "你是一個專業的OCR助手。請識別並提取圖片中的所有文字。只需返回文字內容，不要添加任何解釋或說明。");
            messages.add(systemMessage);

            // 用戶訊息包含提示
            ChatMessage promptMessage = new ChatMessage(ChatMessageRole.USER.value(), prompt);
            messages.add(promptMessage);

            // 創建包含圖片URL的消息
            List<MessageContent> contents = new ArrayList<>();
            contents.add(new MessageContent(ContentType.TEXT.value(), prompt));

            Map<String, String> imageUrlMap = new HashMap<>();
            imageUrlMap.put("url", imageUrl);

            Map<String, Object> imageMap = new HashMap<>();
            imageMap.put("image_url", imageUrlMap);

            contents.add(new MessageContent(ContentType.IMAGE_URL.value(), imageMap));

            ChatMessage userMessage = new ChatMessage(ChatMessageRole.USER.value(), contents.toString());
            messages.add(userMessage);

            // 建立請求
            ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
                    .model(modelName)
                    .messages(messages)
                    .temperature(0.3)
                    .maxTokens(1024)
                    .build();

            // 執行請求並獲取回應
            String response = openAiClient.createChatCompletion(chatCompletionRequest)
                    .getChoices().get(0).getMessage().getContent();

            return response.trim();
        } catch (Exception e) {
            log.error("OpenAI 圖片處理失敗: failure={}", SafeLog.failure(e));
            return "圖片處理失敗，請稍後再試。";
        }
    }

    @Override
    public String getProviderName() {
        return "openai";
    }

    private final OpenAiConfig openAiConfig;
    
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
        // 如果用戶有指定 OpenAI 模型，則使用用戶指定的模型
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
            List<ChatMessage> messages = new ArrayList<>();

            // 系統訊息設定文本生成任務
            ChatMessage systemMessage = new ChatMessage(ChatMessageRole.SYSTEM.value(),
                    "你是一個專業的語言助手。請根據用戶的提示生成回應。");
            messages.add(systemMessage);

            // 用戶訊息包含提示
            ChatMessage userMessage = new ChatMessage(ChatMessageRole.USER.value(), prompt);
            messages.add(userMessage);

            // 建立請求
            ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
                    .model(modelName)
                    .messages(messages)
                    .temperature(0.7)  // 適中的溫度使輸出更加多樣化
                    .build();

            // 執行請求並獲取回應
            String response = openAiClient.createChatCompletion(chatCompletionRequest)
                    .getChoices().get(0).getMessage().getContent();

            return response.trim();
        } catch (Exception e) {
            log.error("OpenAI 文本生成失敗: failure={}", SafeLog.failure(e));
            return "文本生成失敗，請稍後再試。";
        }
    }

    // 內部類用於處理消息內容
    private static class MessageContent {
        private String type;
        private Object content;

        public MessageContent(String type, Object content) {
            this.type = type;
            this.content = content;
        }

        public String getType() {
            return type;
        }

        public Object getContent() {
            return content;
        }
    }

    private enum ContentType {
        TEXT("text"),
        IMAGE_URL("image_url");

        private final String value;

        ContentType(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
