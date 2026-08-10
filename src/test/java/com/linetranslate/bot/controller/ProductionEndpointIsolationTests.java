package com.linetranslate.bot.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.storage.MinioStorageService;

@WebMvcTest(TestController.class)
@ActiveProfiles("prod")
class ProductionEndpointIsolationTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TranslationRecordRepository translationRecordRepository;

    @MockitoBean
    private UserProfileRepository userProfileRepository;

    @MockitoBean
    private MinioStorageService minioStorageService;

    @MockitoBean
    private CacheManager cacheManager;

    @Test
    void databaseDiagnosticIsNotRegisteredInProduction() throws Exception {
        mockMvc.perform(get("/api/test/db"))
                .andExpect(status().isNotFound());
    }

    @Test
    void minioDiagnosticIsNotRegisteredInProduction() throws Exception {
        mockMvc.perform(get("/api/test/minio"))
                .andExpect(status().isNotFound());
    }
}
