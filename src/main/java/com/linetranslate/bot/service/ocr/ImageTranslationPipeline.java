package com.linetranslate.bot.service.ocr;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.linecorp.bot.client.base.BlobContent;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.service.imagetranslator.ImageTranslatorClient;
import com.linetranslate.bot.service.imagetranslator.ImageTranslatorException;
import com.linetranslate.bot.service.imagetranslator.ImageTranslatorResult;
import com.linetranslate.bot.service.preference.UserPreferences;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.storage.ImageStorageResult;
import com.linetranslate.bot.service.storage.MinioStorageService;
import com.linetranslate.bot.service.translation.TargetLocalePolicy;
import com.linetranslate.bot.service.translation.TranslationStylePreset;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Image translation pipeline Module: a LINE picture is downloaded, validated and stored, handed to the
 * image-translator service in the user's target language, and what comes back is turned into the
 * stored translated picture (or the translated text) and a {@code TranslationRecord}. Every stage
 * consumes and returns explicit values; no request state is stored on threads or singleton fields.
 *
 * <p>The service answers with a status: {@code image} (a picture to send, {@code clean} or
 * {@code warned}), {@code text_only} (the picture is not good enough, send the translation as text),
 * {@code no_text} and {@code error}. A picture that cannot be fetched, is too large to deliver or is
 * refused by storage degrades to the {@code text_only} reply when the service gave a translation.
 */
@Service
@Slf4j
public class ImageTranslationPipeline {

    /** The {@code aiProvider} of the records saved here, so the statistics keep them apart from OpenRouter's. */
    static final String PROVIDER_NAME = "image-translator";

    /** The service's {@code error} reasons that mean this month's money is gone. */
    private static final Set<String> BUDGET_REASONS = Set.of("anthropic_budget", "openrouter_budget");

    private static final TargetLocalePolicy LOCALES = new TargetLocalePolicy();

    /** Where a defect was caught; the lower-case name is the {@code stage} tag of the counter. */
    private enum UnexpectedStage {
        STORAGE, TRANSLATION, DELIVERY, RECORDING;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final ImageTranslatorClient imageTranslatorClient;
    private final TranslationRecordRepository translationRecordRepository;
    private final UserPreferencesModule userPreferencesModule;
    private final MessagingApiBlobClient messagingApiBlobClient;
    private final MinioStorageService minioStorageService;
    private final ImageInputValidator imageInputValidator;
    private final ImageTranslationProperties properties;
    private final Map<UnexpectedStage, Counter> unexpectedFailures = new EnumMap<>(UnexpectedStage.class);

    @Autowired
    public ImageTranslationPipeline(
            ImageTranslatorClient imageTranslatorClient,
            TranslationRecordRepository translationRecordRepository,
            UserPreferencesModule userPreferencesModule,
            MessagingApiBlobClient messagingApiBlobClient,
            MinioStorageService minioStorageService,
            ImageInputValidator imageInputValidator,
            ImageTranslationProperties properties,
            MeterRegistry meterRegistry) {
        this.imageTranslatorClient = imageTranslatorClient;
        this.translationRecordRepository = translationRecordRepository;
        this.userPreferencesModule = userPreferencesModule;
        this.messagingApiBlobClient = messagingApiBlobClient;
        this.minioStorageService = minioStorageService;
        this.imageInputValidator = imageInputValidator;
        this.properties = properties;
        for (UnexpectedStage stage : UnexpectedStage.values()) {
            unexpectedFailures.put(stage, Counter.builder("image.translation.unexpected")
                    .description("Unexpected exceptions caught inside the image translation pipeline")
                    .tag("stage", stage.tag())
                    .register(meterRegistry));
        }
    }

    /** Compatibility constructor for focused tests: default limits, no validation of the pixels, own meters. */
    public ImageTranslationPipeline(
            ImageTranslatorClient imageTranslatorClient,
            TranslationRecordRepository translationRecordRepository,
            UserPreferencesModule userPreferencesModule,
            MessagingApiBlobClient messagingApiBlobClient,
            MinioStorageService minioStorageService) {
        this(imageTranslatorClient, translationRecordRepository, userPreferencesModule,
                messagingApiBlobClient, minioStorageService,
                ImageInputValidator.trustedTestSeam(ImageTranslationProperties.defaults()),
                ImageTranslationProperties.defaults(), new SimpleMeterRegistry());
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

        UserPreferences preferences = userPreferencesModule.resolve(request.userProfile());
        String targetLanguage = LOCALES.resolve(preferences.targetLanguage()).locale();
        // The service takes minutes; only the encoded bytes are needed for the call, so the decoded
        // raster (four bytes per pixel) becomes collectable now. The original is kept as the context.
        byte[] bytes = image.bytes();
        String contentType = image.contentType();
        image = null;

        stageStarted = System.nanoTime();
        ImageTranslatorResult result;
        try {
            result = imageTranslatorClient.translate(bytes, contentType, targetLanguage, request.messageId());
        } catch (ImageTranslatorException failure) {
            return serviceFailure(failure);
        } catch (RuntimeException failure) {
            logUnexpected(UnexpectedStage.TRANSLATION, failure);
            return new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.SERVICE_UNAVAILABLE);
        }
        long serviceMillis = elapsedMillis(stageStarted);
        log.info("Image translator answered: status={}, tier={}, reason={}, mode={}, verdict={}, usability={}, "
                        + "costUsd={}, warnings={}",
                SafeLog.metadata(result.status()), SafeLog.metadata(result.tier()), SafeLog.metadata(result.reason()),
                SafeLog.metadata(result.mode()), SafeLog.metadata(result.verdict()), result.usability(),
                result.costs().totalUsd(), result.warnings().size());

        ImageTranslationOutcome outcome = switch (result.status() == null ? "" : result.status()) {
            case ImageTranslatorResult.STATUS_NO_TEXT ->
                    new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.NO_TEXT);
            case ImageTranslatorResult.STATUS_ERROR -> new ImageTranslationOutcome.Failure(
                    BUDGET_REASONS.contains(result.reason())
                            ? ImageTranslationFailureStage.BUDGET : ImageTranslationFailureStage.TRANSLATION);
            case ImageTranslatorResult.STATUS_IMAGE, ImageTranslatorResult.STATUS_TEXT_ONLY ->
                    answer(request, preferences, targetLanguage,
                            new ImageTranslationContext(new DownloadedImage(bytes, contentType), storage), result);
            default -> {
                log.warn("Image translator answered with an unknown status: status={}", SafeLog.metadata(result.status()));
                yield new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.TRANSLATION);
            }
        };
        String delivered = !(outcome instanceof ImageTranslationOutcome.Success success) ? "none"
                : success.result().renderedImage().url().isPresent() ? "picture" : "translation";
        log.info("Image translation timing: downloadMs={}, storageMs={}, serviceMs={}, totalMs={}, delivered={}",
                downloadMillis, storageMillis, serviceMillis, elapsedMillis(pipelineStarted), delivered);
        return outcome;
    }

    /**
     * A failed call: the service already having this request (a redelivery of the LINE event: 409) is
     * the generic failure, and nothing is translated a second time; any other HTTP error, a timeout or an
     * unusable answer is "the service is unavailable".
     */
    private ImageTranslationOutcome serviceFailure(ImageTranslatorException failure) {
        if (Thread.currentThread().isInterrupted()) {
            return new ImageTranslationOutcome.Failure(ImageTranslationFailureStage.CANCELLED);
        }
        log.warn("Image translator call failed: kind={}, status={}, reason={}",
                failure.kind(), SafeLog.httpStatus(failure.httpStatus()), SafeLog.metadata(failure.reason()));
        return new ImageTranslationOutcome.Failure(failure.isDuplicateRequest()
                ? ImageTranslationFailureStage.TRANSLATION
                : ImageTranslationFailureStage.SERVICE_UNAVAILABLE);
    }

    /**
     * An {@code image} or {@code text_only} answer: the picture is fetched and stored when there is one
     * to send, and a record is saved for the translation. When there is no deliverable picture the reply
     * is the translated text, and a translation that is not there leaves nothing to send.
     */
    private ImageTranslationOutcome answer(
            ImageTranslationRequest request,
            UserPreferences preferences,
            String targetLanguage,
            ImageTranslationContext context,
            ImageTranslatorResult result) {
        ImageStorageResult rendered = ImageTranslatorResult.STATUS_IMAGE.equals(result.status())
                ? deliver(result) : ImageStorageResult.notStored();
        boolean picture = rendered.url().isPresent();
        ImageTranslatorResult.Text text = result.text();
        boolean hasText = text.translatedText() != null && !text.translatedText().isBlank();
        if (!picture && !hasText) {
            log.warn("Image translation has neither a picture to send nor a translation: status={}",
                    SafeLog.metadata(result.status()));
            return new ImageTranslationOutcome.Failure(
                    ImageTranslatorResult.STATUS_IMAGE.equals(result.status())
                            ? ImageTranslationFailureStage.SERVICE_UNAVAILABLE
                            : ImageTranslationFailureStage.TRANSLATION);
        }
        TranslationStylePreset style = preferences.translationStyle();
        String recordId = save(request, targetLanguage, context.storage(), result, style);
        return new ImageTranslationOutcome.Success(new ImageTranslationPipelineResult(
                context,
                picture ? rendered : ImageStorageResult.notStored(),
                picture && ImageTranslatorResult.TIER_WARNED.equals(result.tier()),
                new ImageTranslationPipelineResult.Text(
                        text.recognizedText(), text.translatedText(), text.sourceLanguage(),
                        targetLanguage, recordId, style.id(), style.promptVersion())));
    }

    /**
     * Fetches the translated PNG from the service, brings it into a size LINE can load and stores it.
     * Any failure leaves {@link ImageStorageResult#notStored()}: the reply then falls back to the text.
     */
    private ImageStorageResult deliver(ImageTranslatorResult result) {
        if (result.image() == null) {
            log.warn("Image translator reported an image without a picture");
            return ImageStorageResult.notStored();
        }
        try {
            byte[] png = imageTranslatorClient.fetchImage(result.image());
            Optional<TranslatedImageEncoder.Encoded> fitted = TranslatedImageEncoder.fit(
                    png, properties.deliveryTargetBytes(), properties.maxOutputBytes());
            if (fitted.isEmpty()) {
                log.warn("Translated image cannot be delivered, falling back to the translation: pngBytes={}", png.length);
                return ImageStorageResult.notStored();
            }
            long uploadStarted = System.nanoTime();
            ImageStorageResult stored = minioStorageService.uploadTranslatedImage(
                    fitted.get().bytes(), fitted.get().contentType());
            log.info("Translated image upload: uploadMs={}, outputBytes={}, format={}, stored={}",
                    elapsedMillis(uploadStarted), fitted.get().bytes().length, fitted.get().contentType(),
                    stored != null && stored.url().isPresent());
            return stored == null ? ImageStorageResult.notStored() : stored;
        } catch (ImageTranslatorException failure) {
            log.warn("Translated image could not be fetched, falling back to the translation: kind={}, status={}, reason={}",
                    failure.kind(), SafeLog.httpStatus(failure.httpStatus()), SafeLog.metadata(failure.reason()));
            return ImageStorageResult.notStored();
        } catch (RuntimeException failure) {
            logUnexpected(UnexpectedStage.DELIVERY, failure);
            return ImageStorageResult.notStored();
        }
    }

    /**
     * Saves what the service made, as the record of an image translation, and counts it in the user's
     * profile. The picture is already made and paid for, so a store that is down costs the quick-reply
     * actions of the reply (no record id) and not the reply.
     */
    private String save(
            ImageTranslationRequest request,
            String targetLanguage,
            ImageStorageResult original,
            ImageTranslatorResult result,
            TranslationStylePreset style) {
        ImageTranslatorResult.Text text = result.text();
        String translated = text.translatedText() == null ? "" : text.translatedText();
        String userId = request.userProfile().getUserId();
        String recordId = null;
        try {
            TranslationRecord record = TranslationRecord.builder()
                    .userId(userId)
                    .sourceText(text.recognizedText() == null ? "" : text.recognizedText())
                    .sourceLanguage(text.sourceLanguage())
                    .targetLanguage(targetLanguage)
                    .translatedText(translated)
                    .aiProvider(PROVIDER_NAME)
                    .modelName(result.mode())
                    .stylePresetId(style.id())
                    .stylePromptVersion(style.promptVersion())
                    .createdAt(LocalDateTime.now())
                    .processingTimeMs(Math.max(0, Duration.between(request.startedAt(), Instant.now()).toMillis()))
                    .isImageTranslation(true)
                    .imageUrl(original.url().orElse(null))
                    .imageStored(original.stored())
                    .imageMode(result.mode())
                    .imageTier(result.tier())
                    .imageVerdict(result.verdict())
                    .imageUsability(result.usability())
                    .imageCostUsd(result.costs().totalUsd())
                    .build();
            TranslationRecord saved = translationRecordRepository.save(record);
            recordId = saved == null ? record.getId() : saved.getId();
        } catch (DataAccessException failure) {
            log.warn("Image translation record not saved: user={}, failure={}",
                    SafeLog.user(userId), SafeLog.failure(failure));
        } catch (RuntimeException failure) {
            logUnexpected(UnexpectedStage.RECORDING, failure);
        }
        try {
            userPreferencesModule.recordTranslationActivity(userId, targetLanguage, translated, true);
        } catch (DataAccessException failure) {
            log.warn("Image translation activity not recorded: user={}, failure={}",
                    SafeLog.user(userId), SafeLog.failure(failure));
        } catch (RuntimeException failure) {
            logUnexpected(UnexpectedStage.RECORDING, failure);
        }
        return recordId;
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
            logUnexpected(UnexpectedStage.STORAGE, failure);
            return ImageStorageResult.notStored();
        }
    }
}
