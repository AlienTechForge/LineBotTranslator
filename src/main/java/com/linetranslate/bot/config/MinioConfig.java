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

    @Value("${minio.public-endpoint:${MINIO_PUBLIC_ENDPOINT:${MINIO_ENDPOINT:http://localhost:9000}}}")
    private String publicEndpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Value("${minio.region:${MINIO_REGION:us-east-1}}")
    private String region;

    @Value("${minio.connect-timeout-ms:${MINIO_CONNECT_TIMEOUT_MS:3000}}")
    private long connectTimeoutMs;

    @Value("${minio.write-timeout-ms:${MINIO_WRITE_TIMEOUT_MS:5000}}")
    private long writeTimeoutMs;

    @Value("${minio.read-timeout-ms:${MINIO_READ_TIMEOUT_MS:5000}}")
    private long readTimeoutMs;

    @Bean
    @ConditionalOnProperty(prefix = "minio", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MinioClient minioClient() {
        return buildClient(endpoint, "internal storage");
    }

    @Bean(name = "minioPublicClient")
    @ConditionalOnProperty(prefix = "minio", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MinioClient minioPublicClient() {
        return buildClient(publicEndpoint, "public URL signer");
    }

    private MinioClient buildClient(String clientEndpoint, String purpose) {
        log.info("初始化 MinIO 客戶端，purpose={}, endpoint={}",
                purpose, SafeLog.endpoint(clientEndpoint));

        if (accessKey == null || accessKey.isEmpty() || secretKey == null || secretKey.isEmpty()) {
            log.warn("未設定 MinIO AccessKey 或 SecretKey，MinIO {} client 停用", purpose);
            return null;
        }

        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(clientEndpoint)
                .credentials(accessKey, secretKey);
        if (region != null && !region.isBlank()) {
            if (!region.matches("[A-Za-z0-9-]{1,63}")) {
                throw new IllegalArgumentException("MinIO region is invalid");
            }
            builder.region(region);
        }
        MinioClient client = builder.build();
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
