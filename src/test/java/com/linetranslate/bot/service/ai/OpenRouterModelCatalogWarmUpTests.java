package com.linetranslate.bot.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class OpenRouterModelCatalogWarmUpTests {

    private final Logger logger =
            (Logger) org.slf4j.LoggerFactory.getLogger(OpenRouterModelCatalogWarmUp.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final AiModelCatalog catalog = mock(AiModelCatalog.class);

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void enabledWarmUpAsksTheCatalogToLoadOnce() {
        new OpenRouterModelCatalogWarmUp(catalog, true).warmUp();

        verify(catalog, times(1)).warmUp();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void disabledWarmUpNeverTouchesTheCatalog() {
        new OpenRouterModelCatalogWarmUp(catalog, false).warmUp();

        verifyNoInteractions(catalog);
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("warm-up disabled");
        });
    }

    @Test
    void aFailingWarmUpIsSwallowedAndLoggedOnceWithoutTheExceptionMessage() {
        doThrow(new IllegalStateException("Bearer sk-or-v1-secret https://openrouter.ai/api?key=abc"))
                .when(catalog).warmUp();

        assertThatCode(() -> new OpenRouterModelCatalogWarmUp(catalog, true).warmUp())
                .doesNotThrowAnyException();

        verify(catalog, times(1)).warmUp();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("failure=IllegalStateException")
                    .doesNotContain("sk-or-v1-secret", "key=abc");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void applicationReadyStartsTheWarmUpByDefault() {
        contextRunner().run(context -> {
            verify(catalog, never()).warmUp();

            publishReady(context);

            verify(catalog, times(1)).warmUp();
        });
    }

    @Test
    void thePropertyAndTheEnvironmentVariableBothSwitchTheWarmUpOff() {
        contextRunner().withPropertyValues("app.openrouter.catalog.warm-up=false").run(context -> {
            publishReady(context);

            verify(catalog, never()).warmUp();
        });
        contextRunner().withPropertyValues("OPEN_ROUTE_CATALOG_WARMUP=false").run(context -> {
            publishReady(context);

            verify(catalog, never()).warmUp();
        });
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withBean(AiModelCatalog.class, () -> catalog)
                .withBean(OpenRouterModelCatalogWarmUp.class);
    }

    private static void publishReady(ConfigurableApplicationContext context) {
        context.publishEvent(new ApplicationReadyEvent(
                new SpringApplication(), new String[0], context, Duration.ZERO));
    }
}
