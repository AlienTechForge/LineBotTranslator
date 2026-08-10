package com.linetranslate.bot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class CiQualityGateContractTests {

    @Test
    void mavenSeparatesFocusedTestsFromIntegrationTests() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom)
                .contains("maven-surefire-plugin")
                .contains("**/*IntegrationTests.java")
                .contains("maven-failsafe-plugin")
                .contains("<goal>integration-test</goal>")
                .contains("<goal>verify</goal>");
    }

    @Test
    void pullRequestGateBuildsPackageAndRetainsBothTestReportsOnFailure() throws IOException {
        String workflow = Files.readString(Path.of(".github", "workflows", "ci-cd.yml"));

        assertThat(workflow)
                .contains("pull_request:")
                .contains("cache: maven")
                .contains("./mvnw clean verify -B")
                .contains("Verify packaged application")
                .contains("target/surefire-reports/")
                .contains("target/failsafe-reports/");
    }
}
