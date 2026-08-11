package com.linetranslate.bot.service.translation;

public enum TranslationCacheSkipReason {
    FAILURE,
    SAFETY_BLOCKED,
    FALLBACK,
    ROUTE_MISMATCH,
    INVALID_RESPONSE
}
