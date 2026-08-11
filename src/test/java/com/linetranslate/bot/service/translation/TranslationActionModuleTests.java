package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

import com.linetranslate.bot.model.TranslationActionClaim;
import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.repository.TranslationActionClaimRepository;
import com.linetranslate.bot.repository.TranslationRecordRepository;

class TranslationActionModuleTests {

    private TranslationRecordRepository recordRepository;
    private TranslationActionClaimRepository claimRepository;
    private TranslationService translationService;
    private TranslationActionModule module;

    @BeforeEach
    void setUp() {
        recordRepository = Mockito.mock(TranslationRecordRepository.class);
        claimRepository = Mockito.mock(TranslationActionClaimRepository.class);
        translationService = Mockito.mock(TranslationService.class);
        module = new TranslationActionModule(
                recordRepository, claimRepository, translationService);
    }

    @Test
    void firstAuthorizedActionClaimsBeforeCallingProviderAndCompletesClaim() {
        TranslationRecord source = record("source-1", "U-user", "hello", "你好", "zh-TW");
        TranslationResponse translated = new TranslationResponse(
                "こんにちは", "こんにちは", "result-1", "en", "ja");
        when(recordRepository.findByIdAndUserId("source-1", "U-user"))
                .thenReturn(Optional.of(source));
        when(claimRepository.insert(any(TranslationActionClaim.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(translationService.translateExisting("U-user", "hello", "ja"))
                .thenReturn(translated);

        assertThat(module.execute("U-user", "source-1", "ja")).isEqualTo(translated);

        verify(translationService).translateExisting("U-user", "hello", "ja");
        ArgumentCaptor<TranslationActionClaim> captor =
                ArgumentCaptor.forClass(TranslationActionClaim.class);
        verify(claimRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus())
                .isEqualTo(TranslationActionClaim.Status.COMPLETED);
        assertThat(captor.getValue().getResultRecordId()).isEqualTo("result-1");
    }

    @Test
    void duplicateCompletedActionReusesSavedResultWithoutProviderCost() {
        TranslationRecord source = record("source-1", "U-user", "hello", "你好", "zh-TW");
        TranslationRecord result = record("result-1", "U-user", "hello", "こんにちは", "ja");
        TranslationActionClaim completed = TranslationActionClaim.builder()
                .id("claim-1")
                .userId("U-user")
                .sourceRecordId("source-1")
                .targetLanguage("ja")
                .status(TranslationActionClaim.Status.COMPLETED)
                .resultRecordId("result-1")
                .build();
        when(recordRepository.findByIdAndUserId("source-1", "U-user"))
                .thenReturn(Optional.of(source));
        when(claimRepository.insert(any(TranslationActionClaim.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(claimRepository.findById(any(String.class))).thenReturn(Optional.of(completed));
        when(recordRepository.findByIdAndUserId("result-1", "U-user"))
                .thenReturn(Optional.of(result));

        TranslationResponse response = module.execute("U-user", "source-1", "ja");

        assertThat(response.translatedText()).isEqualTo("こんにちは");
        assertThat(response.recordId()).isEqualTo("result-1");
        verify(translationService, never()).translateExisting(any(), any(), any());
    }

    @Test
    void duplicateInProgressActionDoesNotCallProviderAgain() {
        TranslationRecord source = record("source-1", "U-user", "hello", "你好", "zh-TW");
        TranslationActionClaim processing = TranslationActionClaim.builder()
                .id("claim-1")
                .userId("U-user")
                .sourceRecordId("source-1")
                .targetLanguage("ja")
                .status(TranslationActionClaim.Status.PROCESSING)
                .build();
        when(recordRepository.findByIdAndUserId("source-1", "U-user"))
                .thenReturn(Optional.of(source));
        when(claimRepository.insert(any(TranslationActionClaim.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(claimRepository.findById(any(String.class))).thenReturn(Optional.of(processing));

        TranslationResponse response = module.execute("U-user", "source-1", "ja");

        assertThat(response.actionable()).isFalse();
        assertThat(response.displayText()).contains("處理中");
        verify(translationService, never()).translateExisting(any(), any(), any());
    }

    @Test
    void guessedRecordOwnedByAnotherUserCannotCreateAClaim() {
        when(recordRepository.findByIdAndUserId("source-1", "U-attacker"))
                .thenReturn(Optional.empty());

        TranslationResponse response = module.execute("U-attacker", "source-1", "ja");

        assertThat(response.actionable()).isFalse();
        verify(claimRepository, never()).insert(any(TranslationActionClaim.class));
        verify(translationService, never()).translateExisting(any(), any(), any());
    }

    private static TranslationRecord record(
            String id,
            String userId,
            String source,
            String translated,
            String target) {
        return TranslationRecord.builder()
                .id(id)
                .userId(userId)
                .sourceText(source)
                .sourceLanguage("en")
                .targetLanguage(target)
                .translatedText(translated)
                .build();
    }
}
