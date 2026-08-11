package com.linetranslate.bot.service.translation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.model.TranslationActionClaim;
import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.repository.TranslationActionClaimRepository;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.util.LanguageUtils;

import lombok.extern.slf4j.Slf4j;

/** Authorized, idempotent execution of actions attached to translation results. */
@Service
@Slf4j
public class TranslationActionModule {

    private final TranslationRecordRepository recordRepository;
    private final TranslationActionClaimRepository claimRepository;
    private final TranslationService translationService;

    public TranslationActionModule(
            TranslationRecordRepository recordRepository,
            TranslationActionClaimRepository claimRepository,
            TranslationService translationService) {
        this.recordRepository = recordRepository;
        this.claimRepository = claimRepository;
        this.translationService = translationService;
    }

    public TranslationResponse execute(
            String userId,
            String sourceRecordId,
            String requestedTargetLanguage) {
        TranslationRecord sourceRecord = recordRepository
                .findByIdAndUserId(sourceRecordId, userId)
                .orElse(null);
        if (sourceRecord == null) {
            return TranslationResponse.plain("找不到可操作的翻譯結果。");
        }

        if (!LanguageUtils.isSupported(requestedTargetLanguage)) {
            return TranslationResponse.plain("不支援指定的目標語言。");
        }
        String targetLanguage = LanguageUtils.toLanguageCode(requestedTargetLanguage);
        String claimId = claimId(userId, sourceRecordId, targetLanguage, null);
        TranslationActionClaim claim = newClaim(
                claimId, userId, sourceRecordId, targetLanguage, null);
        try {
            claimRepository.insert(claim);
        } catch (DuplicateKeyException duplicate) {
            return replayExisting(claimId, userId, sourceRecordId, targetLanguage, null);
        }

        TranslationResponse response;
        try {
            response = translationService.translateExisting(
                    userId, sourceRecord.getSourceText(), targetLanguage);
        } catch (RuntimeException failure) {
            log.warn("Translation action failed: user={}, record={}, failure={}",
                    SafeLog.user(userId), SafeLog.metadata(sourceRecordId), SafeLog.failure(failure));
            response = TranslationResponse.plain("重新翻譯未完成，請建立新的翻譯請求。");
        }

        claim.setUpdatedAt(LocalDateTime.now());
        if (response.actionable()) {
            claim.setStatus(TranslationActionClaim.Status.COMPLETED);
            claim.setResultRecordId(response.recordId());
        } else {
            claim.setStatus(TranslationActionClaim.Status.FAILED);
        }
        claimRepository.save(claim);
        return response;
    }

    public TranslationResponse executeStyle(
            String userId,
            String sourceRecordId,
            String requestedStylePresetId) {
        TranslationRecord sourceRecord = recordRepository
                .findByIdAndUserId(sourceRecordId, userId)
                .orElse(null);
        if (sourceRecord == null) {
            return TranslationResponse.plain("找不到可操作的翻譯結果。");
        }
        TranslationStylePreset style = TranslationStylePreset.find(requestedStylePresetId)
                .orElse(null);
        if (style == null) {
            return TranslationResponse.plain("不支援指定的翻譯風格。");
        }
        if (!LanguageUtils.isSupported(sourceRecord.getTargetLanguage())) {
            return TranslationResponse.plain("原翻譯的目標語言已失效。");
        }
        String targetLanguage = LanguageUtils.toLanguageCode(sourceRecord.getTargetLanguage());
        String claimId = claimId(userId, sourceRecordId, targetLanguage, style.id());
        TranslationActionClaim claim = newClaim(
                claimId, userId, sourceRecordId, targetLanguage, style.id());
        try {
            claimRepository.insert(claim);
        } catch (DuplicateKeyException duplicate) {
            return replayExisting(
                    claimId, userId, sourceRecordId, targetLanguage, style.id());
        }

        TranslationResponse response;
        try {
            response = translationService.translateExisting(
                    userId, sourceRecord.getSourceText(), targetLanguage, style.id());
        } catch (RuntimeException failure) {
            log.warn("Translation style action failed: user={}, record={}, failure={}",
                    SafeLog.user(userId), SafeLog.metadata(sourceRecordId), SafeLog.failure(failure));
            response = TranslationResponse.plain("風格翻譯未完成，請建立新的翻譯請求。");
        }
        completeClaim(claim, response);
        return response;
    }

    private TranslationResponse replayExisting(
            String claimId,
            String userId,
            String sourceRecordId,
            String targetLanguage,
            String stylePresetId) {
        TranslationActionClaim existing = claimRepository.findById(claimId).orElse(null);
        if (existing == null
                || !Objects.equals(existing.getUserId(), userId)
                || !Objects.equals(existing.getSourceRecordId(), sourceRecordId)
                || !Objects.equals(existing.getTargetLanguage(), targetLanguage)
                || !Objects.equals(existing.getStylePresetId(), stylePresetId)) {
            return TranslationResponse.plain("此操作目前無法執行。");
        }
        if (existing.getStatus() == TranslationActionClaim.Status.COMPLETED
                && existing.getResultRecordId() != null) {
            return recordRepository
                    .findByIdAndUserId(existing.getResultRecordId(), userId)
                    .map(TranslationResponse::fromRecord)
                    .orElseGet(() -> TranslationResponse.plain("此操作已完成。"));
        }
        if (existing.getStatus() == TranslationActionClaim.Status.PROCESSING) {
            return TranslationResponse.plain("此翻譯操作正在處理中，請勿重複點擊。");
        }
        return TranslationResponse.plain("此操作先前未完成，請建立新的翻譯請求。");
    }

    private TranslationActionClaim newClaim(
            String id,
            String userId,
            String sourceRecordId,
            String targetLanguage,
            String stylePresetId) {
        LocalDateTime now = LocalDateTime.now();
        return TranslationActionClaim.builder()
                .id(id)
                .userId(userId)
                .sourceRecordId(sourceRecordId)
                .targetLanguage(targetLanguage)
                .stylePresetId(stylePresetId)
                .status(TranslationActionClaim.Status.PROCESSING)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private String claimId(
            String userId,
            String sourceRecordId,
            String targetLanguage,
            String stylePresetId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String identity = userId + "\0" + sourceRecordId + "\0" + targetLanguage;
            if (stylePresetId != null) {
                identity += "\0" + stylePresetId;
            }
            byte[] input = identity
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(input));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private void completeClaim(TranslationActionClaim claim, TranslationResponse response) {
        claim.setUpdatedAt(LocalDateTime.now());
        if (response.actionable()) {
            claim.setStatus(TranslationActionClaim.Status.COMPLETED);
            claim.setResultRecordId(response.recordId());
        } else {
            claim.setStatus(TranslationActionClaim.Status.FAILED);
        }
        claimRepository.save(claim);
    }
}
