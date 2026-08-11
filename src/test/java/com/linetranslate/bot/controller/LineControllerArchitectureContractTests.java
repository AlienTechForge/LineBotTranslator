package com.linetranslate.bot.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class LineControllerArchitectureContractTests {

    @Test
    void controllersAreThinLineAdaptersWithoutParsingOrMessageAssembly() throws IOException {
        String lineController = source("LineBotController.java");
        String adminController = source("AdminController.java");

        assertThat(lineController)
                .doesNotContain("switch (")
                .doesNotContain("startsWith(\"/\")")
                .doesNotContain("new TextMessage")
                .doesNotContain("StringBuilder");
        assertThat(adminController)
                .doesNotContain("switch (")
                .doesNotContain("split(\"\\\\s+\"")
                .doesNotContain("StringBuilder");
    }

    private static String source(String fileName) throws IOException {
        return Files.readString(Path.of(
                "src", "main", "java", "com", "linetranslate", "bot", "controller", fileName));
    }
}
