package com.linetranslate.bot.service;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * Normalizes the operator-facing broadcast settings.
 *
 * <p>A blank value, for example an empty {@code APP_BROADCAST_TEST_MODE} environment variable,
 * means "not configured" and resolves to the default instead of failing startup (an empty string
 * cannot be converted to a primitive boolean).
 */
final class BroadcastSettings {

    static final boolean DEFAULT_TEST_MODE = false;

    private BroadcastSettings() {
    }

    static boolean testMode(String configured) {
        if (StringUtils.isBlank(configured)) {
            return DEFAULT_TEST_MODE;
        }
        Boolean parsed = BooleanUtils.toBooleanObject(configured.trim());
        if (parsed == null) {
            // Failing startup beats silently sending a broadcast for real when test mode was intended.
            throw new IllegalArgumentException("app.broadcast.test-mode must be true or false");
        }
        return parsed;
    }
}
