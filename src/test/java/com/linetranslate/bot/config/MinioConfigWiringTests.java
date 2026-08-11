package com.linetranslate.bot.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.linetranslate.bot.service.storage.MinioStorageService;

class MinioConfigWiringTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MinioConfig.class, MinioStorageService.class)
            .withPropertyValues(
                    "minio.enabled=true",
                    "minio.endpoint=http://127.0.0.1:9000",
                    "minio.public-endpoint=https://s3.example.com",
                    "minio.access-key=linebot-test",
                    "minio.secret-key=linebot-test-secret",
                    "minio.bucket-name=line-bot",
                    "minio.region=us-east-1",
                    "minio.retry-interval-ms=30000");

    @Test
    void internalAndPublicClientsAreWiredToTheirQualifiedRoles() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MinioStorageService.class);
            assertThat(context).hasBean("minioClient");
            assertThat(context).hasBean("minioPublicClient");
        });
    }
}
