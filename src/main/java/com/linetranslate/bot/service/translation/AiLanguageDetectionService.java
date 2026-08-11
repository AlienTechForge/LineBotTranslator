package com.linetranslate.bot.service.translation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiProviderExecutionModule;
import com.linetranslate.bot.service.ai.AiProviderException;
import com.linetranslate.bot.logging.SafeLog;

import lombok.extern.slf4j.Slf4j;

/**
 * 使用 AI 模型來檢測文本的語言
 */
@Service
@Slf4j
public class AiLanguageDetectionService {

    private final AiProviderExecutionModule aiProviderExecutionModule;
    
    @Value("${app.language-detection.model-name:${OPEN_ROUTE_MODEL_NAME:openai/gpt-4o-mini}}")
    private String modelName;
    
    @Value("${app.language-detection.default-chinese:zh-tw}")
    private String defaultChineseType;

    @Autowired
    public AiLanguageDetectionService(AiProviderExecutionModule aiProviderExecutionModule) {
        this.aiProviderExecutionModule = aiProviderExecutionModule;
    }

    /**
     * 使用 AI 模型檢測文本的語言
     * 
     * @param text 要檢測的文本
     * @return 檢測到的語言代碼 (ISO 639-1)，如果無法檢測則返回 "unknown"
     */
    public String detectLanguage(String text) {
        try {
            // 構建提示詞
            String prompt = "請檢測以下文本的語言，只返回 ISO 639-1 語言代碼（如 zh, ja, en, ko 等），不要添加任何解釋或其他內容。\n\n" + text;

            AiExecutionOutcome outcome = aiProviderExecutionModule.generateTextOutcome(modelName, prompt);
            if (outcome instanceof AiExecutionOutcome.Failure failure) {
                log.warn(
                        "AI 語言檢測改用本地偵測: provider={}, model={}, outcome={}, reason={}, correlation={}",
                        failure.failure().provider(),
                        failure.failure().model(),
                        failure.failure().outcome(),
                        failure.failure().reason(),
                        failure.failure().correlationId());
                return "unknown";
            }
            String response = ((AiExecutionOutcome.Success) outcome).result().text();
            
            // 清理回應
            String languageCode = cleanResponse(response);
            
            log.info("AI 語言檢測結果: {}", languageCode);
            return languageCode;
        } catch (AiProviderException e) {
            log.warn(
                    "AI 語言檢測改用本地偵測: provider={}, model={}, outcome={}, reason={}, correlation={}",
                    e.getProvider(),
                    e.getModel(),
                    e.getOutcome(),
                    e.getReason(),
                    e.getCorrelationId());
            return "unknown";
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
        
        cleaned = cleaned.replace("`", "").trim();

        // 只接受完整的 ISO 語言碼，避免把供應商錯誤字串誤判成語言。
        if (!cleaned.matches("(?i)^[a-z]{2,3}(?:-[a-z]{2,4})?$")) {
            return "unknown";
        }
        
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
