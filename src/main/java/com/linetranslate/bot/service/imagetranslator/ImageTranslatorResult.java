package com.linetranslate.bot.service.imagetranslator;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The 200 answer of {@code POST /v1/translations} (schema version 1), field for field. The names
 * are the service's snake_case names read through the client's naming strategy; fields this client
 * has no use for are ignored. A missing list is empty and a missing {@code text} block is an empty
 * one, so a caller never meets a null there; {@code image} is null exactly when the service sent
 * none.
 *
 * <p>{@code status} is one of {@link #STATUS_IMAGE}, {@link #STATUS_TEXT_ONLY}, {@link #STATUS_NO_TEXT}
 * and {@link #STATUS_ERROR}; {@code tier} is {@link #TIER_CLEAN} or {@link #TIER_WARNED} for an image
 * and null otherwise; {@code mode} is {@code A} or {@code B}, the attempt that was used.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImageTranslatorResult(
        String requestId,
        String status,
        String tier,
        String reason,
        String mode,
        String verdict,
        Double usability,
        Double coverage,
        Image image,
        Text text,
        Judge judge,
        Costs costs,
        List<String> warnings,
        List<Attempt> attempts) {

    public static final String STATUS_IMAGE = "image";
    public static final String STATUS_TEXT_ONLY = "text_only";
    public static final String STATUS_NO_TEXT = "no_text";
    public static final String STATUS_ERROR = "error";
    public static final String TIER_CLEAN = "clean";
    public static final String TIER_WARNED = "warned";

    public ImageTranslatorResult {
        text = text == null ? Text.EMPTY : text;
        costs = costs == null ? Costs.NONE : costs;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    /** The picture the service made: where to fetch it (a path on the service) and its size. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Image(String path, int width, int height, long bytes) {
    }

    /** What the picture said and what it means in the target language; any part may be null. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Text(String sourceLanguage, String recognizedText, String translatedText) {

        static final Text EMPTY = new Text(null, null, null);
    }

    /** The judge's one-sentence summary of the chosen picture (its findings are not used here). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Judge(String summary) {
    }

    /** What the request cost, in US dollars. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Costs(double anthropicUsd, double openrouterUsd, double totalUsd) {

        static final Costs NONE = new Costs(0, 0, 0);
    }

    /** One try of one mode, as the service reports it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Attempt(
            String mode,
            String verdict,
            Double usability,
            Double costUsd,
            Long elapsedMs,
            String error,
            String skipped,
            boolean selected) {
    }
}
