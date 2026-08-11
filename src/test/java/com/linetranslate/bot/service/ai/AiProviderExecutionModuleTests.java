package com.linetranslate.bot.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.linetranslate.bot.service.preference.UserPreferences;
import com.linetranslate.bot.service.settings.RuntimeSettings;
import com.linetranslate.bot.service.translation.TranslationStylePreset;
import com.linetranslate.bot.service.usage.AiUsageEventSink;

class AiProviderExecutionModuleTests {

    @Test
    void selectedModelIsPlannedAndSentToTheSingleOpenRouterAdapter() {
        FakeAdapter adapter = new FakeAdapter("openai/gpt-4o-mini",
                Set.of("openai/gpt-4o-mini", "anthropic/claude-sonnet-4"));
        AiProviderExecutionModule module = module(adapter);
        UserPreferences preferences = preferences("anthropic/claude-sonnet-4");

        assertThat(module.planText(preferences))
                .isEqualTo(new AiProviderRoute("openrouter", "anthropic/claude-sonnet-4"));
        AiExecutionOutcome outcome = module.translateTextOutcome(preferences, "hello", "zh-TW");

        assertThat(outcome).isInstanceOf(AiExecutionOutcome.Success.class);
        assertThat(adapter.requests).singleElement()
                .extracting(AiProviderRequest::model)
                .isEqualTo("anthropic/claude-sonnet-4");
        assertThat(((AiExecutionOutcome.Success) outcome).result().fallbackUsed()).isFalse();
    }

    @Test
    void providerFailureIsTerminalStructuredDataWithoutFallback() {
        FakeAdapter adapter = new FakeAdapter("openai/gpt-4o-mini", Set.of("openai/gpt-4o-mini"));
        adapter.failure = failure(AiProviderException.Outcome.RATE_LIMITED, "openai/gpt-4o-mini");

        AiExecutionOutcome outcome = module(adapter).translateTextOutcome(
                preferences("openai/gpt-4o-mini"), "hello", "zh-TW");

        AiExecutionFailure failure = ((AiExecutionOutcome.Failure) outcome).failure();
        assertThat(failure.outcome()).isEqualTo(AiProviderException.Outcome.RATE_LIMITED);
        assertThat(failure.attempts()).hasSize(1);
    }

    @Test
    void versionedStyleContractReachesTheOnlyProviderAdapter() {
        FakeAdapter adapter = new FakeAdapter(
                "openai/gpt-4o-mini", Set.of("openai/gpt-4o-mini"));

        module(adapter).translateTextOutcome(
                preferences("openai/gpt-4o-mini"),
                "hello",
                "zh-TW",
                TranslationStylePreset.BUSINESS);

        assertThat(adapter.requests).singleElement().satisfies(request -> {
            assertThat(request.translationStyleId()).isEqualTo("business");
            assertThat(request.translationPromptVersion()).isEqualTo("business-v1");
            assertThat(request.translationStyleInstruction())
                    .isEqualTo(TranslationStylePreset.BUSINESS.promptRule());
        });
    }

    @Test
    void runtimeDefaultModelIsReadDynamically() {
        FakeAdapter adapter = new FakeAdapter("openai/gpt-4o-mini",
                Set.of("openai/gpt-4o-mini", "anthropic/claude-sonnet-4"));
        AtomicReference<RuntimeSettings> runtime = new AtomicReference<>(settings("openai/gpt-4o-mini"));
        AiProviderExecutionModule module = new AiProviderExecutionModule(List.of(adapter), runtime::get);

        module.generateTextOutcome(null, "first");
        runtime.set(settings("anthropic/claude-sonnet-4"));
        module.generateTextOutcome(null, "second");

        assertThat(adapter.requests).extracting(AiProviderRequest::model)
                .containsExactly("openai/gpt-4o-mini", "anthropic/claude-sonnet-4");
    }

    @Test
    void completedOutcomeIsSentToAccountingAndAccountingOutageIsIsolated() {
        FakeAdapter adapter = new FakeAdapter("openai/gpt-4o-mini", Set.of("openai/gpt-4o-mini"));
        AiUsageEventSink sink = mock(AiUsageEventSink.class);
        AiProviderExecutionModule module = new AiProviderExecutionModule(
                List.of(adapter), () -> settings("openai/gpt-4o-mini"), sink);

        AiExecutionOutcome first = module.translateTextOutcome(
                preferences("openai/gpt-4o-mini"), "hello", "zh-TW");
        verify(sink).record(AiProviderOperation.TRANSLATE_TEXT, first);

        doThrow(new IllegalStateException("mongo unavailable"))
                .when(sink).record(
                        org.mockito.ArgumentMatchers.eq(AiProviderOperation.TRANSLATE_TEXT),
                        org.mockito.ArgumentMatchers.any(AiExecutionOutcome.class));
        assertThat(module.translateTextOutcome(
                preferences("openai/gpt-4o-mini"), "again", "zh-TW"))
                .isInstanceOf(AiExecutionOutcome.Success.class);
    }

    private static AiProviderExecutionModule module(AiProviderAdapter adapter) {
        return new AiProviderExecutionModule(List.of(adapter), () -> settings(adapter.defaultModel()));
    }

    private static RuntimeSettings settings(String model) {
        return new RuntimeSettings("en", "zh-TW", model, true,
                2, 1, null, "U-admin", RuntimeSettings.Source.PERSISTED);
    }

    private static UserPreferences preferences(String model) {
        return new UserPreferences("en", "en", "en", model, List.of());
    }

    private static AiProviderException failure(AiProviderException.Outcome outcome, String model) {
        return new AiProviderException(
                outcome, "openrouter", model, outcome.name(), "correlation-1", 429, null);
    }

    private static final class FakeAdapter implements AiProviderAdapter {
        private final String defaultModel;
        private final Set<String> models;
        private final List<AiProviderRequest> requests = new ArrayList<>();
        private AiProviderException failure;

        private FakeAdapter(String defaultModel, Set<String> models) {
            this.defaultModel = defaultModel;
            this.models = models;
        }

        public String providerName() { return "openrouter"; }
        public String defaultModel() { return defaultModel; }
        public Set<String> availableModels() { return models; }
        public Set<AiProviderOperation> capabilities() { return Set.of(AiProviderOperation.values()); }

        public AiProviderResponse execute(AiProviderRequest request) {
            requests.add(request);
            if (failure != null) throw failure;
            return new AiProviderResponse("translated", request.model(), new AiTokenUsage(10, 4, 14));
        }
    }
}
