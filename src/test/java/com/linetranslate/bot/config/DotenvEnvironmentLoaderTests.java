package com.linetranslate.bot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class DotenvEnvironmentLoaderTests {

    @TempDir
    Path tempDirectory;

    @Test
    void missingDotenvIsAnExpectedNoOp() {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(DotenvEnvironmentLoader.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            boolean loaded = DotenvEnvironmentLoader.load(tempDirectory.resolve(".env"));

            assertThat(loaded).isFalse();
            assertThat(appender.list)
                    .noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void existingJvmPropertyWinsOverDotenv() throws Exception {
        String key = "LINEBOT_TEST_EXISTING_SETTING";
        String previous = System.getProperty(key);
        Files.writeString(tempDirectory.resolve(".env"), key + "=dotenv-value\n");
        System.setProperty(key, "existing-value");

        try {
            assertThat(DotenvEnvironmentLoader.load(tempDirectory.resolve(".env"))).isTrue();
            assertThat(System.getProperty(key)).isEqualTo("existing-value");
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }
}
