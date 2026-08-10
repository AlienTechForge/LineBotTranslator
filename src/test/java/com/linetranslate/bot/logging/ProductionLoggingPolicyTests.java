package com.linetranslate.bot.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class ProductionLoggingPolicyTests {

    private static final Pattern LOG_STATEMENT = Pattern.compile(
            "log\\.(?:info|warn|error)\\((?s:.*?)\\);",
            Pattern.MULTILINE);

    private static final List<Pattern> UNSAFE_ARGUMENTS = List.of(
            Pattern.compile("\\b(?:userId|targetUserId|testUserId|displayName)\\b"),
            Pattern.compile("\\b(?:text|sourceText|recognizedText|message|prompt)\\b"),
            Pattern.compile("\\b(?:imageUrl|mongoUri|response|responseBody|result)\\b"),
            Pattern.compile("\\b(?:endpoint|credentialsPath|envCredentialsPath|accessKey|secretKey|apiKey)\\b"),
            Pattern.compile("\\.(?:getAbsolutePath|getName)\\(\\)"),
            Pattern.compile("\\.getUserId\\(\\)"),
            Pattern.compile("\\.getMessage\\(\\)"),
            Pattern.compile(",\\s*[a-zA-Z][a-zA-Z0-9_]*Exception\\s*\\)"),
            Pattern.compile(",\\s*e\\s*\\)"));

    @Test
    void productionLogCallsUseSafeMetadataForSensitiveValues() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith(Path.of("controller", "TestController.java")))
                    .forEach(path -> inspect(path, violations));
        }

        assertThat(violations)
                .as("unsafe production log arguments")
                .isEmpty();
    }

    @Test
    void applicationCodeNeverReturnsOrLogsRawExceptionMessages() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path);
                            if (source.contains("e.getMessage()") || source.contains("exception.getMessage()")) {
                                violations.add(path.toString());
                            }
                        } catch (IOException exception) {
                            throw new IllegalStateException("Unable to inspect " + path, exception);
                        }
                    });
        }

        assertThat(violations)
                .as("raw exception messages exposed by application code")
                .isEmpty();
    }

    private static void inspect(Path path, List<String> violations) {
        try {
            String source = Files.readString(path);
            Matcher matcher = LOG_STATEMENT.matcher(source);
            while (matcher.find()) {
                String statement = matcher.group();
                boolean risky = UNSAFE_ARGUMENTS.stream().anyMatch(pattern -> pattern.matcher(statement).find());
                boolean protectedBySafeLog = statement.contains("SafeLog.");
                if (risky && !protectedBySafeLog) {
                    long line = source.substring(0, matcher.start()).lines().count();
                    violations.add(path + ":" + line + " " + statement.replaceAll("\\s+", " "));
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect " + path, exception);
        }
    }
}
