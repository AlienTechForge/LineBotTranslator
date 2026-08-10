package com.linetranslate.bot.service.translation;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiProviderExecutionModule;

/**
 * Cache Adapter around provider execution. Failures are never cached.
 */
@Service
public class CachedTranslationAdapter {

    private final AiProviderExecutionModule providerExecutionModule;

    public CachedTranslationAdapter(AiProviderExecutionModule providerExecutionModule) {
        this.providerExecutionModule = providerExecutionModule;
    }

    @Cacheable(
            value = "translations",
            key = "{#text, #targetLanguage, #userProfile.preferredAiProvider, "
                    + "#userProfile.openaiPreferredModel, #userProfile.geminiPreferredModel}",
            unless = "#result.failed()")
    public AiExecutionOutcome translate(
            UserProfile userProfile,
            String text,
            String targetLanguage) {
        return providerExecutionModule.translateTextOutcome(userProfile, text, targetLanguage);
    }
}
