package com.linetranslate.bot.health;

import java.util.concurrent.atomic.AtomicReference;

import org.bson.Document;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import com.linetranslate.bot.logging.SafeLog;
import com.mongodb.client.MongoClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Mongo readiness check that logs only dependency state transitions.
 */
@Slf4j
public final class MongoDependencyHealthIndicator implements HealthIndicator {

    private final MongoClient mongoClient;
    private final String databaseName;
    private final String safeEndpoint;
    private final AtomicReference<State> state = new AtomicReference<>(State.UNKNOWN);

    public MongoDependencyHealthIndicator(
            MongoClient mongoClient,
            String databaseName,
            String safeEndpoint) {
        this.mongoClient = mongoClient;
        this.databaseName = databaseName;
        this.safeEndpoint = safeEndpoint;
    }

    @Override
    public Health health() {
        try {
            mongoClient.getDatabase(databaseName).runCommand(new Document("ping", 1));
            State previous = state.getAndSet(State.READY);
            if (previous != State.READY) {
                log.info(
                        "MongoDB readiness changed: state=ready, endpoint={}",
                        SafeLog.endpoint(safeEndpoint));
            }
            return Health.up().build();
        } catch (Exception failure) {
            State previous = state.getAndSet(State.UNAVAILABLE);
            if (previous != State.UNAVAILABLE) {
                log.warn(
                        "MongoDB readiness changed: state=unavailable, endpoint={}, failure={}",
                        safeEndpoint,
                        SafeLog.failure(failure));
            }
            return Health.down().build();
        }
    }

    private enum State {
        UNKNOWN,
        READY,
        UNAVAILABLE
    }
}
