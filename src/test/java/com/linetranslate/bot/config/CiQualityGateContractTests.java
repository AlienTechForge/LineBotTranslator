package com.linetranslate.bot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class CiQualityGateContractTests {

    /** Inputs only the credential rotation workflow needs; none of them is a container setting. */
    private static final Set<String> ROTATION_ONLY_INPUTS = Set.of(
            "ROTATION_MODE", "ROTATION_CONFIRMATION", "ROTATION_USERNAME", "ROTATION_AUTH_DB",
            "MONGODB_CONTAINER_NAME", "MONGODB_CURRENT_URI", "MONGODB_ROTATION_OLD_URI",
            "MONGODB_ROTATION_NEW_URI", "MONGODB_ROTATION_OLD_PASSWORD", "MONGODB_ROTATION_NEW_PASSWORD");

    /** Inputs scripts/deploy.sh reads or writes for itself instead of copying through runtime_keys. */
    private static final Set<String> DEPLOY_CONTROL_INPUTS = Set.of(
            "IMAGE", "CONTAINER_NAME", "DOCKER_NETWORK", "SERVER_PORT", "HOST_PORT",
            "HEALTH_TIMEOUT_SECONDS", "GOOGLE_CREDENTIALS_JSON");

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
                .contains("target/failsafe-reports/")
                .contains("target/image-translation-replay/")
                .contains("Verify production renderer runtime")
                .contains("fc-match \"Noto Sans CJK TC\"");
    }

    @Test
    void deploymentPassesFailClosedOverlayPolicyIntoRuntimeContainer() throws IOException {
        String workflow = Files.readString(Path.of(".github", "workflows", "ci-cd.yml"));
        String deployScript = Files.readString(Path.of("scripts", "deploy.sh"));

        assertThat(workflow)
                .contains("IMAGE_TRANSLATION_OVERLAY_ENABLED:")
                .contains("IMAGE_TRANSLATION_MAX_REGION_AREA_RATIO:")
                .contains("IMAGE_TRANSLATION_MAX_TOTAL_MASK_RATIO:");
        assertThat(deployScript)
                .contains("IMAGE_TRANSLATION_OVERLAY_ENABLED")
                .contains("IMAGE_TRANSLATION_MAX_REGION_AREA_RATIO")
                .contains("IMAGE_TRANSLATION_MAX_TOTAL_MASK_RATIO");
    }

    @Test
    void deploymentPassesBoundedCachePolicyIntoTheRuntimeContainer() throws IOException {
        String workflow = Files.readString(Path.of(".github", "workflows", "ci-cd.yml"));
        String deployScript = Files.readString(Path.of("scripts", "deploy.sh"));

        assertThat(workflow)
                .contains("TRANSLATION_CACHE_TTL: ${{ vars.TRANSLATION_CACHE_TTL || 'PT30M' }}")
                .contains("TRANSLATION_CACHE_MAX_ENTRIES: ${{ vars.TRANSLATION_CACHE_MAX_ENTRIES || '1000' }}")
                .contains("TRANSLATION_GLOSSARY_VERSION:")
                .doesNotContain("TRANSLATION_STYLE:", "TRANSLATION_PROMPT_VERSION:");
        assertThat(deployScript)
                .contains("TRANSLATION_CACHE_TTL")
                .contains("TRANSLATION_CACHE_MAX_ENTRIES")
                .contains("TRANSLATION_GLOSSARY_VERSION")
                .doesNotContain("TRANSLATION_STYLE", "TRANSLATION_PROMPT_VERSION");
    }

    @Test
    void deploymentPassesTranslationActionClaimLeaseIntoTheRuntimeContainer() throws IOException {
        String workflow = Files.readString(Path.of(".github", "workflows", "ci-cd.yml"));
        String deployScript = Files.readString(Path.of("scripts", "deploy.sh"));
        String properties = Files.readString(
                Path.of("src", "main", "resources", "application.properties"));

        assertThat(workflow).contains(
                "TRANSLATION_ACTION_CLAIM_LEASE: ${{ vars.TRANSLATION_ACTION_CLAIM_LEASE || 'PT5M' }}");
        assertThat(deployScript).contains("TRANSLATION_ACTION_CLAIM_LEASE");
        assertThat(properties).contains(
                "app.translation.action-claim-lease=${TRANSLATION_ACTION_CLAIM_LEASE:PT5M}");
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

    @Test
    void deploymentPassesImageProxyAndJvmMemoryBoundsIntoTheRuntimeContainer() throws IOException {
        String workflow = Files.readString(Path.of(".github", "workflows", "ci-cd.yml"));
        String deployScript = Files.readString(Path.of("scripts", "deploy.sh"));
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        assertThat(workflow)
                .contains("IMAGE_PROXY_CONTENT_CACHE_MAX_BYTES: "
                        + "${{ vars.IMAGE_PROXY_CONTENT_CACHE_MAX_BYTES || '67108864' }}")
                .contains("JAVA_TOOL_OPTIONS: ${{ vars.JAVA_TOOL_OPTIONS || "
                        + "'-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError' }}")
                .doesNotContain("IMAGE_PROXY_CONTENT_CACHE_ENTRIES");
        assertThat(deployScript)
                .contains("IMAGE_PROXY_CONTENT_CACHE_MAX_BYTES")
                .contains("JAVA_TOOL_OPTIONS")
                .doesNotContain("IMAGE_PROXY_CONTENT_CACHE_ENTRIES");
        assertThat(dockerfile)
                .contains("JAVA_TOOL_OPTIONS=\"-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError\"")
                .contains("ENTRYPOINT [\"java\", \"-jar\", \"/app/app.jar\"]");
    }

    @Test
    void deploymentPassesBoundedVisionTimeoutIntoTheRuntimeContainer() throws IOException {
        String workflow = Files.readString(Path.of(".github", "workflows", "ci-cd.yml"));
        String deployScript = Files.readString(Path.of("scripts", "deploy.sh"));

        assertThat(workflow)
                .contains("GOOGLE_VISION_TIMEOUT_MS: ${{ vars.GOOGLE_VISION_TIMEOUT_MS || '60000' }}");
        assertThat(deployScript).contains("GOOGLE_VISION_TIMEOUT_MS");
    }

    @Test
    void deploymentPassesPushFallbackPolicyIntoTheRuntimeContainer() throws IOException {
        String workflow = Files.readString(Path.of(".github", "workflows", "ci-cd.yml"));
        String deployScript = Files.readString(Path.of("scripts", "deploy.sh"));

        assertThat(workflow)
                .contains("LINE_PUSH_FALLBACK_ENABLED: ${{ vars.LINE_PUSH_FALLBACK_ENABLED || 'true' }}")
                .contains("LINE_PUSH_FALLBACK_DAILY_LIMIT: ${{ vars.LINE_PUSH_FALLBACK_DAILY_LIMIT || '100' }}");
        assertThat(deployScript)
                .contains("LINE_PUSH_FALLBACK_ENABLED")
                .contains("LINE_PUSH_FALLBACK_DAILY_LIMIT");
    }

    @Test
    void deployScriptCopiesExactlyTheSettingsTheMasterDeployPasses() throws IOException {
        Set<String> workflowSettings = new TreeSet<>(deployStepEnvironment("ci-cd.yml").keySet());
        workflowSettings.removeAll(DEPLOY_CONTROL_INPUTS);

        assertThat(runtimeKeys(Files.readString(Path.of("scripts", "deploy.sh"))))
                .as("runtime_keys in scripts/deploy.sh and the deploy env in ci-cd.yml must list the same settings")
                .containsExactlyInAnyOrderElementsOf(workflowSettings);
    }

    @Test
    void rotationRedeployPassesTheSameRuntimeSettingsAsTheMasterDeploy() throws IOException {
        Map<String, String> deploy = deployStepEnvironment("ci-cd.yml");
        Map<String, String> rotation = deployStepEnvironment("rotate-mongodb-credential.yml");

        // The rotation script exports the new MONGODB_URI itself when it calls scripts/deploy.sh.
        assertThat(rotation).doesNotContainKey("MONGODB_URI");
        Map<String, String> expected = new LinkedHashMap<>(deploy);
        expected.remove("MONGODB_URI");
        Map<String, String> actual = new LinkedHashMap<>(rotation);
        actual.keySet().removeAll(ROTATION_ONLY_INPUTS);

        assertThat(actual.keySet())
                .as("rotate-mongodb-credential.yml must pass every setting ci-cd.yml passes, and nothing else")
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
        // The image reference is the one shared key whose expression legitimately differs.
        actual.remove("IMAGE");
        expected.remove("IMAGE");
        assertThat(actual)
                .as("shared settings must use the same expression and default in both workflows")
                .containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void deploymentUsesOnlyTheAuthorizedOpenRouterSecretAndValidatesItBeforeMutation()
            throws IOException {
        String workflow = Files.readString(Path.of(".github", "workflows", "ci-cd.yml"));
        String deployScript = Files.readString(Path.of("scripts", "deploy.sh"));

        assertThat(workflow)
                .contains("OPEN_ROUTE_API_KEY: ${{ secrets.OPEN_ROUTE_API_KEY }}")
                .doesNotContain("OPENAI_API_KEY", "GEMINI_API_KEY", "AI_DEFAULT_PROVIDER");
        assertThat(deployScript)
                .contains("require_value OPEN_ROUTE_API_KEY")
                .contains("validate_openrouter")
                .contains("/models?output_modalities=text")
                .doesNotContain("OPENAI_API_KEY", "GEMINI_API_KEY", "AI_DEFAULT_PROVIDER");
    }

    /** The env block of the one workflow step that calls deploy.sh (directly or via the rotation script). */
    @SuppressWarnings("unchecked")
    private static Map<String, String> deployStepEnvironment(String workflowFile) throws IOException {
        Map<String, Object> workflow = new Yaml().load(
                Files.readString(Path.of(".github", "workflows", workflowFile)));
        Map<String, Map<String, Object>> jobs = (Map<String, Map<String, Object>>) workflow.get("jobs");
        Map<String, String> found = null;
        for (Map<String, Object> job : jobs.values()) {
            for (Map<String, Object> step : (List<Map<String, Object>>) job.get("steps")) {
                Map<String, Object> env = (Map<String, Object>) step.get("env");
                if (env != null && env.containsKey("CONTAINER_NAME")) {
                    assertThat(found).as("%s declares one deploy step", workflowFile).isNull();
                    found = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : env.entrySet()) {
                        found.put(entry.getKey(), String.valueOf(entry.getValue()));
                    }
                }
            }
        }
        assertThat(found).as("%s declares a deploy step", workflowFile).isNotNull();
        return found;
    }

    private static Set<String> runtimeKeys(String deployScript) {
        Matcher block = Pattern.compile("(?s)runtime_keys=\\((.*?)\\n\\)").matcher(deployScript);
        assertThat(block.find()).as("runtime_keys array in scripts/deploy.sh").isTrue();
        return new TreeSet<>(Arrays.stream(block.group(1).split("\\R"))
                .map(String::strip)
                .filter(key -> !key.isEmpty())
                .toList());
    }
}
