package com.linetranslate.bot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import lombok.Getter;

@Configuration
@PropertySource("classpath:application.properties")
@Getter
public class AppConfig {

    @Value("${app.translation.default-target-language-for-chinese}")
    private String defaultTargetLanguageForChinese;

    @Value("${app.translation.default-target-language-for-others}")
    private String defaultTargetLanguageForOthers;

    @Value("${app.ocr.enabled}")
    private boolean ocrEnabled;

    @Value("${app.short-url.enabled:${SHORT_URL_ENABLED:false}}")
    private boolean shortUrlEnabled;

}
