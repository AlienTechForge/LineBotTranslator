package com.linetranslate.bot.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.storage.MinioStorageService;

@ExtendWith(MockitoExtension.class)
class DevelopmentDiagnosticSafetyTests {

    @Mock
    private TranslationRecordRepository translationRecordRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private MinioStorageService minioStorageService;

    private TestController controller;

    @BeforeEach
    void setUp() {
        controller = new TestController(
                translationRecordRepository,
                userProfileRepository,
                minioStorageService);
    }

    @Test
    void databaseDiagnosticIsReadOnlyAndReturnsNoCountsOrIdentifiers() {
        when(translationRecordRepository.count()).thenReturn(7L);

        Map<String, Object> result = controller.testDatabase();

        assertThat(result)
                .containsEntry("status", "success")
                .containsEntry("databaseAvailable", true)
                .doesNotContainKeys("totalRecords", "userProfileId", "translationRecordId");
        verify(userProfileRepository, never()).save(any(UserProfile.class));
        verify(translationRecordRepository, never()).save(any(TranslationRecord.class));
    }

    @Test
    void minioDiagnosticIsReadOnlyAndReturnsNoSignedUrl() {
        when(minioStorageService.isAvailable()).thenReturn(true);

        Map<String, Object> result = controller.testMinio();

        assertThat(result)
                .containsEntry("status", "success")
                .containsEntry("storageAvailable", true)
                .doesNotContainKeys("imageUrl", "imageUploaded", "error");
        verify(minioStorageService, never()).uploadImage(any(byte[].class), any(String.class));
    }
}
