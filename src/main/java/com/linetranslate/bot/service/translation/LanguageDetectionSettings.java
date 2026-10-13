package com.linetranslate.bot.service.translation;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * Normalizes the operator-facing language detection settings.
 *
 * <p>A blank value, for example an empty {@code LANGUAGE_DETECTION_*} environment variable, means
 * "not configured" and resolves to the default instead of failing startup (an empty string cannot
 * be converted to a primitive boolean) or producing an empty language code.
 */
final class LanguageDetectionSettings {

    static final boolean DEFAULT_USE_AI = true;
    static final String DEFAULT_CHINESE = "zh-tw";

    private LanguageDetectionSettings() {
    }

    static boolean useAi(String configured) {
        if (StringUtils.isBlank(configured)) {
            return DEFAULT_USE_AI;
        }
        Boolean parsed = BooleanUtils.toBooleanObject(configured.trim());
        if (parsed == null) {
            throw new IllegalArgumentException("app.language-detection.use-ai must be true or false");
        }
        return parsed;
    }

    static String defaultChinese(String configured) {
        return StringUtils.isBlank(configured) ? DEFAULT_CHINESE : configured.trim();
    }
}
