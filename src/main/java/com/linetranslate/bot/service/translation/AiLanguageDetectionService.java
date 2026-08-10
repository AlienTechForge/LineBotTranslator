package com.linetranslate.bot.service.translation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.service.ai.AiService;
import com.linetranslate.bot.service.ai.AiServiceFactory;
import com.linetranslate.bot.logging.SafeLog;

import lombok.extern.slf4j.Slf4j;

/**
 * 使用 AI 模型來檢測文本的語言
 */
@Service
@Slf4j
public class AiLanguageDetectionService {

    private final AiServiceFactory aiServiceFactory;
    
    @Value("${app.language-detection.ai-provider:gemini}")
    private String aiProvider;
    
    @Value("${app.language-detection.model-name:gemini-1.5-flash-001}")
    private String modelName;
    
    @Value("${app.language-detection.default-chinese:zh-tw}")
    private String defaultChineseType;

    @Autowired
    public AiLanguageDetectionService(AiServiceFactory aiServiceFactory) {
        this.aiServiceFactory = aiServiceFactory;
    }

    /**
     * 使用 AI 模型檢測文本的語言
     * 
     * @param text 要檢測的文本
     * @return 檢測到的語言代碼 (ISO 639-1)，如果無法檢測則返回 "unknown"
     */
    public String detectLanguage(String text) {
        try {
            // 使用指定的 AI 提供者和模型
            AiService aiService = aiServiceFactory.getService(aiProvider);
            
            // 構建提示詞
            String prompt = "請檢測以下文本的語言，只返回 ISO 639-1 語言代碼（如 zh, ja, en, ko 等），不要添加任何解釋或其他內容。\n\n" + text;
            
            // 調用 AI 模型
            String response = aiService.generateText(prompt);
            
            // 清理回應
            String languageCode = cleanResponse(response);
            
            log.info("AI 語言檢測結果: {}", languageCode);
            return languageCode;
        } catch (Exception e) {
            log.error("AI 語言檢測失敗: failure={}", SafeLog.failure(e));
            return "unknown";
        }
    }
    
    /**
     * 清理 AI 回應，提取語言代碼
     */
    private String cleanResponse(String response) {
        if (response == null || response.isEmpty()) {
            return "unknown";
        }
        
        // 移除空白字符
        String cleaned = response.trim();
        
        // 如果回應包含多行，只取第一行
        if (cleaned.contains("\n")) {
            cleaned = cleaned.split("\n")[0];
        }
        
        // 如果回應超過 10 個字符，可能包含了解釋，只取前 10 個字符
        if (cleaned.length() > 10) {
            cleaned = cleaned.substring(0, 10);
        }
        
        // 移除任何非字母字符
        cleaned = cleaned.replaceAll("[^a-zA-Z\\-]", "");
        
        // 轉換為小寫
        cleaned = cleaned.toLowerCase();
        
        // 如果清理後的結果為空，返回 unknown
        if (cleaned.isEmpty()) {
            return "unknown";
        }
        
        // 處理中文變體
        if (cleaned.startsWith("zh")) {
            return defaultChineseType;
        }
        
        return cleaned;
    }
}
