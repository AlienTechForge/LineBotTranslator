package com.linetranslate.bot.service.ocr;

import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.linecorp.bot.client.base.BlobContent;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderExecutionModule;
import com.linetranslate.bot.service.storage.ImageStorageResult;
import com.linetranslate.bot.service.storage.MinioStorageService;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.translation.TranslationRequestKind;
import com.linetranslate.bot.service.translation.TranslationWorkflowModule;
import com.linetranslate.bot.service.translation.TranslationWorkflowOutcome;
import com.linetranslate.bot.service.translation.TranslationWorkflowRequest;
import com.linetranslate.bot.service.translation.TranslationWorkflowResult;
import com.linetranslate.bot.service.translation.ImageRegionTranslationInput;
import com.linetranslate.bot.service.translation.ImageRegionLayout;
import com.linetranslate.bot.service.translation.TranslationPromptFactory;
import com.linetranslate.bot.util.LanguageUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Image translation pipeline Module. Every stage consumes and returns explicit
 * values; no request state is stored on threads or singleton fields.
 */
@Service
@Slf4j
public class ImageTranslationPipeline {

    private static final int MAX_OCR_REGIONS = 200;
    private static final int MAX_OCR_TEXT_CHARS = 50_000;

    private static final Pattern TRANSLATION_COMMAND_PATTERN_CN =
            Pattern.compile("翻譯成([\\u4e00-\\u9fa5]+)\\s*(.*)");
    private static final Pattern TRANSLATION_COMMAND_PATTERN_CODE =
            Pattern.compile("翻譯成([a-zA-Z\\-]+)\\s*(.*)");

    private final OcrService ocrService;
    private final TranslationWorkflowModule translationWorkflowModule;
    private final AiProviderExecutionModule aiProviderExecutionModule;
    private final UserPreferencesModule userPreferencesModule;
    private final MessagingApiBlobClient messagingApiBlobClient;
    private final MinioStorageService minioStorageService;
    private final ImageInputValidator imageInputValidator;
    private final ImageTranslationOverlayRenderer overlayRenderer;
    private final ImageTranslationProperties properties;
    private final OcrRegionQualificationPolicy qualificationPolicy;
    private final OcrSourceLanguageResolver sourceLanguageResolver;
    private final OverlaySafetyPolicy overlaySafetyPolicy;
    private final TranslationPromptFactory promptFactory;
    private final OverlayContentClassifier contentClassifier = new OverlayContentClassifier();

    @Autowired
    public ImageTranslationPipeline(
            ObjectProvider<OcrService> ocrServiceProvider,
            TranslationWorkflowModule translationWorkflowModule,
            AiProviderExecutionModule aiProviderExecutionModule,
            UserPreferencesModule userPreferencesModule,
            MessagingApiBlobClient messagingApiBlobClient,
            MinioStorageService minioStorageService,
            ImageInputValidator imageInputValidator,
            ImageTranslationOverlayRenderer overlayRenderer,
            ImageTranslationProperties properties,
            OcrRegionQualificationPolicy qualificationPolicy,
            OcrSourceLanguageResolver sourceLanguageResolver,
            OverlaySafetyPolicy overlaySafetyPolicy,
            TranslationPromptFactory promptFactory) {
        this.ocrService = ocrServiceProvider.getIfAvailable();
        this.translationWorkflowModule = translationWorkflowModule;
        this.aiProviderExecutionModule = aiProviderExecutionModule;
        this.userPreferencesModule = userPreferencesModule;
        this.messagingApiBlobClient = messagingApiBlobClient;
        this.minioStorageService = minioStorageService;
        this.imageInputValidator = imageInputValidator;
        this.overlayRenderer = overlayRenderer;
        this.properties = properties;
        this.qualificationPolicy = qualificationPolicy;
        this.sourceLanguageResolver = sourceLanguageResolver;
        this.overlaySafetyPolicy = overlaySafetyPolicy;
        this.promptFactory = promptFactory;
    }

    public ImageTranslationPipeline(
            ObjectProvider<OcrService> ocrServiceProvider,
            TranslationWorkflowModule translationWorkflowModule,
            AiProviderExecutionModule aiProviderExecutionModule,
            UserPreferencesModule userPreferencesModule,
            MessagingApiBlobClient messagingApiBlobClient,
            MinioStorageService minioStorageService,
            ImageInputValidator imageInputValidator,
            ImageTranslationOverlayRenderer overlayRenderer,
            ImageTranslationProperties properties,
            OcrRegionQualificationPolicy qualificationPolicy,
            OcrSourceLanguageResolver sourceLanguageResolver,
            OverlaySafetyPolicy overlaySafetyPolicy) {
        this(ocrServiceProvider, translationWorkflowModule, aiProviderExecutionModule,
                userPreferencesModule, messagingApiBlobClient, minioStorageService,
                imageInputValidator, overlayRenderer, properties, qualificationPolicy,
                sourceLanguageResolver, overlaySafetyPolicy,
                new TranslationPromptFactory());
    }

    /** Compatibility constructor retained for focused tests. */
    public ImageTranslationPipeline(
            ObjectProvider<OcrService> ocrServiceProvider,
            TranslationWorkflowModule translationWorkflowModule,
            AiProviderExecutionModule aiProviderExecutionModule,
            UserPreferencesModule userPreferencesModule,
            MessagingApiBlobClient messagingApiBlobClient,
            MinioStorageService minioStorageService,
            ImageInputValidator imageInputValidator,
            ImageTranslationOverlayRenderer overlayRenderer,
            ImageTranslationProperties properties) {
        this(ocrServiceProvider, translationWorkflowModule, aiProviderExecutionModule,
                userPreferencesModule, messagingApiBlobClient, minioStorageService,
                imageInputValidator, overlayRenderer, properties,
                new OcrRegionQualificationPolicy(), new OcrSourceLanguageResolver(), new OverlaySafetyPolicy());
    }

    /** Compatibility constructor for focused workflow tests. */
    public ImageTranslationPipeline(
            ObjectProvider<OcrService> ocrServiceProvider,
            TranslationWorkflowModule translationWorkflowModule,
            AiProviderExecutionModule aiProviderExecutionModule,
            UserPreferencesModule userPreferencesModule,
            MessagingApiBlobClient messagingApiBlobClient,
            MinioStorageService minioStorageService) {
        this(
                ocrServiceProvider,
                translationWorkflowModule,
                aiProviderExecutionModule,
                userPreferencesModule,
                messagingApiBlobClient,
                minioStorageService,
                ImageInputValidator.trustedTestSeam(ImageTranslationProperties.defaults()),
                new ImageTranslationOverlayRenderer(),
                ImageTranslationProperties.defaults(),
                new OcrRegionQualificationPolicy(),
                new OcrSourceLanguageResolver(),
                new OverlaySafetyPolicy());
    }

    public ImageTranslationOutcome execute(ImageTranslationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Image translation request is required");
        }
        long pipelineStarted = System.nanoTime();
        long stageStarted = pipelineStarted;
        ValidatedImage image;
        try {
            image = download(request.messageId());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.CANCELLED);
        } catch (Exception failure) {
            log.warn("LINE image download failed: message={}, failure={}",
                    SafeLog.content(request.messageId()), SafeLog.failure(failure));
            return new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.DOWNLOAD);
        }
        long downloadMillis = elapsedMillis(stageStarted);

        stageStarted = System.nanoTime();
        ImageStorageResult storage = store(image);
        long storageMillis = elapsedMillis(stageStarted);
        stageStarted = System.nanoTime();
        OcrRecognition recognition;
        try {
            recognition = recognize(request, image);
        } catch (RuntimeException failure) {
            log.warn("Image recognition failed: user={}, failure={}",
                    SafeLog.user(request.userProfile().getUserId()), SafeLog.failure(failure));
            return new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.RECOGNITION);
        }
        long recognitionMillis = elapsedMillis(stageStarted);
        List<OcrRegionDecision> decisions = recognition.regions().stream()
                .map(region -> qualificationPolicy.decide(region, properties.lowConfidenceThreshold()))
                .toList();
        List<OcrRegionDecision> translatedDecisions = decisions.stream()
                .filter(value -> value.qualification() == OcrQualification.TRANSLATE).toList();
        List<OcrRegionDecision> workflowDecisions = decisions.stream()
                .filter(value -> value.qualification() != OcrQualification.REJECT).toList();
        String translatableText = recognition.regions().isEmpty()
                ? recognition.reliableText(properties.lowConfidenceThreshold())
                : workflowDecisions.stream().map(value -> value.region().text())
                        .reduce((left, right) -> left + "\n" + right).orElse("");
        if (translatableText.isBlank() || (!recognition.regions().isEmpty() && translatedDecisions.isEmpty())) {
            return new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.NO_TEXT);
        }

        ImageTranslationContext context = new ImageTranslationContext(
                new DownloadedImage(image.bytes(), image.contentType()),
                storage,
                recognition.text(),
                requestedTargetLanguage(translatableText));
        TranslationWorkflowOutcome workflowOutcome;
        long translationStarted = System.nanoTime();
        try {
            String resolvedSourceLanguage = sourceLanguageResolver.resolve(
                    workflowDecisions.stream().map(OcrRegionDecision::region).toList());
            String sourceLanguage = !recognition.regions().isEmpty() && resolvedSourceLanguage == null
                    ? "und" : resolvedSourceLanguage;
            List<ImageRegionTranslationInput> structuredRegions = workflowDecisions.stream()
                    .map(decision -> new ImageRegionTranslationInput(
                            decision.region().id(), decision.region().text(),
                            decision.region().languages().stream().findFirst()
                                    .map(OcrDetectedLanguage::code).orElse(sourceLanguage),
                            decision.qualification() == OcrQualification.PRESERVE
                                    ? List.of(decision.region().text()) : decision.protectedTokens(),
                            decision.qualification() == OcrQualification.TRANSLATE,
                            decision.region().readingOrder(),
                            layout(decision.region())))
                    .toList();
            workflowOutcome = translationWorkflowModule.execute(new TranslationWorkflowRequest(
                    request.userProfile(),
                    translatableText,
                    context.requestedTargetLanguage(),
                    TranslationRequestKind.IMAGE_OCR,
                    context.storage().url().orElse(null),
                    context.storage().stored(),
                    request.startedAt(),
                    null,
                    sourceLanguage,
                    structuredRegions));
        } catch (RuntimeException failure) {
            log.warn("Image translation workflow failed: user={}, failure={}",
                    SafeLog.user(request.userProfile().getUserId()), SafeLog.failure(failure));
            return new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.TRANSLATION);
        }
        if (workflowOutcome instanceof TranslationWorkflowOutcome.Failure failure) {
            return new ImageTranslationOutcome.Failure(
                    ImageTranslationFailureStage.TRANSLATION,
                    failure.failure());
        }

        TranslationWorkflowResult translation =
                ((TranslationWorkflowOutcome.Success) workflowOutcome).result();
        long translationMillis = elapsedMillis(translationStarted);
        long renderingStarted = System.nanoTime();
        OverlayOutcome overlay = renderAndStore(image, recognition, translation);
        long renderingMillis = elapsedMillis(renderingStarted);
        log.info("Image translation timing: downloadMs={}, storageMs={}, recognitionMs={}, "
                        + "translationMs={}, renderingMs={}, totalMs={}, ocrRegions={}, translatedRegions={}",
                downloadMillis,
                storageMillis,
                recognitionMillis,
                translationMillis,
                renderingMillis,
                elapsedMillis(pipelineStarted),
                recognition.regions().size(),
                translatedDecisions.size());
        return new ImageTranslationOutcome.Success(
                new ImageTranslationPipelineResult(
                        context,
                        translation,
                        overlay.storage(),
                        overlay.lowConfidenceBlockCount(),
                        overlay.disposition(),
                        overlay.degradation()));
    }

    private static long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private ValidatedImage download(String messageId) throws Exception {
        Result<BlobContent> response = messagingApiBlobClient.getMessageContent(messageId).get();
        BlobContent content = response == null ? null : response.body();
        if (content == null) {
            throw new IllegalStateException("LINE image response body is empty");
        }
        try (InputStream stream = content.byteStream()) {
            if (stream == null) {
                throw new IllegalStateException("LINE image response stream is empty");
            }
            int readLimit = (int) Math.min(
                    Integer.MAX_VALUE,
                    properties.maxFileSizeBytes() + 1L);
            byte[] bytes = stream.readNBytes(readLimit);
            if (bytes.length > properties.maxFileSizeBytes()) {
                throw new InvalidImageException("Image exceeds the maximum file size");
            }
            return imageInputValidator.validate(bytes, content.mimeType());
        }
    }

    private ImageStorageResult store(ValidatedImage image) {
        try {
            ImageStorageResult result = minioStorageService.uploadImage(
                    image.bytes(), image.contentType());
            return result == null ? ImageStorageResult.notStored() : result;
        } catch (RuntimeException failure) {
            log.warn("Image storage degraded: failure={}", SafeLog.failure(failure));
            return ImageStorageResult.notStored();
        }
    }

    private OcrRecognition recognize(ImageTranslationRequest request, ValidatedImage image) {
        if (ocrService != null) {
            try {
                List<OcrRegion> regions;
                try (InputStream stream = new java.io.ByteArrayInputStream(image.bytes())) {
                    regions = ocrService.recognizeRegions(stream);
                }
                if (regions != null && !regions.isEmpty()) {
                    int characters = regions.stream().mapToInt(region -> region.text().length()).sum();
                    long uniqueIds = regions.stream().map(OcrRegion::id).distinct().count();
                    if (regions.size() > MAX_OCR_REGIONS || characters > MAX_OCR_TEXT_CHARS
                            || uniqueIds != regions.size()) {
                        throw new InvalidImageException("OCR result exceeds processing limits");
                    }
                    return OcrRecognition.structured(regions);
                }
                try (InputStream stream = new java.io.ByteArrayInputStream(image.bytes())) {
                    return OcrRecognition.plain(ocrService.recognizeText(stream));
                }
            } catch (OcrProcessingException failure) {
                log.info("Primary OCR unavailable; using AI image recognition: failure={}",
                        SafeLog.failure(failure));
            } catch (java.io.IOException impossibleForByteArray) {
                throw new OcrProcessingException("Unable to close OCR image stream", impossibleForByteArray);
            }
        }

        String dataUrl = "data:" + image.contentType() + ";base64,"
                + Base64.getEncoder().encodeToString(image.bytes());
        AiExecutionResult result = aiProviderExecutionModule.processImage(
                userPreferencesModule.resolve(request.userProfile()), promptFactory.ocr(), dataUrl);
        if (result == null) {
            throw new OcrProcessingException("AI image recognition returned no result");
        }
        return OcrRecognition.plain(result.text());
    }

    private OverlayOutcome renderAndStore(
            ValidatedImage image,
            OcrRecognition recognition,
            TranslationWorkflowResult translation) {
        if (recognition.regions().isEmpty()) {
            return OverlayOutcome.unavailable();
        }
        if (translation.imageRegionTranslations().isEmpty()) {
            return new OverlayOutcome(ImageStorageResult.notStored(), 0,
                    ImageOverlayDisposition.SAFETY_DEGRADED,
                    OverlayDegradationSummary.single(OverlayDegradationReason.MAPPING, 1));
        }
        try {
            java.util.Map<String, String> replacements = translation.imageRegionTranslations().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            com.linetranslate.bot.service.translation.ImageRegionTranslation::regionId,
                            com.linetranslate.bot.service.translation.ImageRegionTranslation::translatedText));
            List<ImageRegionOverlay> candidates = recognition.regions().stream()
                    .filter(region -> replacements.containsKey(region.id()))
                    .map(region -> new ImageRegionOverlay(region, replacements.get(region.id())))
                    .toList();
            OverlayContentMode contentMode = contentClassifier.classify(
                    image.image(), candidates.stream().map(ImageRegionOverlay::region).toList());
            OverlaySafetyPlan plan = overlaySafetyPolicy.evaluate(
                    candidates, image.image().getWidth(), image.image().getHeight(), properties, contentMode);
            log.info("Image overlay decision: mode={}, candidates={}, accepted={}, preserved={}, reason={}",
                    contentMode, candidates.size(), plan.overlays().size(), plan.skipped(), plan.reason());
            if (!plan.safe() || plan.overlays().isEmpty()) {
                OverlayDegradationSummary degradation = OverlayDegradationSummary.fromDecisions(plan.decisions());
                return new OverlayOutcome(ImageStorageResult.notStored(),
                        degradation.count(OverlayDegradationReason.LOW_CONFIDENCE),
                        ImageOverlayDisposition.SAFETY_DEGRADED, degradation);
            }
            RenderedImage rendered = overlayRenderer.render(image, plan);
            if (rendered.renderedBlockCount() == 0) {
                return new OverlayOutcome(
                        ImageStorageResult.notStored(), rendered.lowConfidenceBlockCount(),
                        ImageOverlayDisposition.SAFETY_DEGRADED, rendered.degradation());
            }
            ImageStorageResult storage = minioStorageService.uploadTranslatedImage(rendered.pngBytes());
            return new OverlayOutcome(
                    storage == null ? ImageStorageResult.notStored() : storage,
                    rendered.lowConfidenceBlockCount(),
                    storage != null && storage.stored()
                            ? ImageOverlayDisposition.GENERATED : ImageOverlayDisposition.UNAVAILABLE,
                    rendered.degradation().merge(storage != null && storage.stored()
                            ? OverlayDegradationSummary.none()
                            : OverlayDegradationSummary.single(OverlayDegradationReason.STORAGE, 1)));
        } catch (RuntimeException failure) {
            log.warn("Translated image rendering degraded to text: failure={}", SafeLog.failure(failure));
            return new OverlayOutcome(ImageStorageResult.notStored(), 0,
                    ImageOverlayDisposition.SAFETY_DEGRADED,
                    OverlayDegradationSummary.single(OverlayDegradationReason.OTHER, 1));
        }
    }

    private String requestedTargetLanguage(String recognizedText) {
        Matcher chineseName = TRANSLATION_COMMAND_PATTERN_CN.matcher(recognizedText);
        if (chineseName.find()) {
            return LanguageUtils.toLanguageCode(chineseName.group(1));
        }
        Matcher languageCode = TRANSLATION_COMMAND_PATTERN_CODE.matcher(recognizedText);
        return languageCode.find()
                ? languageCode.group(1).toLowerCase(java.util.Locale.ROOT)
                : null;
    }

    private static ImageRegionLayout layout(OcrRegion region) {
        java.awt.Rectangle bounds = OverlaySafetyPolicy.polygon(region.polygon()).getBounds();
        int maxLines = region.compactLabel() ? 1 : Math.max(1, bounds.height / 14);
        int maxCharacters = region.compactLabel()
                ? Math.max(2, region.text().codePointCount(0, region.text().length()) * 3)
                : Math.max(8, bounds.width / 6 * maxLines);
        return new ImageRegionLayout(region.groupId(), bounds.x, bounds.y,
                bounds.width, bounds.height, maxLines, maxCharacters, region.compactLabel());
    }

    private record OverlayOutcome(
            ImageStorageResult storage,
            int lowConfidenceBlockCount,
            ImageOverlayDisposition disposition,
            OverlayDegradationSummary degradation) {
        static OverlayOutcome unavailable() {
            return new OverlayOutcome(ImageStorageResult.notStored(), 0, ImageOverlayDisposition.UNAVAILABLE,
                    OverlayDegradationSummary.none());
        }
    }
}
