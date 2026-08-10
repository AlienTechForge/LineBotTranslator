package com.linetranslate.bot.service.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.logging.SafeLog;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class MinioStorageService {

    private MinioClient minioClient;
    private String bucketName;

    @Autowired(required = false)
    public MinioStorageService(MinioClient minioClient, @Value("${minio.bucket-name}") String bucketName) {
        this.minioClient = minioClient;
        this.bucketName = bucketName;
        
        // 檢查 MinioClient 是否為 null
        if (this.minioClient == null) {
            log.warn("MinIO 客戶端為 null，MinIO 功能將被禁用");
            return;
        }
        
        try {
            initializeBucket();
        } catch (Exception e) {
            log.error("初始化 MinIO 存儲桶失敗，但應用程式將繼續運行: failure={}",
                    SafeLog.failure(e));
            // 不拋出異常，讓應用程式繼續運行
        }
    }

    /**
     * 初始化 MinIO 存儲桶
     */
    private void initializeBucket() {
        try {
            boolean bucketExists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!bucketExists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("創建 MinIO 存儲桶: {}", bucketName);
            } else {
                log.info("MinIO 存儲桶已存在: {}", bucketName);
            }
        } catch (Exception e) {
            log.error("初始化 MinIO 存儲桶失敗: failure={}", SafeLog.failure(e));
            throw new IllegalStateException("初始化 MinIO 存儲桶失敗");
        }
    }

    /**
     * 上傳圖片到 MinIO
     *
     * @param imageBytes 圖片字節數組
     * @param contentType 圖片內容類型 (例如 "image/jpeg", "image/png")
     * @return 圖片的 URL，如果上傳失敗則返回 null
     */
    public String uploadImage(byte[] imageBytes, String contentType) {
        // 檢查 MinioClient 是否為 null
        if (minioClient == null) {
            log.warn("MinIO 客戶端為 null，無法上傳圖片");
            return null;
        }
        
        try {
            String objectName = generateObjectName(contentType);
            
            try (InputStream inputStream = new ByteArrayInputStream(imageBytes)) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucketName)
                                .object(objectName)
                                .stream(inputStream, imageBytes.length, -1)
                                .contentType(contentType)
                                .build());
            }
            
            String imageUrl = getPresignedUrl(objectName);
            log.info("圖片上傳成功: object={}", objectName);
            return imageUrl;
        } catch (Exception e) {
            log.error("上傳圖片到 MinIO 失敗: failure={}", SafeLog.failure(e));
            // 返回 null 而不是拋出異常，讓應用程式能夠繼續運行
            return null;
        }
    }

    /** Performs a read-only bucket accessibility check for development diagnostics and health. */
    public boolean isAvailable() {
        if (minioClient == null) {
            return false;
        }
        try {
            return minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        } catch (Exception exception) {
            log.warn("MinIO availability check failed: failure={}", SafeLog.failure(exception));
            return false;
        }
    }

    /**
     * 獲取對象的預簽名 URL
     *
     * @param objectName 對象名稱
     * @return 預簽名 URL，如果獲取失敗則返回臨時 URL
     */
    public String getPresignedUrl(String objectName) {
        // 檢查 MinioClient 是否為 null
        if (minioClient == null) {
            log.warn("MinIO 客戶端為 null，無法獲取預簽名 URL");
            // 返回一個臨時 URL
            return "http://192.168.0.10:9000/" + bucketName + "/" + objectName;
        }
        
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .method(Method.GET)
                            .expiry(7, TimeUnit.DAYS)
                            .build());
        } catch (Exception e) {
            log.error("獲取預簽名 URL 失敗: failure={}", SafeLog.failure(e));
            // 返回一個臨時 URL，讓應用程式能夠繼續運行
            return "http://192.168.0.10:9000/" + bucketName + "/" + objectName;
        }
    }

    /**
     * 生成唯一的對象名稱
     *
     * @param contentType 內容類型
     * @return 對象名稱
     */
    private String generateObjectName(String contentType) {
        String extension = "";
        if (contentType != null) {
            if (contentType.equals("image/jpeg")) {
                extension = ".jpg";
            } else if (contentType.equals("image/png")) {
                extension = ".png";
            } else if (contentType.equals("image/gif")) {
                extension = ".gif";
            } else {
                extension = ".bin";
            }
        }
        
        String uuid = UUID.randomUUID().toString();
        return "images/" + uuid + extension;
    }
}
