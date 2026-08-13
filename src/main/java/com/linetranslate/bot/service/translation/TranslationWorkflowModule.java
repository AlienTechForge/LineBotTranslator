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
    private final StructuredImageTranslationAdapter structuredAdapter;
    private final TargetLocalePolicy localePolicy;

    public TranslationWorkflowModule(
            LanguageDetectionService languageDetectionService,
            CachedTranslationAdapter translationAdapter,
            TranslationRecordRepository translationRecordRepository,
            UserPreferencesModule userPreferencesModule) {
        this(languageDetectionService, translationAdapter, translationRecordRepository,
                userPreferencesModule, null, new TargetLocalePolicy());
    }

    public TranslationWorkflowModule(
            LanguageDetectionService languageDetectionService,
            CachedTranslationAdapter translationAdapter,
            TranslationRecordRepository translationRecordRepository,
            UserPreferencesModule userPreferencesModule,
            StructuredImageTranslationAdapter structuredAdapter) {
        this(languageDetectionService, translationAdapter, translationRecordRepository,
                userPreferencesModule, structuredAdapter, new TargetLocalePolicy());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TranslationWorkflowModule(
            LanguageDetectionService languageDetectionService,
            CachedTranslationAdapter translationAdapter,
            TranslationRecordRepository translationRecordRepository,
            UserPreferencesModule userPreferencesModule,
            StructuredImageTranslationAdapter structuredAdapter,
            TargetLocalePolicy localePolicy) {
        this.languageDetectionService = languageDetectionService;
        this.translationAdapter = translationAdapter;
        this.translationRecordRepository = translationRecordRepository;
        this.userPreferencesModule = userPreferencesModule;
        this.structuredAdapter = structuredAdapter;
        this.localePolicy = localePolicy;
    }

    public TranslationWorkflowOutcome execute(TranslationWorkflowRequest request) {
        String sourceLanguage = request.explicitSourceLanguage() == null
                ? languageDetectionService.detectLanguage(request.sourceText())
                : request.explicitSourceLanguage();
        UserPreferences preferences = userPreferencesModule.resolve(request.userProfile());
        String targetLanguage = request.requestedTargetLanguage() == null
                ? defaultTargetLanguage(sourceLanguage, preferences)
                : request.requestedTargetLanguage();
        targetLanguage = localePolicy.resolve(targetLanguage).locale();
        TranslationStylePreset style = request.requestedStylePresetId() == null
                ? preferences.translationStyle()
                : TranslationStylePreset.find(request.requestedStylePresetId())
                        .orElse(preferences.translationStyle());

        AiExecutionResult execution;
        java.util.List<ImageRegionTranslation> regionTranslations = java.util.List.of();
        if (!request.imageRegions().isEmpty()) {
            if (structuredAdapter == null) {
                throw new IllegalStateException("Structured image translation adapter is unavailable");
            }
            try {
                StructuredImageTranslationAdapter.Result structured = structuredAdapter.translate(
                        preferences, request.imageRegions(), targetLanguage, style);
                execution = structured.execution();
                regionTranslations = structured.translations();
            } catch (StructuredTranslationException unsafeMapping) {
                // Keep the core translation useful, but never guess image-region identity.
                AiExecutionOutcome degraded = translateWithLocaleValidation(
                        preferences, request.sourceText(), targetLanguage, style, true);
                if (degraded instanceof AiExecutionOutcome.Failure failure) {
                    return new TranslationWorkflowOutcome.Failure(failure.failure());
                }
                execution = ((AiExecutionOutcome.Success) degraded).result();
                log.warn("Structured image mapping degraded: reason={}, requestedRegions={}, target={}",
                        unsafeMapping.reason(), request.imageRegions().size(), targetLanguage);
            }
        } else {
            AiExecutionOutcome providerOutcome = translateWithLocaleValidation(
                    preferences, request.sourceText(), targetLanguage, style,
                    request.requestedStylePresetId() != null);
            if (providerOutcome instanceof AiExecutionOutcome.Failure failure) {
                return new TranslationWorkflowOutcome.Failure(failure.failure());
            }
            execution = ((AiExecutionOutcome.Success) providerOutcome).result();
        }
        long processingTimeMillis = Math.max(
                0,
                Duration.between(request.startedAt(), Instant.now()).toMillis());
        String recordId = persistSuccess(
                request, sourceLanguage, targetLanguage, style, execution, processingTimeMillis);

        return new TranslationWorkflowOutcome.Success(new TranslationWorkflowResult(
                request.sourceText(),
                sourceLanguage,
                targetLanguage,
                execution,
                processingTimeMillis,
                request.kind(),
                recordId,
                style.id(),
                style.promptVersion(),
                regionTranslations));
    }

    private AiExecutionOutcome translateWithLocaleValidation(
            UserPreferences preferences,
            String sourceText,
            String targetLanguage,
            TranslationStylePreset style,
            boolean explicitStyle) {
        AiExecutionOutcome outcome = explicitStyle
                ? translationAdapter.translate(preferences, sourceText, targetLanguage, style)
                : translationAdapter.translate(preferences, sourceText, targetLanguage);
        if (localeMismatch(outcome, targetLanguage)) {
            logLocaleMismatch((AiExecutionOutcome.Success) outcome, targetLanguage, 1);
            outcome = explicitStyle
                    ? translationAdapter.translate(preferences, sourceText, targetLanguage, style)
                    : translationAdapter.translate(preferences, sourceText, targetLanguage);
        }
        if (!localeMismatch(outcome, targetLanguage)) return outcome;
        logLocaleMismatch((AiExecutionOutcome.Success) outcome, targetLanguage, 2);
        AiExecutionResult result = ((AiExecutionOutcome.Success) outcome).result();
        return new AiExecutionOutcome.Failure(new com.linetranslate.bot.service.ai.AiExecutionFailure(
                com.linetranslate.bot.service.ai.AiProviderException.Outcome.MALFORMED_RESPONSE,
                result.providerName(), result.modelName(), "TARGET_LOCALE_MISMATCH",
                "locale-validation", -1, result.attempts()));
    }

    private boolean localeMismatch(AiExecutionOutcome outcome, String targetLanguage) {
        return outcome instanceof AiExecutionOutcome.Success success
                && localePolicy.needsValidation(targetLanguage)
                && !localePolicy.accepts(success.result().text(), targetLanguage);
    }

    private static void logLocaleMismatch(
            AiExecutionOutcome.Success success, String targetLanguage, int attempt) {
        AiExecutionResult result = success.result();
        log.warn("Translation output rejected by target locale: target={}, provider={}, model={}, attempt={}",
                SafeLog.metadata(targetLanguage), SafeLog.metadata(result.providerName()),
                SafeLog.metadata(result.modelName()), attempt);
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

    private String persistSuccess(
            TranslationWorkflowRequest request,
            String sourceLanguage,
            String targetLanguage,
            TranslationStylePreset style,
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
                .stylePresetId(style.id())
                .stylePromptVersion(style.promptVersion())
                .createdAt(LocalDateTime.now())
                .processingTimeMs(processingTimeMillis)
                .isImageTranslation(request.kind().isImage())
                .imageUrl(request.imageUrl())
                .imageStored(request.imageStored())
                .build();
        TranslationRecord savedRecord = translationRecordRepository.save(record);

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
        return savedRecord == null ? record.getId() : savedRecord.getId();
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
