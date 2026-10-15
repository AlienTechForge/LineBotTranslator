package com.linetranslate.bot.service.ocr;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    /** The OCR bound when none is wired: the default of {@code google.cloud.vision.timeout-ms}. */
    static final long DEFAULT_OCR_TIMEOUT_MS = 60_000;
    /**
     * Reading a rotated page again must earn its extra call: the second reading is kept only when
     * its reliable text weighs at least this many percent of the first reading's (110, so 10% more).
     * Whole numbers on purpose: a compare in floating point puts 1.10 x 20 above 22.
     */
    private static final int RETRY_MIN_GAIN_PERCENT = 110;
    private static final float RETRY_JPEG_QUALITY = .92f;

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
    private final long ocrTimeoutMs;
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
            MeterRegistry meterRegistry,
            @Value("${google.cloud.vision.timeout-ms:60000}") long ocrTimeoutMs) {
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
        this.ocrTimeoutMs = ocrTimeoutMs > 0 ? ocrTimeoutMs : DEFAULT_OCR_TIMEOUT_MS;
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
            OverlaySafetyPolicy overlaySafetyPolicy,
            TranslationPromptFactory promptFactory,
            MeterRegistry meterRegistry) {
        this(ocrServiceProvider, translationWorkflowModule, aiProviderExecutionModule,
                userPreferencesModule, messagingApiBlobClient, minioStorageService,
                imageInputValidator, overlayRenderer, properties, qualificationPolicy,
                sourceLanguageResolver, overlaySafetyPolicy, promptFactory, meterRegistry,
                DEFAULT_OCR_TIMEOUT_MS);
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
        // OCR calls made for this image: the first, and the second reading of a rotated page.
        int ocrCalls = ocrService == null ? 0 : 1;
        List<OcrRegionDecision> workflowDecisions;
        List<OcrRegionDecision> translatedDecisions;
        int omittedRegions;
        String translatableText;
        // The whole recognition stage is guarded: the OCR call and everything computed from its
        // result before the translation request is built.
        try {
            recognition = recognize(request, image);
            long firstCallMillis = elapsedMillis(stageStarted);
            if (recognition.frame().rotated()) {
                // The regions are in the upright frame of a rotated page, so the raster is turned
                // into it before anything reads either. Assigning drops the original raster, and
                // the request keeps the upright one; the original bytes stay what is stored.
                ValidatedImage upright = uprightImage(image, recognition.frame());
                if (upright == null) {
                    recognition = inImageFrame(recognition);
                } else {
                    image = upright;
                    if (properties.orientationRetryEnabled() && recognition.frame().retryAdvised()) {
                        RetryOutcome retry = retryFromUpright(image, recognition, firstCallMillis);
                        recognition = retry.recognition();
                        ocrCalls += retry.calls();
                    }
                }
            }
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

        ImageTranslationContext context = new ImageTranslationContext(
                new DownloadedImage(image.bytes(), image.contentType()),
                storage,
                recognition.text());
        // Storage, OCR and the context were the last readers of the encoded bytes. The overlay
        // stage is the only one left that needs the image, so it becomes its sole owner.
        SourceImage source = new SourceImage(image);
        image = null;
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
                    // Recognized text is content, never a command: an image cannot choose its own
                    // target, so the workflow resolves it from User Preferences.
                    null,
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
        OverlayOutcome overlay = renderAndStore(source, recognition, translation, translatableRegionIds)
                .omitting(omittedRegions);
        long renderingMillis = elapsedMillis(renderingStarted);
        log.info("Image translation timing: downloadMs={}, storageMs={}, recognitionMs={}, "
                        + "translationMs={}, renderingMs={}, totalMs={}, ocrRegions={}, translatedRegions={}, "
                        + "omittedRegions={}, orientationQuarterTurns={}, ocrCalls={}",
                downloadMillis,
                storageMillis,
                recognitionMillis,
                translationMillis,
                renderingMillis,
                elapsedMillis(pipelineStarted),
                recognition.regions().size(),
                translatedDecisions.size(),
                omittedRegions,
                recognition.frame().quarterTurns(),
                ocrCalls);
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

    /**
     * Draws, uploads and previews the translated image. The source is taken out of its holder only
     * by the renderer call, and nothing here keeps a local to the image or its raster, so the
     * source pixels are collectable once the overlay is drawn and the rendered raster is dropped
     * as soon as the upload (and with it the preview) is done.
     */
    private OverlayOutcome renderAndStore(
            SourceImage source,
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
                    source.raster(), candidates.stream().map(ImageRegionOverlay::region).toList());
            OverlaySafetyPlan plan = overlaySafetyPolicy.evaluate(
                    candidates, source.raster().getWidth(), source.raster().getHeight(),
                    properties, contentMode);
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
            // An upright page is turned back to the original frame by the renderer. A page that was
            // never turned takes the unchanged call.
            int restoreQuarterTurns = recognition.frame().quarterTurns();
            RenderedImage rendered = restoreQuarterTurns == 0
                    ? overlayRenderer.render(source.take(), plan)
                    : overlayRenderer.render(source.take(), plan, restoreQuarterTurns);
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
                    renderedBytes, rendered.contentType(), rendered.raster());
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

    /**
     * The image turned into the upright frame of a rotated page, or null when the page the
     * provider described is not the image that was sent (a size the provider cannot have seen, so
     * the turn would put the regions in the wrong place). The returned image keeps the original
     * bytes: they are what is stored and what the context carries.
     */
    private static ValidatedImage uprightImage(ValidatedImage image, PageFrame frame) {
        if (image.image().getWidth() != frame.sourceWidth() || image.image().getHeight() != frame.sourceHeight()) {
            log.warn("Page orientation normalization skipped: verdict=frame-mismatch, quarterTurns={}, "
                            + "pageWidth={}, pageHeight={}, imageWidth={}, imageHeight={}",
                    frame.quarterTurns(), frame.sourceWidth(), frame.sourceHeight(),
                    image.image().getWidth(), image.image().getHeight());
            return null;
        }
        return new ValidatedImage(image.bytes(), image.contentType(),
                QuarterTurns.toUpright(image.image(), frame.quarterTurns()));
    }

    /** The regions of a recognition moved back to the frame of the image: the exact inverse of the turn. */
    private static OcrRecognition inImageFrame(OcrRecognition recognition) {
        PageFrame frame = recognition.frame();
        return new OcrRecognition(recognition.text(), recognition.blocks(),
                QuarterTurns.fromUpright(recognition.regions(), frame.quarterTurns(),
                        frame.sourceWidth(), frame.sourceHeight()),
                PageFrame.NONE);
    }

    /** The recognition to carry on with, and how many OCR calls were made to get it (0 or 1 here). */
    private record RetryOutcome(OcrRecognition recognition, int calls) {
    }

    /**
     * Reads a rotated page that was read poorly a second time, from its upright raster, and keeps
     * the second reading only when it is clearly better. The retry never decides whether a page is
     * rotated or written vertically: that was settled before it, from the first reading's
     * geometry. It never turns an image into a failure and never falls back to AI recognition: any
     * failure keeps the first reading.
     */
    private RetryOutcome retryFromUpright(ValidatedImage upright, OcrRecognition first, long firstCallMillis) {
        PageFrame frame = first.frame();
        if (firstCallMillis * 2 > ocrTimeoutMs) {
            logRetry("skipped", "slow-first-call", frame.reliableShare(), -1, null, 0, 0, "n/a");
            return new RetryOutcome(first, 0);
        }
        byte[] bytes;
        String contentType;
        try {
            boolean opaque = !upright.image().getColorModel().hasAlpha();
            bytes = opaque
                    ? ImageTranslationOverlayRenderer.writeJpeg(upright.image(), RETRY_JPEG_QUALITY)
                    : ImageTranslationOverlayRenderer.writePng(upright.image());
            contentType = opaque ? "jpeg" : "png";
        } catch (java.io.IOException failure) {
            logRetry("skipped", "encode-failed", frame.reliableShare(), -1, null, 0, 0, "n/a");
            return new RetryOutcome(first, 0);
        }
        if (bytes.length > properties.maxFileSizeBytes()) {
            logRetry("skipped", "too-large", frame.reliableShare(), -1, null, 0, bytes.length, contentType);
            return new RetryOutcome(first, 0);
        }
        long started = System.nanoTime();
        OcrRecognition retried;
        try (InputStream stream = new java.io.ByteArrayInputStream(bytes)) {
            retried = ocrService.recognize(stream);
        } catch (RuntimeException | java.io.IOException failure) {
            // A timeout or a provider failure is an outage; anything else is a defect, which is
            // counted like any other. Either way the first reading stands.
            if (failure instanceof RuntimeException defect && !isExpectedFailure(defect)) {
                logUnexpected(UnexpectedStage.RECOGNITION, defect);
            }
            log.info("Page orientation retry: outcome=failed, failure={}, firstReliableShare={}, "
                            + "retryMs={}, encodedBytes={}, format={}",
                    SafeLog.failure(failure), ratio(frame.reliableShare()), elapsedMillis(started),
                    bytes.length, contentType);
            return new RetryOutcome(first, 1);
        }
        long retryMillis = elapsedMillis(started);
        if (retried == null) {
            logRetry("rejected", "no-regions", frame.reliableShare(), -1, null, retryMillis,
                    bytes.length, contentType);
            return new RetryOutcome(first, 1);
        }
        PageFrame second = retried.frame();
        List<OcrRegion> regions = retried.regions();
        String rejection = null;
        if (regions.isEmpty()) {
            rejection = "no-regions";
        } else if (regions.stream().map(OcrRegion::id).distinct().count() != regions.size()) {
            rejection = "duplicate-ids";
        } else if (second.verdict() != PageOrientation.Verdict.UPRIGHT) {
            rejection = "not-upright";
        } else if (second.sourceWidth() != upright.image().getWidth()
                || second.sourceHeight() != upright.image().getHeight()) {
            // Boxes the provider placed on a page of another size cannot be drawn on this raster.
            rejection = "frame-mismatch";
        } else {
            long retryWeight = PageOrientationDetector.reliableWeight(regions);
            long firstWeight = PageOrientationDetector.reliableWeight(first.regions());
            if (retryWeight <= 0 || retryWeight * 100 < firstWeight * RETRY_MIN_GAIN_PERCENT) {
                rejection = "no-gain";
            }
        }
        logRetry(rejection == null ? "accepted" : "rejected", rejection == null ? "better-reading" : rejection,
                frame.reliableShare(), second.reliableShare(), second.verdict(), retryMillis,
                bytes.length, contentType);
        if (rejection != null) {
            return new RetryOutcome(first, 1);
        }
        // The regions are upright, as the first reading's were after the turn, so the frame keeps
        // the turn and the renderer still restores the original orientation.
        return new RetryOutcome(new OcrRecognition(retried.text(), retried.blocks(), regions,
                new PageFrame(frame.quarterTurns(), frame.sourceWidth(), frame.sourceHeight(),
                        PageOrientation.Verdict.ROTATED, false, second.reliableShare())), 1);
    }

    private static void logRetry(String outcome, String reason, double firstReliableShare,
            double retryReliableShare, PageOrientation.Verdict retryVerdict, long retryMillis,
            int encodedBytes, String format) {
        log.info("Page orientation retry: outcome={}, reason={}, firstReliableShare={}, "
                        + "retryReliableShare={}, retryVerdict={}, retryMs={}, encodedBytes={}, format={}",
                outcome, reason, ratio(firstReliableShare), ratio(retryReliableShare),
                retryVerdict == null ? "n/a" : retryVerdict, retryMillis, encodedBytes, format);
    }

    private static String ratio(double value) {
        return value < 0 ? "n/a" : String.format(Locale.ROOT, "%.3f", value);
    }

    static ImageRegionLayout layout(OcrRegion region) {
        if (region.writingMode().vertical()) return verticalLayout(region);
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

    /**
     * The budget of a region of upright vertical writing, measured along its columns: a line is a
     * column, so its length is the height of the region and the lines stack across its width. A glyph
     * is as wide as a column is thick, and a column leaves a fifth of a glyph between itself and the
     * next. Measured across the horizontal axes, a 30x163 column would read as four lines of two
     * characters, which is not what it can hold.
     */
    private static ImageRegionLayout verticalLayout(OcrRegion region) {
        java.awt.Rectangle bounds = OverlaySafetyPolicy.polygon(region.polygon()).getBounds();
        List<OcrPoint> upright = region.uprightPolygon();
        OcrPoint origin = upright.get(0);
        OcrPoint edge = upright.get(1);
        OcrPoint side = upright.get(upright.size() - 1);
        int width = Math.max(1, (int) Math.round(Math.hypot(
                edge.x() - origin.x(), edge.y() - origin.y())));
        int height = Math.max(1, (int) Math.round(Math.hypot(
                side.x() - origin.x(), side.y() - origin.y())));
        List<Integer> glyphs = region.words().stream()
                .map(OcrWord::polygon).filter(points -> points.size() >= 4)
                .map(points -> (int) Math.round(OcrRegion.uprightWidth(points)))
                .filter(value -> value > 1).sorted().toList();
        int glyph = glyphs.isEmpty() ? Math.min(width, 24) : glyphs.get(glyphs.size() / 2);
        int maxLines = region.compactLabel() ? 1
                : Math.max(1, (int) Math.floor(width / Math.max(10d, glyph * 1.2)));
        int charactersPerLine = Math.max(2, (int) Math.floor(height / Math.max(4d, glyph)));
        int geometricCapacity = Math.max(2, charactersPerLine * maxLines);
        int sourceCharacters = region.text().codePointCount(0, region.text().length());
        int maxCharacters = region.compactLabel()
                ? geometricCapacity
                : Math.max(geometricCapacity, sourceCharacters * 2);
        // As for any region, width is the length of a line and height the room the lines stack in.
        return new ImageRegionLayout(region.groupId(), bounds.x, bounds.y,
                height, width, maxLines, maxCharacters, region.compactLabel());
    }

    /**
     * Hands a request's decoded image to the one stage that consumes it. {@link #take()} gives the
     * image to its caller and forgets it, so that stage is the image's only owner and can let it
     * go when it no longer needs it; a plain local in the pipeline would keep it reachable until
     * the whole request ends. Confined to one request, never shared between threads.
     */
    private static final class SourceImage {

        private ValidatedImage image;

        SourceImage(ValidatedImage image) {
            this.image = image;
        }

        /**
         * The decoded pixels, for reading before {@link #take()}. Use the result inline: a local
         * holding it would pin the raster for as long as the local is in scope.
         */
        BufferedImage raster() {
            return image.image();
        }

        ValidatedImage take() {
            ValidatedImage taken = image;
            image = null;
            return taken;
        }
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
