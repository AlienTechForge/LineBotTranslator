package com.linetranslate.bot.health;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.imagetranslator.ImageTranslatorClient;
import com.linetranslate.bot.service.imagetranslator.ImageTranslatorException;
import com.linetranslate.bot.service.imagetranslator.ImageTranslatorHealth;

import lombok.extern.slf4j.Slf4j;

/**
 * Liveness of the image-translator service: its {@code GET /healthz} within three seconds. UP carries
 * the service's version and this month's budget numbers as details, DOWN names why it did not answer,
 * and DISABLED means the bot does not use the service (OCR off, or {@code app.image-translator.enabled}
 * false). It belongs to the liveness group only: a dead service must not take the bot out of
 * rotation, because text messages do not need it. Only the changes of state are logged.
 */
@Slf4j
public final class ImageTranslatorHealthIndicator implements HealthIndicator {

    private final ImageTranslatorClient client;
    private final BooleanSupplier enabled;
    private final AtomicReference<State> state = new AtomicReference<>(State.UNKNOWN);

    public ImageTranslatorHealthIndicator(ImageTranslatorClient client, BooleanSupplier enabled) {
        this.client = client;
        this.enabled = enabled;
    }

    @Override
    public Health health() {
        if (!enabled.getAsBoolean()) {
            return Health.status(DependencyHealthConfig.DISABLED).build();
        }
        try {
            ImageTranslatorHealth answer = client.health();
            if (!answer.up()) {
                return down("unexpected_status", null);
            }
            State previous = state.getAndSet(State.READY);
            if (previous != State.READY) {
                log.info("Image translator liveness changed: state=up");
            }
            Health.Builder up = Health.up();
            if (answer.version() != null) {
                up.withDetail("version", answer.version());
            }
            if (answer.mock() != null) {
                up.withDetail("mock", answer.mock());
            }
            budget(answer.budget()).ifPresent(numbers -> up.withDetail("budget", numbers));
            return up.build();
        } catch (ImageTranslatorException failure) {
            return down(failure.kind().name().toLowerCase(java.util.Locale.ROOT), failure);
        }
    }

    private Health down(String reason, ImageTranslatorException failure) {
        State previous = state.getAndSet(State.UNAVAILABLE);
        if (previous != State.UNAVAILABLE) {
            log.warn("Image translator liveness changed: state=down, reason={}, failure={}",
                    reason, SafeLog.failure(failure));
        }
        return Health.down().withDetail("reason", reason).build();
    }

    private static java.util.Optional<Map<String, Object>> budget(ImageTranslatorHealth.Budget budget) {
        if (budget == null) {
            return java.util.Optional.empty();
        }
        Map<String, Object> numbers = new LinkedHashMap<>();
        numbers.put("month", budget.month());
        numbers.put("openrouterSpentUsd", budget.openrouterSpentUsd());
        numbers.put("openrouterBudgetUsd", budget.openrouterBudgetUsd());
        numbers.put("anthropicSpentUsd", budget.anthropicSpentUsd());
        numbers.put("anthropicBudgetUsd", budget.anthropicBudgetUsd());
        return java.util.Optional.of(numbers);
    }

    private enum State {
        UNKNOWN,
        READY,
        UNAVAILABLE
    }
}
