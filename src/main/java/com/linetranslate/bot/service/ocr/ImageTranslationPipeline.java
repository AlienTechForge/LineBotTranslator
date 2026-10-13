package com.linetranslate.bot.service.ocr;

import java.io.InputStream;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.linecorp.bot.client.base.BlobContent;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.ai.AiProviderException;
import com.linetranslate.bot.service.ai.AiProviderExecutionModule;
import com.linetranslate.bot.service.storage.ImageStorageResult;
import com.linetranslate.bot.service.storage.MinioStorageService;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.translation.TranslationRequestKind;
import com.linetranslate.bot.service.translation.StructuredTranslationException;
import com.linetranslate.bot.service.translation.TranslationWorkflowModule;
import com.linetranslate.bot.service.translation.TranslationWorkflowOutcome;
import com.linetranslate.bot.service.translation.TranslationWorkflowRequest;
import com.linetranslate.bot.service.translation.TranslationWorkflowResult;
import com.linetranslate.bot.service.translation.ImageRegionTranslationInput;
import com.linetranslate.bot.service.translation.ImageRegionLayout;
import com.linetranslate.bot.service.translation.TranslationPromptFactory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Image translation pipeline Module. Every stage consumes and returns explicit
 * values; no request state is stored on threads or singleton fields.
 */
@Service
@Slf4j
public class ImageTranslationPipeline {

    /**
     * What one image may send to the model and overlay. A result past either bound is not an
     * invalid image: the leading regions in reading order are kept and the rest are reported.
     */
    private static final int MAX_OCR_REGIONS = 200;
    private static final int MAX_OCR_TEXT_CHARS = 50_000;

    /** Where a defect was caught; the lower-case name is the {@code stage} tag of the counter. */
    private enum UnexpectedStage {
        RECOGNITION, STORAGE, TRANSLATION, RENDERING;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

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
    private final Map<UnexpectedStage, Counter> unexpectedFailures = new EnumMap<>(UnexpectedStage.class);

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
            TranslationPromptFactory promptFactory,
            MeterRegistry meterRegistry) {
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
        for (UnexpectedStage stage : UnexpectedStage.values()) {
            unexpectedFailures.put(stage, Counter.builder("image.translation.unexpected")
                    .description("Unexpected exceptions caught inside the image translation pipeline")
                    .tag("stage", stage.tag())
                    .register(meterRegistry));
        }
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
                new TranslationPromptFactory(), new SimpleMeterRegistry());
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
        long recognitionMillis;
        List<OcrRegionDecision> workflowDecisions;
        List<OcrRegionDecision> translatedDecisions;
        int omittedRegions;
        String translatableText;
        // The whole recognition stage is guarded: the OCR call and everything computed from its
        // result before the translation request is built.
        try {
            recognition = recognize(request, image);
            recognitionMillis = elapsedMillis(stageStarted);
            List<OcrRegionDecision> decisions = recognition.regions().stream()
                    .map(region -> qualificationPolicy.decide(region, properties.lowConfidenceThreshold()))
                    .toList();
            List<OcrRegionDecision> eligibleDecisions = decisions.stream()
                    .filter(value -> value.qualification() != OcrQualification.REJECT).toList();
            workflowDecisions = withinModelLimits(eligibleDecisions);
            omittedRegions = eligibleDecisions.size() - workflowDecisions.size();
            if (omittedRegions > 0) {
                log.info("Image OCR output exceeds processing limits; leading regions kept: "
                                + "eligible={}, kept={}, omitted={}, maxRegions={}, maxChars={}",
                        eligibleDecisions.size(), workflowDecisions.size(), omittedRegions,
                        MAX_OCR_REGIONS, MAX_OCR_TEXT_CHARS);
            }
            translatedDecisions = workflowDecisions.stream()
                    .filter(value -> value.qualification() == OcrQualification.TRANSLATE).toList();
            translatableText = recognition.regions().isEmpty()
                    ? recognition.reliableText(properties.lowConfidenceThreshold())
                    : workflowDecisions.stream().map(value -> value.region().text())
                            .reduce((left, right) -> left + "\n" + right).orElse("");
        } catch (RuntimeException failure) {
            if (isExpectedFailure(failure)) {
                log.warn("Image recognition failed: user={}, failure={}",
                        SafeLog.user(request.userProfile().getUserId()), SafeLog.failure(failure));
            } else {
                logUnexpected(UnexpectedStage.RECOGNITION, failure);
            }
            return new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.RECOGNITION);
        }
        if (translatableText.isBlank() || (!recognition.regions().isEmpty() && translatedDecisions.isEmpty())) {
            return new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.NO_TEXT);
        }

        // Recognized text is content, never a command: an image cannot choose its own
        // target, so the workflow resolves it from User Preferences.
        ImageTranslationContext context = new ImageTranslationContext(
                new DownloadedImage(image.bytes(), image.contentType()),
                storage,
                recognition.text(),
                null);
        TranslationWorkflowOutcome workflowOutcome;
        List<String> translatableRegionIds = List.of();
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
                            protectedTokens(decision),
                            decision.qualification() == OcrQualification.TRANSLATE,
                            decision.region().readingOrder(),
                            layout(decision.region())))
                    .toList();
            translatableRegionIds = structuredRegions.stream()
                    .filter(ImageRegionTranslationInput::translatable)
                    .map(ImageRegionTranslationInput::regionId)
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
            if (isExpectedFailure(failure)) {
                log.warn("Image translation workflow failed: user={}, failure={}",
                        SafeLog.user(request.userProfile().getUserId()), SafeLog.failure(failure));
            } else {
                logUnexpected(UnexpectedStage.TRANSLATION, failure);
            }
            return new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.TRANSLATION);
        }
        if (workflowOutcome instanceof TranslationWorkflowOutcome.Failure failure) {
            if (failure.failure() == null) {
                // A failed outcome must say why; one that does not is a defect of the workflow.
                logUnexpected(UnexpectedStage.TRANSLATION,
                        new IllegalStateException("Translation workflow failed without a failure"));
            } else if (failure.failure().outcome() == AiProviderException.Outcome.UNEXPECTED_ERROR) {
                // The provider module reports a bug it caught as a failed outcome, with the cause.
                logUnexpected(UnexpectedStage.TRANSLATION, failure.failure().toException());
            }
            return new ImageTranslationOutcome.Failure(
                    ImageTranslationFailureStage.TRANSLATION,
                    failure.failure());
        }

        TranslationWorkflowOutcome.Success workflowSuccess =
                (TranslationWorkflowOutcome.Success) workflowOutcome;
        TranslationWorkflowResult translation = workflowSuccess.result();
        if (workflowSuccess.unexpectedFailure() != null) {
            // The workflow made up for a provider bug (a structured attempt) with a plain-text
            // translation: the image is served, but the bug is still reported.
            logUnexpected(UnexpectedStage.TRANSLATION, workflowSuccess.unexpectedFailure().toException());
        }
        long translationMillis = elapsedMillis(translationStarted);
        long renderingStarted = System.nanoTime();
        OverlayOutcome overlay = renderAndStore(image, recognition, translation, translatableRegionIds)
                .omitting(omittedRegions);
        long renderingMillis = elapsedMillis(renderingStarted);
        log.info("Image translation timing: downloadMs={}, storageMs={}, recognitionMs={}, "
                        + "translationMs={}, renderingMs={}, totalMs={}, ocrRegions={}, translatedRegions={}, "
                        + "omittedRegions={}",
                downloadMillis,
                storageMillis,
                recognitionMillis,
                translationMillis,
                renderingMillis,
                elapsedMillis(pipelineStarted),
                recognition.regions().size(),
                translatedDecisions.size(),
                omittedRegions);
        return new ImageTranslationOutcome.Success(
                new ImageTranslationPipelineResult(
                        context,
                        translation,
                        overlay.storage(),
                        overlay.lowConfidenceBlockCount(),
                        overlay.disposition(),
                        overlay.degradation()));
    }

    /**
     * A preserved region is sent with translatable=false and its tokens are never encoded or
     * checked, so it carries none. A translatable region's tokens are bounded to the input
     * contract so a dense receipt or menu cannot fail the whole image.
     */
    private static List<String> protectedTokens(OcrRegionDecision decision) {
        if (decision.qualification() != OcrQualification.TRANSLATE) return List.of();
        List<String> derived = decision.protectedTokens();
        List<String> bounded = ImageRegionTranslationInput.boundedProtectedTokens(derived);
        if (bounded.size() < derived.size()) {
            log.info("Image region protected tokens bounded: region={}, derived={}, kept={}",
                    decision.region().id(), derived.size(), bounded.size());
        }
        return bounded;
    }

    /**
     * The leading regions, in reading order, that fit the region and character bounds. Segmenting
     * one paragraph into rows and columns can multiply the regions Vision returned, so the bounds
     * cannot be checked on the raw OCR. The first translatable region is always kept, so an image
     * with text is not turned into "no text": when preserved regions such as prices come before it
     * and would break a bound, the oldest of them are dropped instead. Both bounds always hold.
     */
    private static List<OcrRegionDecision> withinModelLimits(List<OcrRegionDecision> decisions) {
        int first = IntStream.range(0, decisions.size())
                .filter(index -> decisions.get(index).qualification() == OcrQualification.TRANSLATE)
                .findFirst().orElse(-1);
        int start = Math.max(first, 0);
        int end = first < 0 ? 0 : first + 1;
        int characters = first < 0 ? 0 : textLength(decisions.get(first));
        while (start > 0 && end - start < MAX_OCR_REGIONS
                && characters + textLength(decisions.get(start - 1)) <= MAX_OCR_TEXT_CHARS) {
            characters += textLength(decisions.get(--start));
        }
        while (end < decisions.size() && end - start < MAX_OCR_REGIONS
                && characters + textLength(decisions.get(end)) <= MAX_OCR_TEXT_CHARS) {
            characters += textLength(decisions.get(end++));
        }
        return decisions.subList(start, end);
    }

    private static int textLength(OcrRegionDecision decision) {
        return decision.region().text().length();
    }

    /**
     * The failures a stage is known to end in: the OCR, provider, structured-mapping, renderer
     * and data-store exceptions that report an unavailable or unusable service, not a bug. They
     * degrade the image as they always did. Anything else thrown by a stage is a defect of the
     * pipeline; it degrades the image the same way but is reported by {@link #logUnexpected}.
     * Two exception types are also used by the adapters to carry a bug they caught, and are not
     * known failures: {@link OcrDefectException} and an {@link AiProviderException} with
     * {@link AiProviderException.Outcome#UNEXPECTED_ERROR}.
     */
    private static boolean isExpectedFailure(RuntimeException failure) {
        return (failure instanceof OcrProcessingException && !(failure instanceof OcrDefectException))
                || failure instanceof InvalidImageException
                || (failure instanceof AiProviderException provider
                        && provider.getOutcome() != AiProviderException.Outcome.UNEXPECTED_ERROR)
                || failure instanceof StructuredTranslationException
                || failure instanceof RenderedImageEncodingException
                || failure instanceof DataAccessException;
    }

    /**
     * Records a defect once, at ERROR, with its stack frames and without any message (see
     * {@link SafeLog#redacted}), and counts it so a bug is told apart from an outage.
     */
    private void logUnexpected(UnexpectedStage stage, RuntimeException failure) {
        unexpectedFailures.get(stage).increment();
        log.error("Image translation stage failed unexpectedly: stage={}, failure={}",
                stage.tag(), SafeLog.failure(failure), SafeLog.redacted(failure));
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
            if (isExpectedFailure(failure)) {
                log.warn("Image storage degraded: failure={}", SafeLog.failure(failure));
            } else {
                logUnexpected(UnexpectedStage.STORAGE, failure);
            }
            return ImageStorageResult.notStored();
        }
    }

    private OcrRecognition recognize(ImageTranslationRequest request, ValidatedImage image) {
        if (ocrService != null) {
            try {
                OcrRecognition recognition;
                try (InputStream stream = new java.io.ByteArrayInputStream(image.bytes())) {
                    recognition = ocrService.recognize(stream);
                }
                List<OcrRegion> regions = recognition.regions();
                if (regions.stream().map(OcrRegion::id).distinct().count() != regions.size()) {
                    // A fault of the OCR provider, not of the upload: use AI recognition instead.
                    throw new OcrProcessingException("OCR result contains duplicate region ids");
                }
                return recognition;
            } catch (OcrProcessingException failure) {
                if (failure instanceof OcrDefectException) {
                    // A bug in the OCR adapter: AI recognition still serves the image, but the
                    // defect is reported instead of passing for an unavailable provider.
                    logUnexpected(UnexpectedStage.RECOGNITION, failure);
                }
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
            TranslationWorkflowResult translation,
            List<String> translatableRegionIds) {
        if (recognition.regions().isEmpty()) {
            return OverlayOutcome.unavailable();
        }
        if (translation.imageRegionTranslations().isEmpty()) {
            return new OverlayOutcome(ImageStorageResult.notStored(), 0,
                    ImageOverlayDisposition.SAFETY_DEGRADED,
                    OverlayDegradationSummary.single(OverlayDegradationReason.MAPPING,
                            Math.max(1, translatableRegionIds.size())));
        }
        try {
            java.util.Map<String, String> replacements = translation.imageRegionTranslations().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            com.linetranslate.bot.service.translation.ImageRegionTranslation::regionId,
                            com.linetranslate.bot.service.translation.ImageRegionTranslation::translatedText));
            // Regions the provider never answered keep their source pixels and are reported, not hidden.
            OverlayDegradationSummary unmapped = OverlayDegradationSummary.single(
                    OverlayDegradationReason.MAPPING,
                    (int) translatableRegionIds.stream().filter(id -> !replacements.containsKey(id)).count());
            List<ImageRegionOverlay> candidates = recognition.regions().stream()
                    .filter(region -> replacements.containsKey(region.id()))
                    .map(region -> new ImageRegionOverlay(region, replacements.get(region.id())))
                    .toList();
            OverlayContentMode contentMode = contentClassifier.classify(
                    image.image(), candidates.stream().map(ImageRegionOverlay::region).toList());
            OverlaySafetyPlan plan = overlaySafetyPolicy.evaluate(
                    candidates, image.image().getWidth(), image.image().getHeight(), properties, contentMode);
            log.info("Image overlay decision: mode={}, candidates={}, accepted={}, preserved={}, "
                            + "unmappedRegions={}, reason={}",
                    contentMode, candidates.size(), plan.overlays().size(), plan.skipped(),
                    unmapped.count(OverlayDegradationReason.MAPPING), plan.reason());
            if (!plan.safe() || plan.overlays().isEmpty()) {
                OverlayDegradationSummary degradation =
                        OverlayDegradationSummary.fromDecisions(plan.decisions()).merge(unmapped);
                return new OverlayOutcome(ImageStorageResult.notStored(),
                        degradation.count(OverlayDegradationReason.LOW_CONFIDENCE),
                        ImageOverlayDisposition.SAFETY_DEGRADED, degradation);
            }
            long rendererStarted = System.nanoTime();
            RenderedImage rendered = overlayRenderer.render(image, plan);
            long rendererMillis = elapsedMillis(rendererStarted);
            byte[] renderedBytes = rendered.bytes();
            if (rendered.renderedBlockCount() == 0) {
                log.info("Image overlay timing: rendererMs={}, uploadMs=0, renderedRegions=0, outputBytes={}",
                        rendererMillis, renderedBytes.length);
                return new OverlayOutcome(
                        ImageStorageResult.notStored(), rendered.lowConfidenceBlockCount(),
                        ImageOverlayDisposition.SAFETY_DEGRADED, rendered.degradation().merge(unmapped));
            }
            long uploadStarted = System.nanoTime();
            ImageStorageResult storage = minioStorageService.uploadTranslatedImage(
                    renderedBytes, rendered.contentType());
            long uploadMillis = elapsedMillis(uploadStarted);
            log.info("Image overlay timing: rendererMs={}, uploadMs={}, renderedRegions={}, outputBytes={}",
                    rendererMillis, uploadMillis, rendered.renderedBlockCount(), renderedBytes.length);
            return new OverlayOutcome(
                    storage == null ? ImageStorageResult.notStored() : storage,
                    rendered.lowConfidenceBlockCount(),
                    storage != null && storage.stored()
                            ? ImageOverlayDisposition.GENERATED : ImageOverlayDisposition.UNAVAILABLE,
                    rendered.degradation().merge(unmapped).merge(storage != null && storage.stored()
                            ? OverlayDegradationSummary.none()
                            : OverlayDegradationSummary.single(OverlayDegradationReason.STORAGE, 1)));
        } catch (RuntimeException failure) {
            if (isExpectedFailure(failure)) {
                log.warn("Translated image rendering degraded to text: failure={}", SafeLog.failure(failure));
            } else {
                logUnexpected(UnexpectedStage.RENDERING, failure);
            }
            return new OverlayOutcome(ImageStorageResult.notStored(), 0,
                    ImageOverlayDisposition.SAFETY_DEGRADED,
                    OverlayDegradationSummary.single(OverlayDegradationReason.OTHER, 1));
        }
    }

    static ImageRegionLayout layout(OcrRegion region) {
        java.awt.Rectangle bounds = OverlaySafetyPolicy.polygon(region.polygon()).getBounds();
        OcrPoint origin = region.polygon().get(0);
        OcrPoint edge = region.polygon().get(1);
        OcrPoint side = region.polygon().get(region.polygon().size() - 1);
        int localWidth = Math.max(1, (int) Math.round(Math.hypot(
                edge.x() - origin.x(), edge.y() - origin.y())));
        int localHeight = Math.max(1, (int) Math.round(Math.hypot(
                side.x() - origin.x(), side.y() - origin.y())));
        List<Integer> wordHeights = region.words().stream()
                .map(OcrWord::polygon).filter(points -> points.size() >= 4)
                .map(points -> (int) Math.round(Math.hypot(
                        points.get(points.size() - 1).x() - points.get(0).x(),
                        points.get(points.size() - 1).y() - points.get(0).y())))
                .filter(value -> value > 1).sorted().toList();
        int sourceHeight = wordHeights.isEmpty()
                ? Math.min(localHeight, 24) : wordHeights.get(wordHeights.size() / 2);
        int maxLines = region.compactLabel() ? 1
                : Math.max(1, (int) Math.floor((double) localHeight / Math.max(10, sourceHeight * 1.15)));
        int charactersPerLine = Math.max(2,
                (int) Math.floor(localWidth / Math.max(4d, sourceHeight * .50d)));
        int geometricCapacity = Math.max(2, charactersPerLine * maxLines);
        int sourceCharacters = region.text().codePointCount(0, region.text().length());
        int maxCharacters = region.compactLabel()
                ? geometricCapacity
                : Math.max(geometricCapacity, sourceCharacters * 2);
        return new ImageRegionLayout(region.groupId(), bounds.x, bounds.y,
                localWidth, localHeight, maxLines, maxCharacters, region.compactLabel());
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

        /** Regions left out for exceeding the processing limits, reported as a count only. */
        OverlayOutcome omitting(int regions) {
            return new OverlayOutcome(storage, lowConfidenceBlockCount, disposition,
                    degradation.merge(OverlayDegradationSummary.single(OverlayDegradationReason.LIMIT, regions)));
        }
    }
}
