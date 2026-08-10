package com.linetranslate.bot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.mongodb.client.MongoClient;

class MongoConfigStartupContractTests {

    @Test
    void unavailableMongoDoesNotBlockOrFailClientBeanCreation() {
        MongoConfig config = new MongoConfig();
        ReflectionTestUtils.setField(
                config,
                "mongoUri",
                "mongodb://127.0.0.1:1/linebot_test?serverSelectionTimeoutMS=100");
        ReflectionTestUtils.setField(config, "mongoDatabaseName", "linebot_test");

        MongoClient client = assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                config::mongoClient);

        try {
            assertThat(client).isNotNull();
        } finally {
            client.close();
        }
    }
}
