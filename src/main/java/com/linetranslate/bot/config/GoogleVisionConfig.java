package com.linetranslate.bot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.ImageAnnotatorSettings;
import com.linetranslate.bot.logging.SafeLog;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
@Getter
@Slf4j
public class GoogleVisionConfig {

    @Value("${google.cloud.vision.api.key:#{null}}")
    private String apiKey;

    @Value("${GOOGLE_APPLICATION_CREDENTIALS:./linebot.json}")
    private String credentialsPath;

    @Bean
    public ImageAnnotatorClient imageAnnotatorClient() {
        try {
            // 首先檢查環境變數
            String envCredentialsPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");

            // 如果環境變數存在且不為空，優先使用
            if (envCredentialsPath != null && !envCredentialsPath.isEmpty()) {
                log.info("Google Vision 認證已透過環境變數配置");

                Path path = Paths.get(envCredentialsPath);
                if (Files.exists(path)) {
                    log.info("環境變數指定的認證文件存在");
                    return ImageAnnotatorClient.create();
                } else {
                    log.error("環境變數指定的 Google Vision 認證文件不存在");
                }
            }

            // 嘗試使用配置文件中指定的路徑
            log.info("嘗試使用已配置的 Google Vision 認證文件");
            Path credentialsFile = Paths.get(credentialsPath);

            if (Files.isRegularFile(credentialsFile)) {
                log.info("找到 Google Vision 認證文件");

                try (FileInputStream serviceAccountStream = new FileInputStream(credentialsFile.toFile())) {
                    GoogleCredentials credentials = GoogleCredentials.fromStream(serviceAccountStream);
                    ImageAnnotatorSettings settings = ImageAnnotatorSettings.newBuilder()
                            .setCredentialsProvider(() -> credentials)
                            .build();

                    log.info("成功載入Google Vision認證文件");
                    return ImageAnnotatorClient.create(settings);
                } catch (IOException e) {
                    log.error("讀取認證文件失敗: failure={}", SafeLog.failure(e));
                    return null;
                }
            } else {
                log.warn("未找到已配置的 Google Vision API 認證文件");
                return null;
            }
        } catch (IOException e) {
            log.error("無法創建 Google Vision 客戶端: failure={}", SafeLog.failure(e));
            return null;
        }
    }
}
