package com.linetranslate.bot.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.linetranslate.bot.service.preference.UserPreferences;
import static com.linetranslate.bot.testing.UserPreferencesFixtures.preferences;

class AiProviderExecutionModuleTests {

    @Test
    void plannedTextRouteUsesTheEffectiveProviderAndModel() {
        FakeAdapter openAi = new FakeAdapter("openai", "gpt-default", Set.of("gpt-default", "gpt-selected"));
        AiProviderExecutionModule module = module(openAi);
        UserPreferences selected = preferences("openai", "gpt-selected", "gemini-default");
        UserPreferences unsupported = preferences("openai", "gpt-unknown", "gemini-default");

        assertThat(module.planText(selected))
                .isEqualTo(new AiProviderRoute("openai", "gpt-selected"));
        assertThat(module.planText(unsupported))
                .isEqualTo(new AiProviderRoute("openai", "gpt-default"));
    }

    @Test
    void selectedModelIsSentToTheProviderAdapter() {
        FakeAdapter openAi = new FakeAdapter("openai", "gpt-default", Set.of("gpt-default", "gpt-selected"));
        AiProviderExecutionModule module = module(openAi);
        UserPreferences profile = preferences("openai", "gpt-selected", "gemini-default");

        AiExecutionOutcome outcome = module.translateTextOutcome(profile, "hello", "zh-TW");

        assertThat(outcome).isInstanceOf(AiExecutionOutcome.Success.class);
        assertThat(openAi.requests).singleElement()
                .extracting(AiProviderRequest::model)
                .isEqualTo("gpt-selected");
        AiExecutionResult result = ((AiExecutionOutcome.Success) outcome).result();
        assertThat(result.modelName()).isEqualTo("gpt-selected");
        assertThat(result.tokenUsage()).isEqualTo(new AiTokenUsage(10, 4, 14));
    }

    @Test
    void fallbackResultCarriesEveryAttemptAndTheActualSuccessfulProvider() {
        FakeAdapter openAi = new FakeAdapter("openai", "gpt-default", Set.of("gpt-default"));
        openAi.failure = failure(AiProviderException.Outcome.QUOTA_EXCEEDED, "openai", "gpt-default");
        FakeAdapter gemini = new FakeAdapter("gemini", "gemini-default", Set.of("gemini-default"));
        AiProviderExecutionModule module = module(openAi, gemini);
        UserPreferences profile = preferences("openai", "gpt-default", "gemini-default");

        AiExecutionOutcome outcome = module.translateTextOutcome(profile, "hello", "zh-TW");

        AiExecutionResult result = ((AiExecutionOutcome.Success) outcome).result();
        assertThat(result.providerName()).isEqualTo("gemini");
        assertThat(result.modelName()).isEqualTo("gemini-default");
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.attempts()).extracting(AiProviderAttempt::provider)
                .containsExactly("openai", "gemini");
        assertThat(result.attempts()).extracting(AiProviderAttempt::status)
                .containsExactly(AiProviderAttempt.Status.FAILURE, AiProviderAttempt.Status.SUCCESS);
    }

    @Test
    void terminalProviderFailureIsReturnedAsStructuredData() {
        FakeAdapter openAi = new FakeAdapter("openai", "gpt-default", Set.of("gpt-default"));
        openAi.failure = failure(AiProviderException.Outcome.SAFETY_BLOCKED, "openai", "gpt-default");
        FakeAdapter gemini = new FakeAdapter("gemini", "gemini-default", Set.of("gemini-default"));
        AiProviderExecutionModule module = module(openAi, gemini);

        AiExecutionOutcome outcome = module.translateTextOutcome(
                preferences("openai", "gpt-default", "gemini-default"),
                "unsafe",
                "zh-TW");

        assertThat(outcome).isInstanceOf(AiExecutionOutcome.Failure.class);
        AiExecutionFailure failure = ((AiExecutionOutcome.Failure) outcome).failure();
        assertThat(failure.outcome()).isEqualTo(AiProviderException.Outcome.SAFETY_BLOCKED);
        assertThat(failure.attempts()).hasSize(1);
        assertThat(gemini.requests).isEmpty();
    }

    @Test
    void unsupportedCapabilityFailsBeforeCallingTheAdapter() {
        FakeAdapter openAi = new FakeAdapter("openai", "gpt-default", Set.of("gpt-default"));
        openAi.capabilities = Set.of(AiProviderOperation.GENERATE_TEXT);

        AiExecutionOutcome outcome = module(openAi).translateTextOutcome(
                preferences("openai", "gpt-default", "gemini-default"),
                "hello",
                "zh-TW");

        AiExecutionFailure failure = ((AiExecutionOutcome.Failure) outcome).failure();
        assertThat(failure.outcome()).isEqualTo(AiProviderException.Outcome.CONFIGURATION_ERROR);
        assertThat(failure.reason()).isEqualTo("UNSUPPORTED_OPERATION");
        assertThat(openAi.requests).isEmpty();
    }

    private static AiProviderExecutionModule module(AiProviderAdapter... adapters) {
        return new AiProviderExecutionModule(List.of(adapters), "openai");
    }

    private static AiProviderException failure(
            AiProviderException.Outcome outcome,
            String provider,
            String model) {
        return new AiProviderException(
                outcome,
                provider,
                model,
                outcome.name(),
                "correlation-1",
                -1,
                null);
    }

    private static final class FakeAdapter implements AiProviderAdapter {
        private final String provider;
        private final String defaultModel;
        private final Set<String> models;
        private final List<AiProviderRequest> requests = new ArrayList<>();
        private Set<AiProviderOperation> capabilities = Set.of(AiProviderOperation.values());
        private AiProviderException failure;

        private FakeAdapter(String provider, String defaultModel, Set<String> models) {
            this.provider = provider;
            this.defaultModel = defaultModel;
            this.models = models;
        }

        @Override
        public String providerName() {
            return provider;
        }

        @Override
        public String defaultModel() {
            return defaultModel;
        }

        @Override
        public Set<String> availableModels() {
            return models;
        }

        @Override
        public Set<AiProviderOperation> capabilities() {
            return capabilities;
        }

        @Override
        public AiProviderResponse execute(AiProviderRequest request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return new AiProviderResponse(
                    "translated",
                    request.model(),
                    new AiTokenUsage(10, 4, 14));
        }
    }
}
