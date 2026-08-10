package com.linetranslate.bot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linetranslate.bot.logging.SafeLog;

import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Value("${minio.connect-timeout-ms:${MINIO_CONNECT_TIMEOUT_MS:3000}}")
    private long connectTimeoutMs;

    @Value("${minio.write-timeout-ms:${MINIO_WRITE_TIMEOUT_MS:5000}}")
    private long writeTimeoutMs;

    @Value("${minio.read-timeout-ms:${MINIO_READ_TIMEOUT_MS:5000}}")
    private long readTimeoutMs;

    @Bean
    @ConditionalOnProperty(prefix = "minio", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MinioClient minioClient() {
        log.info("初始化 MinIO 客戶端，endpoint: {}", SafeLog.endpoint(endpoint));
        
        // 檢查 AccessKey 和 SecretKey 是否為空
        if (accessKey == null || accessKey.isEmpty() || secretKey == null || secretKey.isEmpty()) {
            log.warn("未設定 MinIO AccessKey 或 SecretKey，將使用模擬的 MinIO 客戶端");
            // 返回一個空的代理對象，避免在實際使用時拋出異常
            return null;
        }
        
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        client.setTimeout(
                positive(connectTimeoutMs, "connect timeout"),
                positive(writeTimeoutMs, "write timeout"),
                positive(readTimeoutMs, "read timeout"));
        return client;
    }

    private long positive(long value, String propertyName) {
        if (value <= 0) {
            throw new IllegalArgumentException("MinIO " + propertyName + " must be greater than zero");
        }
        return value;
    }
}
