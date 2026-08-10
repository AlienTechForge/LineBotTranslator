package com.linetranslate.bot.config;

import java.nio.file.Files;
import java.nio.file.Path;

import com.linetranslate.bot.logging.SafeLog;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.extern.slf4j.Slf4j;

/** Loads a local dotenv file as a development convenience without overriding effective settings. */
@Slf4j
public final class DotenvEnvironmentLoader {

    private DotenvEnvironmentLoader() {
    }

    public static boolean load(Path envFile) {
        if (!Files.isRegularFile(envFile)) {
            log.debug("Optional dotenv file is absent");
            return false;
        }

        try {
            Dotenv dotenv = Dotenv.configure()
                    .directory(envFile.toAbsolutePath().getParent().toString())
                    .filename(envFile.getFileName().toString())
                    .load();
            dotenv.entries().forEach(entry -> {
                if (System.getProperty(entry.getKey()) == null && System.getenv(entry.getKey()) == null) {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            });
            log.info("Loaded optional dotenv configuration");
            return true;
        } catch (RuntimeException exception) {
            log.warn("Unable to load optional dotenv configuration: failure={}", SafeLog.failure(exception));
            return false;
        }
    }
}
