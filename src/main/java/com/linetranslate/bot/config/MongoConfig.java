package com.linetranslate.bot.config;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.mongodb.config.AbstractMongoClientConfiguration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.linetranslate.bot.health.MongoDependencyHealthIndicator;
import com.linetranslate.bot.logging.SafeLog;

import lombok.extern.slf4j.Slf4j;

@Configuration
@EnableMongoRepositories(basePackages = "com.linetranslate.bot.repository")
@Slf4j
public class MongoConfig extends AbstractMongoClientConfiguration {

    @Value("${mongodb.uri:${MONGODB_URI:mongodb://localhost:27017/linebot_translator}}")
    private String mongoUri;

    @Value("${mongodb.database:${MONGODB_DATABASE:linebot_translator}}")
    private String mongoDatabaseName;

    @Value("${mongodb.connect-timeout-ms:${MONGODB_CONNECT_TIMEOUT_MS:3000}}")
    private long connectTimeoutMs = 3000;

    @Value("${mongodb.read-timeout-ms:${MONGODB_READ_TIMEOUT_MS:5000}}")
    private long readTimeoutMs = 5000;

    @Value("${mongodb.server-selection-timeout-ms:${MONGODB_SERVER_SELECTION_TIMEOUT_MS:3000}}")
    private long serverSelectionTimeoutMs = 3000;

    @Value("${mongodb.heartbeat-frequency-ms:${MONGODB_HEARTBEAT_FREQUENCY_MS:5000}}")
    private long heartbeatFrequencyMs = 5000;

    @Value("${mongodb.min-heartbeat-frequency-ms:${MONGODB_MIN_HEARTBEAT_FREQUENCY_MS:500}}")
    private long minHeartbeatFrequencyMs = 500;

    @Override
    protected String getDatabaseName() {
        return mongoDatabaseName;
    }

    @Override
    @Bean(destroyMethod = "close")
    public MongoClient mongoClient() {
        try {
            log.info("初始化 MongoDB client: endpoint={}", SafeLog.endpoint(mongoUri));
            ConnectionString connectionString = new ConnectionString(mongoUri);
            MongoClientSettings mongoClientSettings = MongoClientSettings.builder()
                    .applyConnectionString(connectionString)
                    .applyToClusterSettings(builder -> builder.serverSelectionTimeout(
                            positive(serverSelectionTimeoutMs, "server selection timeout"),
                            TimeUnit.MILLISECONDS))
                    .applyToSocketSettings(builder -> builder
                            .connectTimeout(
                                    positive(connectTimeoutMs, "connect timeout"),
                                    TimeUnit.MILLISECONDS)
                            .readTimeout(
                                    positive(readTimeoutMs, "read timeout"),
                                    TimeUnit.MILLISECONDS))
                    .applyToServerSettings(builder -> builder
                            .heartbeatFrequency(
                                    positive(heartbeatFrequencyMs, "heartbeat frequency"),
                                    TimeUnit.MILLISECONDS)
                            .minHeartbeatFrequency(
                                    positive(minHeartbeatFrequencyMs, "minimum heartbeat frequency"),
                                    TimeUnit.MILLISECONDS))
                    .build();

            MongoClient client = MongoClients.create(mongoClientSettings);
            log.info("MongoDB client 已初始化；連線狀態由 readiness 檢查");
            return client;
        } catch (RuntimeException exception) {
            log.error("MongoDB client 設定無效: endpoint={}, failure={}",
                    SafeLog.endpoint(mongoUri), SafeLog.failure(exception));
            throw new IllegalStateException("MongoDB client configuration is invalid", exception);
        }
    }

    @Bean(name = "mongoHealthIndicator")
    HealthIndicator mongoHealthIndicator(MongoClient mongoClient) {
        return new MongoDependencyHealthIndicator(
                mongoClient,
                mongoDatabaseName,
                SafeLog.endpoint(mongoUri));
    }

    private long positive(long value, String propertyName) {
        if (value <= 0) {
            throw new IllegalArgumentException(propertyName + " must be greater than zero");
        }
        return value;
    }
}
