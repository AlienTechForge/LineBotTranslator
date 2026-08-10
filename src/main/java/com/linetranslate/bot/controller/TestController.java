package com.linetranslate.bot.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.storage.MinioStorageService;

import lombok.extern.slf4j.Slf4j;

/**
 * 測試控制器，用於驗證資料庫連接和儲存功能
 * 僅用於開發和測試環境
 */
@RestController
@RequestMapping("/api/test")
@Profile({"dev", "test"})
@Slf4j
public class TestController {

    private final TranslationRecordRepository translationRecordRepository;
    private final UserProfileRepository userProfileRepository;
    private final MinioStorageService minioStorageService;

    @Autowired
    public TestController(
            TranslationRecordRepository translationRecordRepository,
            UserProfileRepository userProfileRepository,
            MinioStorageService minioStorageService) {
        this.translationRecordRepository = translationRecordRepository;
        this.userProfileRepository = userProfileRepository;
        this.minioStorageService = minioStorageService;
    }

    /**
     * 測試資料庫連接和儲存功能
     */
    @GetMapping("/db")
    public Map<String, Object> testDatabase() {
        Map<String, Object> result = new HashMap<>();

        try {
            translationRecordRepository.count();
            result.put("databaseAvailable", true);
            result.put("status", "success");
            log.info("開發資料庫診斷成功");
        } catch (Exception e) {
            log.error("開發資料庫診斷失敗: failure={}", SafeLog.failure(e));
            result.put("status", "error");
            result.put("databaseAvailable", false);
            result.put("message", "資料庫診斷失敗");
        }

        return result;
    }
    
    /**
     * 測試 MinIO 連接和儲存功能
     */
    @GetMapping("/minio")
    public Map<String, Object> testMinio() {
        Map<String, Object> result = new HashMap<>();

        try {
            boolean available = minioStorageService.isAvailable();
            result.put("storageAvailable", available);
            result.put("status", "success");
            log.info("開發 MinIO 診斷完成: available={}", available);
        } catch (Exception e) {
            log.error("開發 MinIO 診斷失敗: failure={}", SafeLog.failure(e));
            result.put("status", "error");
            result.put("storageAvailable", false);
            result.put("message", "MinIO 診斷失敗");
        }

        return result;
    }
}
