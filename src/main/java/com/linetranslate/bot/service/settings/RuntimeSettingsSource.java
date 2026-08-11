package com.linetranslate.bot.service.settings;

/** Read Seam for consumers that need effective runtime defaults. */
@FunctionalInterface
public interface RuntimeSettingsSource {

    RuntimeSettings current();
}
