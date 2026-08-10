package com.linetranslate.bot.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.config.GeminiConfig;
import com.linetranslate.bot.logging.SafeLog;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import com.linetranslate.bot.model.UserProfile;

@Service
@Slf4j
public class GeminiService implements AiService {

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

        // 初始化 HTTP 客戶端
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
        try {
            // 建立提示
            String prompt = "請將以下文本翻譯成" + targetLanguage + "。只需返回翻譯結果，不要添加任何解釋或額外信息：\n\n" + text;

            // 建立請求體
            ObjectNode requestBodyJson = objectMapper.createObjectNode();

            // 添加內容部分
            ArrayNode contentsArray = requestBodyJson.putArray("contents");
            ObjectNode contentObject = contentsArray.addObject();
            ArrayNode partsArray = contentObject.putArray("parts");
            ObjectNode textPart = partsArray.addObject();
            textPart.put("text", prompt);

            // 添加生成配置
            ObjectNode generationConfig = requestBodyJson.putObject("generationConfig");
            generationConfig.put("temperature", 0.2);
            generationConfig.put("topK", 40);
            generationConfig.put("topP", 0.95);
            generationConfig.put("maxOutputTokens", 1024);

            String requestBody = objectMapper.writeValueAsString(requestBodyJson);

            // 建立請求
            Request request = new Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/" + modelName + ":generateContent?key=" + apiKey)
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            // 發送請求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Gemini API 請求失敗: status={}", SafeLog.httpStatus(response.code()));
                    return "翻譯失敗: Gemini API 請求錯誤 " + response.code();
                }

                String responseBody = response.body().string();
                JsonNode jsonResponse = objectMapper.readTree(responseBody);

                // 解析回應
                JsonNode candidatesNode = jsonResponse.path("candidates");
                if (candidatesNode.isArray() && candidatesNode.size() > 0) {
                    JsonNode contentNode = candidatesNode.get(0).path("content");
                    JsonNode partsNode = contentNode.path("parts");

                    if (partsNode.isArray() && partsNode.size() > 0) {
                        String translatedText = partsNode.get(0).path("text").asText();
                        return translatedText.trim();
                    }
                }

                log.error("無法從 Gemini 回應中解析翻譯結果: response={}", SafeLog.content(responseBody));
                return "翻譯失敗: 無法解析回應";
            }
        } catch (IOException e) {
            log.error("Gemini 翻譯失敗: failure={}", SafeLog.failure(e));
            return "翻譯失敗，請稍後再試。";
        }
    }

    @Override
    public String processImage(String prompt, String imageUrl) {
        try {
            // 建立請求體
            ObjectNode requestBodyJson = objectMapper.createObjectNode();

            // 添加內容部分
            ArrayNode contentsArray = requestBodyJson.putArray("contents");
            ObjectNode contentObject = contentsArray.addObject();
            ArrayNode partsArray = contentObject.putArray("parts");

            // 添加文本提示
            ObjectNode textPart = partsArray.addObject();
            textPart.put("text", prompt);

            // 添加圖片
            ObjectNode imagePart = partsArray.addObject();
            ObjectNode inlineData = imagePart.putObject("inlineData");

            // 從 data:image/jpeg;base64, 格式中提取 base64 部分
            String base64Data = imageUrl;
            if (imageUrl.contains(";base64,")) {
                base64Data = imageUrl.split(";base64,")[1];
            }

            inlineData.put("data", base64Data);
            inlineData.put("mimeType", "image/jpeg");

            // 添加生成配置
            ObjectNode generationConfig = requestBodyJson.putObject("generationConfig");
            generationConfig.put("temperature", 0.1);
            generationConfig.put("topK", 32);
            generationConfig.put("topP", 0.95);
            generationConfig.put("maxOutputTokens", 1024);

            String requestBody = objectMapper.writeValueAsString(requestBodyJson);

            // 建立請求
            Request request = new Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/" + modelName + ":generateContent?key=" + apiKey)
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            // 發送請求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Gemini API 圖片處理請求失敗: status={}", SafeLog.httpStatus(response.code()));
                    return "圖片處理失敗: Gemini API 請求錯誤 " + response.code();
                }

                String responseBody = response.body().string();
                JsonNode jsonResponse = objectMapper.readTree(responseBody);

                // 解析回應
                JsonNode candidatesNode = jsonResponse.path("candidates");
                if (candidatesNode.isArray() && candidatesNode.size() > 0) {
                    JsonNode contentNode = candidatesNode.get(0).path("content");
                    JsonNode partsNode = contentNode.path("parts");

                    if (partsNode.isArray() && partsNode.size() > 0) {
                        String extractedText = partsNode.get(0).path("text").asText();
                        return extractedText.trim();
                    }
                }

                log.error("無法從 Gemini 回應中解析圖片處理結果: response={}", SafeLog.content(responseBody));
                return "圖片處理失敗: 無法解析回應";
            }
        } catch (IOException e) {
            log.error("Gemini 圖片處理失敗: failure={}", SafeLog.failure(e));
            return "圖片處理失敗，請稍後再試。";
        }
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
        try {
            // 建立請求體
            ObjectNode requestBodyJson = objectMapper.createObjectNode();

            // 添加內容部分
            ArrayNode contentsArray = requestBodyJson.putArray("contents");
            ObjectNode contentObject = contentsArray.addObject();
            ArrayNode partsArray = contentObject.putArray("parts");
            ObjectNode textPart = partsArray.addObject();
            textPart.put("text", prompt);

            // 添加生成配置
            ObjectNode generationConfig = requestBodyJson.putObject("generationConfig");
            generationConfig.put("temperature", 0.7);
            generationConfig.put("topK", 40);
            generationConfig.put("topP", 0.95);
            generationConfig.put("maxOutputTokens", 1024);

            String requestBody = objectMapper.writeValueAsString(requestBodyJson);

            // 建立請求
            Request request = new Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/" + modelName + ":generateContent?key=" + apiKey)
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            // 發送請求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Gemini API 請求失敗: status={}", SafeLog.httpStatus(response.code()));
                    return "文本生成失敗: Gemini API 請求錯誤 " + response.code();
                }

                String responseBody = response.body().string();
                JsonNode jsonResponse = objectMapper.readTree(responseBody);

                // 解析回應
                JsonNode candidatesNode = jsonResponse.path("candidates");
                if (candidatesNode.isArray() && candidatesNode.size() > 0) {
                    JsonNode contentNode = candidatesNode.get(0).path("content");
                    JsonNode partsNode = contentNode.path("parts");

                    if (partsNode.isArray() && partsNode.size() > 0) {
                        String generatedText = partsNode.get(0).path("text").asText();
                        return generatedText.trim();
                    }
                }

                log.error("無法從 Gemini 回應中解析生成結果: response={}", SafeLog.content(responseBody));
                return "文本生成失敗: 無法解析回應";
            }
        } catch (IOException e) {
            log.error("Gemini 文本生成失敗: failure={}", SafeLog.failure(e));
            return "文本生成失敗，請稍後再試。";
        }
    }
}
