package com.linetranslate.bot.service.ocr;

/** Selects coverage limits without weakening region-level safety checks. */
public enum OverlayContentMode {
    /** Photographs and anything not recognised as a printed layout: the strictest total limit. */
    GENERAL,
    /** Low-colour text documents: the loosest total limit and a larger single-region limit. */
    DOCUMENT,
    /**
     * Posters and infographics: many reliable horizontal text lines on flat backgrounds, in colour.
     * Not a photo, so a flat-colour mask over a line of text is not visible; a total limit between
     * GENERAL and DOCUMENT, and the per-region limit of GENERAL.
     */
    POSTER
}
