package com.linetranslate.bot.service.ai;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.model.UserProfile;

import lombok.extern.slf4j.Slf4j;

/**
 * AI 服務工廠，根據配置或用戶偏好選擇合適的 AI 服務
 */
@Service
@Slf4j
public class AiServiceFactory {

    private final OpenAiService openAiService;
    private final GeminiService geminiService;
    private final String defaultProvider;

    public AiServiceFactory(
            ObjectProvider<OpenAiService> openAiServiceProvider,
            ObjectProvider<GeminiService> geminiServiceProvider,
            @Value("${app.ai.default-provider:openai}") String defaultProvider) {
        OpenAiService openAiService = openAiServiceProvider.getIfAvailable();
        GeminiService geminiService = geminiServiceProvider.getIfAvailable();
        this.openAiService = openAiService;
        this.geminiService = geminiService;

        // 使用臨時變量存儲最終要使用的提供者
        String effectiveProvider = defaultProvider;

        // 如果默認提供者的服務為空，嘗試使用另一個
        if ("openai".equals(effectiveProvider) && openAiService == null) {
            if (geminiService != null) {
                log.info("OpenAI 服務不可用，將使用 Gemini 作為默認提供者");
                effectiveProvider = "gemini";
            }
        } else if ("gemini".equals(effectiveProvider) && geminiService == null) {
            if (openAiService != null) {
                log.info("Gemini 服務不可用，將使用 OpenAI 作為默認提供者");
                effectiveProvider = "openai";
            }
        }

        // 最後統一賦值
        this.defaultProvider = effectiveProvider;

        log.info("AI 服務工廠初始化成功，預設提供者: {}", this.defaultProvider);

        if (openAiService == null && geminiService == null) {
            log.warn("所有 AI 服務都不可用，翻譯功能將無法正常工作");
        }
    }

    /**
     * 獲取 AI 服務
     *
     * @param provider 提供者名稱（"openai" 或 "gemini"），如果為 null 則使用預設提供者
     * @return 相應的 AI 服務實例，如果所有服務都不可用，返回一個臨時服務
     */
    public AiService getService(String provider) {
        String actualProvider = (provider != null) ? provider : defaultProvider;

        // 嘗試獲取請求的服務
        AiService requestedService = getRequestedService(actualProvider);
        if (requestedService != null) {
            return requestedService;
        }

        // 如果請求的服務不可用，嘗試使用其他服務
        AiService fallbackService = getFallbackService(actualProvider);
        if (fallbackService != null) {
            log.warn("請求的 AI 服務 {} 不可用，使用 {} 作為替代", actualProvider, fallbackService.getProviderName());
            return fallbackService;
        }

        // 如果所有服務都不可用，返回一個臨時服務
        log.error("所有 AI 服務都不可用");
        return new FallbackAiService();
    }
    
    /**
     * 根據用戶資料獲取 AI 服務
     * 
     * @param userProfile 用戶資料
     * @return 相應的 AI 服務實例，如果所有服務都不可用，返回一個臨時服務
     */
    public AiService getService(UserProfile userProfile) {
        // 獲取用戶偏好的提供者
        String provider = userProfile.getPreferredAiProvider();
        String actualProvider = (provider != null && !provider.isEmpty()) ? provider : defaultProvider;
        
        // 根據提供者獲取相應的服務
        AiService service;
        if ("openai".equals(actualProvider)) {
            service = openAiService;
            if (service != null && service instanceof OpenAiService) {
                // 使用用戶偏好的 OpenAI 模型
                return new UserPreferredAiService((OpenAiService) service, userProfile);
            }
        } else if ("gemini".equals(actualProvider)) {
            service = geminiService;
            if (service != null && service instanceof GeminiService) {
                // 使用用戶偏好的 Gemini 模型
                return new UserPreferredAiService((GeminiService) service, userProfile);
            }
        }
        
        // 如果無法獲取用戶偏好的服務，則使用預設方式獲取服務
        return getService(actualProvider);
    }

    /**
     * 獲取請求的服務
     */
    private AiService getRequestedService(String provider) {
        switch (provider.toLowerCase()) {
            case "openai":
                return openAiService;
            case "gemini":
                return geminiService;
            default:
                log.warn("未知的 AI 提供者: {}", provider);
                return null;
        }
    }

    /**
     * 獲取備用服務
     */
    private AiService getFallbackService(String primaryProvider) {
        if ("openai".equals(primaryProvider)) {
            return geminiService;
        } else {
            return openAiService;
        }
    }

    /**
     * 用戶偏好 AI 服務實現
     * 包裝現有的 AI 服務，使用用戶偏好的模型
     */
    private static class UserPreferredAiService implements AiService {
        private final AiService baseService;
        private final String modelName;
        
        public UserPreferredAiService(OpenAiService openAiService, UserProfile userProfile) {
            this.baseService = openAiService;
            this.modelName = openAiService.getModelName(userProfile);
        }
        
        public UserPreferredAiService(GeminiService geminiService, UserProfile userProfile) {
            this.baseService = geminiService;
            this.modelName = geminiService.getModelName(userProfile);
        }
        
        @Override
        public String translateText(String text, String targetLanguage) {
            return baseService.translateText(text, targetLanguage);
        }

        @Override
        public String processImage(String prompt, String imageUrl) {
            return baseService.processImage(prompt, imageUrl);
        }

        @Override
        public String getProviderName() {
            return baseService.getProviderName();
        }

        @Override
        public String getModelName() {
            return modelName;
        }

        @Override
        public String generateText(String prompt) {
            return baseService.generateText(prompt);
        }
    }
    
    /**
     * 備用 AI 服務實現
     */
    private static class FallbackAiService implements AiService {
        @Override
        public String translateText(String text, String targetLanguage) {
            return "無法翻譯：所有 AI 服務都未正確配置。請檢查環境變數設置。";
        }

        @Override
        public String processImage(String prompt, String imageUrl) {
            return "無法處理圖片：所有 AI 服務都未正確配置。請檢查環境變數設置。";
        }

        @Override
        public String getProviderName() {
            return "fallback";
        }

        @Override
        public String getModelName() {
            return "none";
        }

        @Override
        public String generateText(String prompt) {
            return "無法生成文本：所有 AI 服務都未正確配置。請檢查環境變數設置。";
        }
    }
}
