package com.linetranslate.bot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.AbstractMongoClientConfiguration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.linetranslate.bot.logging.SafeLog;
import org.bson.Document;

import lombok.extern.slf4j.Slf4j;

@Configuration
@EnableMongoRepositories(basePackages = "com.linetranslate.bot.repository")
@Slf4j
public class MongoConfig extends AbstractMongoClientConfiguration {

    @Value("${mongodb.uri:${MONGODB_URI:mongodb://localhost:27017/linebot_translator}}")
    private String mongoUri;

    @Value("${mongodb.database:${MONGODB_DATABASE:linebot_translator}}")
    private String mongoDatabaseName;

    @Override
    protected String getDatabaseName() {
        return mongoDatabaseName;
    }

    @Override
    @Bean
    public MongoClient mongoClient() {
        try {
            log.info("嘗試連接到 MongoDB: endpoint={}", SafeLog.endpoint(mongoUri));
            ConnectionString connectionString = new ConnectionString(mongoUri);
            MongoClientSettings mongoClientSettings = MongoClientSettings.builder()
                    .applyConnectionString(connectionString)
                    .build();

            MongoClient client = MongoClients.create(mongoClientSettings);
            // 測試連接
            client.getDatabase(mongoDatabaseName).runCommand(new Document("ping", 1));
            log.info("MongoDB 連接成功");
            return client;
        } catch (Exception e) {
            log.error("無法連接到 MongoDB: endpoint={}, failure={}",
                    SafeLog.endpoint(mongoUri), SafeLog.failure(e));
            throw new IllegalStateException("MongoDB 連接失敗");
        }
    }
}
