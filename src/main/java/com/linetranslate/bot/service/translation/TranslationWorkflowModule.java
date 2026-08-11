package com.linetranslate.bot.service.translation;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;

import org.springframework.stereotype.Service;

import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.service.ai.AiExecutionOutcome;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.preference.UserPreferences;
import com.linetranslate.bot.service.preference.UserPreferencesModule;

import lombok.extern.slf4j.Slf4j;

/**
 * Shared translation workflow Module. It owns the single-pass domain workflow
 * after a request has been normalized by a text or image caller.
 */
@Service
@Slf4j
public class TranslationWorkflowModule {

    private final LanguageDetectionService languageDetectionService;
    private final CachedTranslationAdapter translationAdapter;
    private final TranslationRecordRepository translationRecordRepository;
    private final UserPreferencesModule userPreferencesModule;

    public TranslationWorkflowModule(
            LanguageDetectionService languageDetectionService,
            CachedTranslationAdapter translationAdapter,
            TranslationRecordRepository translationRecordRepository,
            UserPreferencesModule userPreferencesModule) {
        this.languageDetectionService = languageDetectionService;
        this.translationAdapter = translationAdapter;
        this.translationRecordRepository = translationRecordRepository;
        this.userPreferencesModule = userPreferencesModule;
    }

    public TranslationWorkflowOutcome execute(TranslationWorkflowRequest request) {
        String sourceLanguage = languageDetectionService.detectLanguage(request.sourceText());
        UserPreferences preferences = userPreferencesModule.resolve(request.userProfile());
        String targetLanguage = request.requestedTargetLanguage() == null
                ? defaultTargetLanguage(sourceLanguage, preferences)
                : request.requestedTargetLanguage();

        AiExecutionOutcome providerOutcome = translationAdapter.translate(
                preferences,
                request.sourceText(),
                targetLanguage);
        if (providerOutcome instanceof AiExecutionOutcome.Failure failure) {
            return new TranslationWorkflowOutcome.Failure(failure.failure());
        }

        AiExecutionResult execution = ((AiExecutionOutcome.Success) providerOutcome).result();
        long processingTimeMillis = Math.max(
                0,
                Duration.between(request.startedAt(), Instant.now()).toMillis());
        persistSuccess(request, sourceLanguage, targetLanguage, execution, processingTimeMillis);

        return new TranslationWorkflowOutcome.Success(new TranslationWorkflowResult(
                request.sourceText(),
                sourceLanguage,
                targetLanguage,
                execution,
                processingTimeMillis,
                request.kind()));
    }

    private String defaultTargetLanguage(String sourceLanguage, UserPreferences preferences) {
        if (isChinese(sourceLanguage)) {
            return preferences.chineseTargetLanguage();
        }

        String preferredLanguage = preferences.targetLanguage();
        if (preferredLanguage != null
                && !preferredLanguage.isBlank()
                && !preferredLanguage.equalsIgnoreCase(sourceLanguage)) {
            return preferredLanguage;
        }
        return preferences.fallbackTargetLanguage();
    }

    private void persistSuccess(
            TranslationWorkflowRequest request,
            String sourceLanguage,
            String targetLanguage,
            AiExecutionResult execution,
            long processingTimeMillis) {
        TranslationRecord record = TranslationRecord.builder()
                .userId(request.userProfile().getUserId())
                .sourceText(request.sourceText())
                .sourceLanguage(sourceLanguage)
                .targetLanguage(targetLanguage)
                .translatedText(execution.text())
                .aiProvider(execution.providerName())
                .modelName(execution.modelName())
                .createdAt(LocalDateTime.now())
                .processingTimeMs(processingTimeMillis)
                .isImageTranslation(request.kind().isImage())
                .imageUrl(request.imageUrl())
                .imageStored(request.imageStored())
                .build();
        translationRecordRepository.save(record);

        UserProfile userProfile = request.userProfile();
        userProfile.setLastInteractionAt(LocalDateTime.now());
        userProfile.setTotalTranslations(userProfile.getTotalTranslations() + 1);
        if (request.kind().isImage()) {
            userProfile.setImageTranslations(userProfile.getImageTranslations() + 1);
        } else {
            userProfile.setTextTranslations(userProfile.getTextTranslations() + 1);
        }
        updateRecentActivity(userProfile, execution.text(), targetLanguage);
        userPreferencesModule.persistTranslationActivity(userProfile, targetLanguage);

        log.info("Translation workflow persisted: user={}, kind={}, provider={}, model={}",
                SafeLog.user(userProfile.getUserId()),
                request.kind(),
                SafeLog.metadata(execution.providerName()),
                SafeLog.metadata(execution.modelName()));
    }

    private void updateRecentActivity(
            UserProfile userProfile,
            String translatedText,
            String targetLanguage) {
        if (userProfile.getRecentTranslations() == null) {
            userProfile.setRecentTranslations(new ArrayList<>());
        }
        userProfile.getRecentTranslations().add(0, translatedText);
        if (userProfile.getRecentTranslations().size() > 5) {
            userProfile.getRecentTranslations().subList(5, userProfile.getRecentTranslations().size()).clear();
        }
    }

    private boolean isChinese(String language) {
        return language != null && language.toLowerCase(java.util.Locale.ROOT).startsWith("zh");
    }
}
