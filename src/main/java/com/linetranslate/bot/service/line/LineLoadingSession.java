package com.linetranslate.bot.service.line;

/** Cancellable lease for renewable LINE loading feedback. */
@FunctionalInterface
public interface LineLoadingSession extends AutoCloseable {

    LineLoadingSession NONE = () -> { };

    @Override
    void close();
}
