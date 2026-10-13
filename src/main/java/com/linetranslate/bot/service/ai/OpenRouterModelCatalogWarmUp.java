package com.linetranslate.bot.service.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.linetranslate.bot.logging.SafeLog;

import lombok.extern.slf4j.Slf4j;

/**
 * Starts the first OpenRouter model catalog fetch once the application is ready.
 * Without it nothing loads the catalog until the first lookup, which then waits
 * for the whole OpenRouter round trip and, if that fetch fails, sees the default
 * model unpriced. The fetch runs on the catalog's own refresh executor, so
 * startup never waits for it, and a failure is logged and leaves the catalog lazy.
 * {@code app.openrouter.catalog.warm-up=false} (env {@code OPEN_ROUTE_CATALOG_WARMUP})
 * turns it off.
 */
@Component
@Slf4j
public class OpenRouterModelCatalogWarmUp {

    private final AiModelCatalog catalog;
    private final boolean enabled;

    public OpenRouterModelCatalogWarmUp(
            AiModelCatalog catalog,
            @Value("${app.openrouter.catalog.warm-up:${OPEN_ROUTE_CATALOG_WARMUP:true}}") boolean enabled) {
        this.catalog = catalog;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!enabled) {
            log.info("OpenRouter model catalog warm-up disabled");
            return;
        }
        try {
            catalog.warmUp();
        } catch (RuntimeException failure) {
            log.warn("OpenRouter model catalog warm-up failed, the catalog stays lazy: failure={}",
                    SafeLog.failure(failure));
        }
    }
}
