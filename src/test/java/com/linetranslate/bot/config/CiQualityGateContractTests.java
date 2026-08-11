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

    @Test
    void deploymentPassesBoundedCachePolicyIntoTheRuntimeContainer() throws IOException {
        String workflow = Files.readString(Path.of(".github", "workflows", "ci-cd.yml"));
        String deployScript = Files.readString(Path.of("scripts", "deploy.sh"));

        assertThat(workflow)
                .contains("TRANSLATION_CACHE_TTL: ${{ vars.TRANSLATION_CACHE_TTL || 'PT30M' }}")
                .contains("TRANSLATION_CACHE_MAX_ENTRIES: ${{ vars.TRANSLATION_CACHE_MAX_ENTRIES || '1000' }}")
                .contains("TRANSLATION_PROMPT_VERSION:");
        assertThat(deployScript)
                .contains("TRANSLATION_CACHE_TTL")
                .contains("TRANSLATION_CACHE_MAX_ENTRIES")
                .contains("TRANSLATION_PROMPT_VERSION");
    }

    @Test
    void deploymentPassesBoundedWebhookPolicyIntoTheRuntimeContainer() throws IOException {
        String workflow = Files.readString(Path.of(".github", "workflows", "ci-cd.yml"));
        String deployScript = Files.readString(Path.of("scripts", "deploy.sh"));

        assertThat(workflow)
                .contains("WEBHOOK_CORE_THREADS: ${{ vars.WEBHOOK_CORE_THREADS || '2' }}")
                .contains("WEBHOOK_MAX_THREADS: ${{ vars.WEBHOOK_MAX_THREADS || '4' }}")
                .contains("WEBHOOK_QUEUE_CAPACITY: ${{ vars.WEBHOOK_QUEUE_CAPACITY || '100' }}")
                .contains("WEBHOOK_RECEIPT_TTL: ${{ vars.WEBHOOK_RECEIPT_TTL || 'P7D' }}")
                .contains("WEBHOOK_PROCESSING_LEASE: ${{ vars.WEBHOOK_PROCESSING_LEASE || 'PT5M' }}")
                .contains("WEBHOOK_REPLY_MAX_ATTEMPTS: ${{ vars.WEBHOOK_REPLY_MAX_ATTEMPTS || '3' }}")
                .contains("WEBHOOK_REPLY_RETRY_BACKOFF: ${{ vars.WEBHOOK_REPLY_RETRY_BACKOFF || 'PT1S' }}");
        assertThat(deployScript)
                .contains("WEBHOOK_CORE_THREADS")
                .contains("WEBHOOK_MAX_THREADS")
                .contains("WEBHOOK_QUEUE_CAPACITY")
                .contains("WEBHOOK_RECEIPT_TTL")
                .contains("WEBHOOK_PROCESSING_LEASE")
                .contains("WEBHOOK_REPLY_MAX_ATTEMPTS")
                .contains("WEBHOOK_REPLY_RETRY_BACKOFF");
    }
}
