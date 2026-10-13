package com.linetranslate.bot.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linetranslate.bot.config.OpenRouterConfig;
import com.linetranslate.bot.service.usage.UsagePriceQuote;
import com.linetranslate.bot.service.usage.UsagePricingCatalog;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

class OpenRouterModelCatalogTests {

    /** Only a safety net so a regression fails instead of hanging; no test depends on it elapsing. */
    private static final long SAFETY_TIMEOUT_SECONDS = 30;

    @Test
    void catalogFiltersTextModelsSearchesAndCachesOfficialPricing() throws IOException {
        OpenRouterConfig config = mock(OpenRouterConfig.class);
        when(config.getApiKey()).thenReturn("test-key");
        when(config.getModelName()).thenReturn("openai/gpt-4o-mini");
        when(config.getApiUrl()).thenReturn("https://openrouter.ai/api/v1");
        when(config.normalizedApiUrl()).thenReturn("https://openrouter.ai/api/v1");
        when(config.getCatalogTtl()).thenReturn(java.time.Duration.ofMinutes(15));
        OkHttpClient client = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        when(client.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response("""
                {"data":[
                  {"id":"anthropic/claude-sonnet-4","name":"Claude Sonnet 4",
                   "architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},
                   "pricing":{"prompt":"0.000003","completion":"0.000015"}},
                  {"id":"openai/gpt-image-1","name":"GPT Image",
                   "architecture":{"input_modalities":["text"],"output_modalities":["image"]},
                   "pricing":{"prompt":"0.1","completion":"0.2"}}
                ]}
                """));
        OpenRouterModelCatalog catalog = new OpenRouterModelCatalog(
                config,
                client,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC));

        AiModelPage first = catalog.list("claude", 20);
        AiModelPage second = catalog.list("", 20);

        assertThat(first.total()).isEqualTo(1);
        assertThat(first.models()).singleElement().satisfies(model -> {
            assertThat(model.id()).isEqualTo("anthropic/claude-sonnet-4");
            assertThat(model.inputModalities()).containsExactlyInAnyOrder("text", "image");
            assertThat(model.promptPricePerToken()).isEqualByComparingTo(new BigDecimal("0.000003"));
        });
        assertThat(second.models()).extracting(AiModelDescriptor::id)
                .containsExactly("anthropic/claude-sonnet-4");
        assertThat(catalog.supports("anthropic/claude-sonnet-4", AiProviderOperation.PROCESS_IMAGE)).isTrue();
        assertThat(catalog.supports("anthropic/claude-sonnet-4", AiProviderOperation.TRANSLATE_TEXT)).isTrue();
        verify(client, times(1)).newCall(any(Request.class));
    }

    @Test
    void expiredSnapshotIsServedImmediatelyWhileOneBackgroundRefreshRuns() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T00:00:00Z"));
        AtomicInteger fetches = new AtomicInteger();
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        OkHttpClient client = client(fetches, fetch -> {
            if (fetch == 1) {
                return response(catalogJson("vendor/model-a"));
            }
            refreshStarted.countDown();
            await(releaseRefresh);
            return response(catalogJson("vendor/model-a", "vendor/model-b"));
        });
        ExecutorService refreshExecutor = Executors.newSingleThreadExecutor();
        OpenRouterModelCatalog catalog = new OpenRouterModelCatalog(
                config(), client, new ObjectMapper(), clock, refreshExecutor);
        try {
            assertThat(catalog.list("", 20).total()).isEqualTo(1);
            clock.advance(Duration.ofMinutes(16));

            AiModelPage expired = within(() -> catalog.list("", 20));

            assertThat(refreshStarted.await(SAFETY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(expired.models()).extracting(AiModelDescriptor::id).containsExactly("vendor/model-a");
            assertThat(expired.stale()).isFalse();
            assertThat(within(() -> catalog.find("vendor/model-b"))).isEmpty();

            releaseRefresh.countDown();
            drain(refreshExecutor);

            assertThat(catalog.find("vendor/model-b")).isPresent();
            assertThat(catalog.list("", 20).stale()).isFalse();
            assertThat(fetches).hasValue(2);
        } finally {
            releaseRefresh.countDown();
            catalog.shutdown();
        }
    }

    @Test
    void concurrentLookupsOfAnExpiredSnapshotShareOneRefresh() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T00:00:00Z"));
        AtomicInteger fetches = new AtomicInteger();
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        OkHttpClient client = client(fetches, fetch -> {
            if (fetch == 1) {
                return response(catalogJson("vendor/model-a"));
            }
            refreshStarted.countDown();
            await(releaseRefresh);
            return response(catalogJson("vendor/model-a", "vendor/model-b"));
        });
        ExecutorService refreshExecutor = Executors.newSingleThreadExecutor();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        OpenRouterModelCatalog catalog = new OpenRouterModelCatalog(
                config(), client, new ObjectMapper(), clock, refreshExecutor);
        try {
            catalog.list("", 20);
            clock.advance(Duration.ofMinutes(16));
            CountDownLatch startGate = new CountDownLatch(1);

            Future<Optional<AiModelDescriptor>> first = callers.submit(() -> {
                startGate.await();
                return catalog.find("vendor/model-a");
            });
            Future<Optional<AiModelDescriptor>> second = callers.submit(() -> {
                startGate.await();
                return catalog.find("vendor/model-a");
            });
            startGate.countDown();

            assertThat(first.get(SAFETY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isPresent();
            assertThat(second.get(SAFETY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isPresent();
            assertThat(refreshStarted.await(SAFETY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            releaseRefresh.countDown();
            drain(refreshExecutor);

            assertThat(fetches).hasValue(2);
            assertThat(catalog.find("vendor/model-b")).isPresent();
        } finally {
            releaseRefresh.countDown();
            callers.shutdownNow();
            catalog.shutdown();
        }
    }

    @Test
    void anExpiredLookupRightAfterARefreshCompletesSchedulesTheNextRefresh() throws Exception {
        OpenRouterConfig config = config();
        OkHttpClient client = client(new AtomicInteger(), fetch -> response(catalogJson("vendor/model-a")));
        // The first lookup resumes the moment its refresh completes, while the refresh thread is still
        // finishing. The window is a few instructions wide, so the sequence is repeated to hit it.
        for (int round = 0; round < 300; round++) {
            MutableClock clock = new MutableClock(Instant.parse("2026-08-11T00:00:00Z"));
            CountingExecutor refreshExecutor = new CountingExecutor();
            OpenRouterModelCatalog catalog = new OpenRouterModelCatalog(
                    config, client, new ObjectMapper(), clock, refreshExecutor);
            try {
                catalog.list("", 20);
                clock.advance(Duration.ofMinutes(16));

                catalog.list("", 20);

                // Scheduling happens on the calling thread, so no wait is involved: the expired lookup
                // either handed a second refresh to the executor or it joined the finished one.
                assertThat(refreshExecutor.submitted()).as("refreshes scheduled in round %s", round).isEqualTo(2);
            } finally {
                catalog.shutdown();
            }
        }
    }

    @Test
    void failedBackgroundRefreshKeepsServingThePreviousModelsMarkedStale() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T00:00:00Z"));
        AtomicInteger fetches = new AtomicInteger();
        OkHttpClient client = client(fetches, fetch -> {
            if (fetch == 1) {
                return response(catalogJson("vendor/model-a"));
            }
            throw new IOException("catalog unavailable");
        });
        ExecutorService refreshExecutor = Executors.newSingleThreadExecutor();
        OpenRouterModelCatalog catalog = new OpenRouterModelCatalog(
                config(), client, new ObjectMapper(), clock, refreshExecutor);
        try {
            catalog.list("", 20);
            clock.advance(Duration.ofMinutes(16));

            assertThat(within(() -> catalog.list("", 20)).stale()).isFalse();
            drain(refreshExecutor);

            AiModelPage degraded = catalog.list("", 20);
            assertThat(degraded.stale()).isTrue();
            assertThat(degraded.models()).extracting(AiModelDescriptor::id).containsExactly("vendor/model-a");
            assertThat(fetches).hasValue(2);
        } finally {
            catalog.shutdown();
        }
    }

    @Test
    void failedFirstFetchServesTheDefaultModelDegradedAndUnpriced() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T00:00:00Z"));
        AtomicInteger fetches = new AtomicInteger();
        OkHttpClient client = client(fetches, fetch -> {
            throw new IOException("catalog unavailable");
        });
        ExecutorService refreshExecutor = Executors.newSingleThreadExecutor();
        OpenRouterModelCatalog catalog = new OpenRouterModelCatalog(
                config(), client, new ObjectMapper(), clock, refreshExecutor);
        try {
            AiModelPage page = catalog.list("", 20);

            assertThat(page.stale()).isTrue();
            assertThat(page.models()).singleElement().satisfies(model -> {
                assertThat(model.id()).isEqualTo("vendor/default-model");
                assertThat(model.promptPricePerToken()).isNull();
                assertThat(model.completionPricePerToken()).isNull();
            });
            assertThat(catalog.supports("vendor/default-model", AiProviderOperation.TRANSLATE_TEXT)).isTrue();
            UsagePriceQuote quote = new UsagePricingCatalog(catalog).quote(
                    "openrouter",
                    "vendor/default-model",
                    clock.instant(),
                    new AiTokenUsage(1_000, 500, 1_500),
                    0);
            assertThat(quote.priced()).isFalse();
            assertThat(quote.pricingVersion()).isEqualTo("unpriced-v1");

            clock.advance(Duration.ofSeconds(61));
            assertThat(within(() -> catalog.list("", 20)).stale()).isTrue();
            drain(refreshExecutor);
            assertThat(fetches).hasValue(2);
        } finally {
            catalog.shutdown();
        }
    }

    @Test
    void warmUpSchedulesOneRefreshAndDoesNotStartASecondWhileOneIsInFlight() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T00:00:00Z"));
        AtomicInteger fetches = new AtomicInteger();
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        OkHttpClient client = client(fetches, fetch -> {
            fetchStarted.countDown();
            await(releaseFetch);
            return response(catalogJson("vendor/model-a"));
        });
        CountingExecutor refreshExecutor = new CountingExecutor();
        ExecutorService callers = Executors.newSingleThreadExecutor();
        OpenRouterModelCatalog catalog = new OpenRouterModelCatalog(
                config(), client, new ObjectMapper(), clock, refreshExecutor);
        try {
            within(() -> {
                catalog.warmUp();
                return null;
            });
            assertThat(fetchStarted.await(SAFETY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            catalog.warmUp();
            catalog.warmUp();
            // A lookup made while the warm-up fetch runs joins it instead of fetching again.
            Future<Optional<AiModelDescriptor>> lookup = callers.submit(() -> catalog.find("vendor/model-a"));

            assertThat(refreshExecutor.submitted()).isEqualTo(1);

            releaseFetch.countDown();
            assertThat(lookup.get(SAFETY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isPresent();
            drain(refreshExecutor);
            int scheduled = refreshExecutor.submitted();

            // The snapshot is loaded and fresh, so neither another warm-up nor a lookup fetches.
            catalog.warmUp();
            assertThat(catalog.find("vendor/model-a")).isPresent();

            assertThat(refreshExecutor.submitted()).isEqualTo(scheduled);
            assertThat(fetches).hasValue(1);
        } finally {
            releaseFetch.countDown();
            callers.shutdownNow();
            catalog.shutdown();
        }
    }

    @Test
    void warmUpDoesNotWaitForTheFetchAndTheFirstLookupFindsTheSnapshotLoaded() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        CountDownLatch releaseFetch = new CountDownLatch(1);
        OkHttpClient client = client(fetches, fetch -> {
            await(releaseFetch);
            return response(catalogJson("vendor/model-a"));
        });
        ExecutorService refreshExecutor = Executors.newSingleThreadExecutor();
        OpenRouterModelCatalog catalog = new OpenRouterModelCatalog(
                config(), client, new ObjectMapper(),
                new MutableClock(Instant.parse("2026-08-11T00:00:00Z")), refreshExecutor);
        try {
            // The fetch is blocked, so this only returns because warmUp() hands it off.
            within(() -> {
                catalog.warmUp();
                return null;
            });
            releaseFetch.countDown();
            drain(refreshExecutor);

            assertThat(within(() -> catalog.find("vendor/model-a"))).isPresent();
            assertThat(fetches).hasValue(1);
        } finally {
            releaseFetch.countDown();
            catalog.shutdown();
        }
    }

    @Test
    void failedWarmUpIsHandledByTheRefreshAndTheCatalogStaysLazy() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T00:00:00Z"));
        AtomicInteger fetches = new AtomicInteger();
        OkHttpClient client = client(fetches, fetch -> {
            if (fetch == 1) {
                throw new IOException("catalog unavailable");
            }
            return response(catalogJson("vendor/model-a"));
        });
        ExecutorService refreshExecutor = Executors.newSingleThreadExecutor();
        OpenRouterModelCatalog catalog = new OpenRouterModelCatalog(
                config(), client, new ObjectMapper(), clock, refreshExecutor);
        try {
            assertThatCode(catalog::warmUp).doesNotThrowAnyException();
            drain(refreshExecutor);

            AiModelPage degraded = catalog.list("", 20);
            assertThat(degraded.stale()).isTrue();
            assertThat(degraded.models()).extracting(AiModelDescriptor::id)
                    .containsExactly("vendor/default-model");
            assertThat(fetches).hasValue(1);

            // Later lookups retry on demand once the retry window has passed, as without a warm-up.
            clock.advance(Duration.ofSeconds(61));
            assertThat(within(() -> catalog.list("", 20)).stale()).isTrue();
            drain(refreshExecutor);

            assertThat(fetches).hasValue(2);
            assertThat(catalog.find("vendor/model-a")).isPresent();
        } finally {
            catalog.shutdown();
        }
    }

    @Test
    void warmUpAfterShutdownDoesNotFetchOnTheCallingThread() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        OkHttpClient client = client(fetches, fetch -> response(catalogJson("vendor/model-a")));
        OpenRouterModelCatalog catalog = new OpenRouterModelCatalog(
                config(), client, new ObjectMapper(),
                new MutableClock(Instant.parse("2026-08-11T00:00:00Z")), Executors.newSingleThreadExecutor());
        catalog.shutdown();

        assertThatCode(catalog::warmUp).doesNotThrowAnyException();

        assertThat(fetches).hasValue(0);
    }

    @Test
    void warmUpTheExecutorRefusesReturnsWithoutFetchingAndLeavesTheSlotFree() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        OkHttpClient client = client(fetches, fetch -> response(catalogJson("vendor/model-a")));
        // Shutdown can begin right after warmUp() has started: the executor refuses the task itself.
        RejectingExecutor refreshExecutor = new RejectingExecutor(new CountDownLatch(0));
        OpenRouterModelCatalog catalog = new OpenRouterModelCatalog(
                config(), client, new ObjectMapper(),
                new MutableClock(Instant.parse("2026-08-11T00:00:00Z")), refreshExecutor);
        try {
            within(() -> {
                catalog.warmUp();
                return null;
            });

            assertThat(refreshExecutor.rejected()).isEqualTo(1);
            assertThat(fetches).hasValue(0);

            // The refused warm-up gave the slot up, so this lookup starts a refresh of its own
            // instead of joining the abandoned flight and being answered without a fetch.
            assertThat(within(() -> catalog.find("vendor/model-a"))).isPresent();
            assertThat(fetches).hasValue(1);
        } finally {
            catalog.shutdown();
        }
    }

    @Test
    void aLookupThatJoinedAWarmUpTheExecutorRefusesStillGetsAnAnswer() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        OkHttpClient client = client(fetches, fetch -> response(catalogJson("vendor/model-a")));
        CountDownLatch refuse = new CountDownLatch(1);
        RejectingExecutor refreshExecutor = new RejectingExecutor(refuse);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        OpenRouterModelCatalog catalog = new OpenRouterModelCatalog(
                config(), client, new ObjectMapper(),
                new MutableClock(Instant.parse("2026-08-11T00:00:00Z")), refreshExecutor);
        try {
            Future<?> warmUp = callers.submit(catalog::warmUp);
            assertThat(refreshExecutor.refusalPending.await(SAFETY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            AtomicReference<Thread> lookupThread = new AtomicReference<>();
            Future<AiModelPage> lookup = callers.submit(() -> {
                lookupThread.set(Thread.currentThread());
                return catalog.list("", 20);
            });
            // The lookup parks on the warm-up's flight, which is claimed but not yet refused.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SAFETY_TIMEOUT_SECONDS);
            while ((lookupThread.get() == null || lookupThread.get().getState() != Thread.State.WAITING)
                    && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(1);
            }
            assertThat(lookupThread.get().getState()).isEqualTo(Thread.State.WAITING);

            refuse.countDown();
            warmUp.get(SAFETY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            AiModelPage page = lookup.get(SAFETY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Answered from what is known without a fetch; the joiner neither fails nor fetches inline.
            assertThat(page.stale()).isTrue();
            assertThat(page.models()).extracting(AiModelDescriptor::id).containsExactly("vendor/default-model");
            assertThat(fetches).hasValue(0);
            assertThat(catalog.find("vendor/model-a")).isPresent();
            assertThat(fetches).hasValue(1);
        } finally {
            refuse.countDown();
            callers.shutdownNow();
            catalog.shutdown();
        }
    }

    private static OpenRouterConfig config() {
        OpenRouterConfig config = mock(OpenRouterConfig.class);
        when(config.getApiKey()).thenReturn("test-key");
        when(config.getModelName()).thenReturn("vendor/default-model");
        when(config.normalizedApiUrl()).thenReturn("https://openrouter.ai/api/v1");
        when(config.getCatalogTtl()).thenReturn(Duration.ofMinutes(15));
        return config;
    }

    private static OkHttpClient client(AtomicInteger fetches, CatalogResponder responder) throws IOException {
        OkHttpClient client = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        when(client.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenAnswer(invocation -> responder.respond(fetches.incrementAndGet()));
        return client;
    }

    private static String catalogJson(String... ids) {
        return Stream.of(ids)
                .map(id -> "{\"id\":\"" + id + "\",\"name\":\"" + id + "\","
                        + "\"architecture\":{\"input_modalities\":[\"text\"],\"output_modalities\":[\"text\"]},"
                        + "\"pricing\":{\"prompt\":\"0.000001\",\"completion\":\"0.000002\"}}")
                .collect(Collectors.joining(",", "{\"data\":[", "]}"));
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(SAFETY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new InterruptedIOException("test latch was not released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("test latch wait interrupted");
        }
    }

    /** Fails instead of hanging when a lookup waits on the provider. */
    private static <T> T within(Supplier<T> lookup) throws Exception {
        return CompletableFuture.supplyAsync(lookup).get(SAFETY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** The refresh executor is single-threaded, so a no-op runs after the pending refresh. */
    private static void drain(ExecutorService refreshExecutor) throws Exception {
        refreshExecutor.submit(() -> { }).get(SAFETY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface CatalogResponder {
        Response respond(int fetch) throws IOException;
    }

    /** Single-threaded like the production executor, and counts what the catalog hands to it. */
    private static final class CountingExecutor extends ThreadPoolExecutor {

        private final AtomicInteger submitted = new AtomicInteger();

        CountingExecutor() {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        public void execute(Runnable task) {
            submitted.incrementAndGet();
            super.execute(task);
        }

        int submitted() {
            return submitted.get();
        }
    }

    /**
     * Refuses its first task like an executor that was shut down just before the catalog submitted it,
     * once {@code refuse} is released, then accepts the rest.
     */
    private static final class RejectingExecutor extends ThreadPoolExecutor {

        final CountDownLatch refusalPending = new CountDownLatch(1);
        private final CountDownLatch refuse;
        private final AtomicInteger rejected = new AtomicInteger();

        RejectingExecutor(CountDownLatch refuse) {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
            this.refuse = refuse;
        }

        @Override
        public void execute(Runnable task) {
            if (rejected.get() == 0) {
                refusalPending.countDown();
                try {
                    refuse.await(SAFETY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                rejected.incrementAndGet();
                throw new RejectedExecutionException("test executor refuses the first task");
            }
            super.execute(task);
        }

        int rejected() {
            return rejected.get();
        }
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static Response response(String body) {
        Request request = new Request.Builder().url("https://openrouter.ai/api/v1/models").build();
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
    }
}
