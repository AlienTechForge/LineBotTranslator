package com.linetranslate.bot.service.settings;

public class RuntimeSettingsPersistenceException extends RuntimeException {

    public RuntimeSettingsPersistenceException(String message) {
        super(message);
    }

    public RuntimeSettingsPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
