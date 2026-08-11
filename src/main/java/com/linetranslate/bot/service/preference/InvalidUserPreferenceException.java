package com.linetranslate.bot.service.preference;

public class InvalidUserPreferenceException extends IllegalArgumentException {

    public enum Kind {
        LANGUAGE,
        PROVIDER,
        MODEL
    }

    private final Kind kind;

    public InvalidUserPreferenceException(Kind kind, String value) {
        super("Unsupported " + kind.name().toLowerCase(java.util.Locale.ROOT) + ": " + value);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
